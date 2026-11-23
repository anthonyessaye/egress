package app.egress.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.egress.EgressApp
import app.egress.R
import app.egress.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "DNS tap" VPN, plus optional country blocking.
 *
 * Only a single virtual resolver address (10.111.222.53) is routed into the tunnel, and it is handed to
 * every app as its DNS server. All other traffic keeps flowing over the normal network untouched, so the
 * monitor cannot break connectivity or slow apps down. For every query we:
 *   1. ask the kernel which app (UID) owns the socket  → per-app attribution
 *   2. forward it to the real upstream resolver over a protected socket
 *   3. read the answer IPs (and cap TTLs so cached answers don't hide other apps' lookups)
 *   4. write the response back into the tunnel and record (app, domain, IPs) for geolocation.
 *
 * Country blocking adds two layers, both enforced only while capture runs:
 *   - DNS: answers pointing into a blocked country are rewritten to a sinkhole address inside the tunnel.
 *   - Routes: the blocked countries' largest IP prefixes (up to a budget Android can take) are routed into
 *     the tunnel too, catching hard-coded IPs and apps with their own DNS.
 * Nothing that enters the tunnel besides DNS is ever forwarded: TCP gets an immediate reset, UDP is dropped.
 */
class EgressVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.egress.action.START"
        const val ACTION_STOP = "app.egress.action.STOP"
        private const val TAG = "EgressVpn"
        private const val VPN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.53"
        private const val VPN_ADDR6 = "fd65:6772:6573:7300::1"
        private const val SINK_V4 = "10.111.222.66"
        private const val SINK_V6 = "fd65:6772:6573:7300::66"
        /** TTL given to sunk answers, so unblocking takes effect quickly. */
        private const val SINK_TTL = 5
        private const val MTU = 8192
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 42
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    }

    private val dnsAddrBytes = byteArrayOf(10, 111, 222.toByte(), 53)
    private val sinkV4 = InetAddress.getByName(SINK_V4).address
    private val sinkV6 = InetAddress.getByName(SINK_V6).address

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunOut: FileOutputStream? = null
    private val writeLock = Any()
    @Volatile private var running = false
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var upstream: List<InetAddress> = emptyList()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var cm: ConnectivityManager

    /** All tunnel (re)configuration happens here, in order. */
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "egress-vpn-control") }
    private var scope: CoroutineScope? = null

    /** Countries enforced by the current tunnel, and the table used to check answers against them. */
    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var ipDb: IpCountryDb? = null
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var lastHitPublish = 0L

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START, a sticky restart (null intent) or the system's always-on VPN start.
        startCapture()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked consent in Settings.
        stopCapture()
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        control.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    @OptIn(FlowPreview::class)
    private fun startCapture() {
        if (running) return
        running = true
        goForeground()
        watchUnderlyingNetwork()
        // Each forward blocks on the network, so keep enough threads that one slow upstream doesn't stall the rest.
        pool = ThreadPoolExecutor(
            16, 16, 30, TimeUnit.SECONDS, LinkedBlockingQueue(2048),
            ThreadPoolExecutor.DiscardOldestPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
        hits.clear()
        CaptureState.blockedHits.value = emptyMap()
        CaptureState.lastError.value = null

        control.execute { establishTunnel() }

        // Re-establish the tunnel when the block list or the route budget changes. Android swaps the interface
        // in place, so apps keep their connectivity.
        val prefs = EgressApp.get().prefs
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                combine(prefs.blockedFlow, prefs.routeBudgetFlow) { b, n -> b to n }
                    .drop(1)
                    .debounce(400)
                    .collect { control.execute { if (running) establishTunnel() } }
            }
        }
    }

    /** Builds (or rebuilds) the TUN interface for the current block list. Runs on [control]. */
    private fun establishTunnel() {
        if (!running) return
        val prefs = EgressApp.get().prefs
        val wanted = prefs.blockedCountries
        var error: String? = null

        val db = if (wanted.isEmpty()) null else try {
            EgressApp.get().ipDb
        } catch (e: Exception) {
            Log.e(TAG, "ipcountry.bin", e)
            error = "Couldn't load the country database, so nothing is blocked."
            null
        }
        var plan = db?.plan(wanted, prefs.routeBudget.coerceAtMost(app.egress.data.MAX_ROUTE_BUDGET))

        var fd = establish(wanted.isNotEmpty() && db != null, plan)
        if (fd == null && plan != null && plan.size > 0) {
            // Too many routes for this device: keep blocking at the DNS layer only.
            Log.w(TAG, "establish with ${plan.size} routes failed; retrying DNS-only")
            error = "Android refused ${plan.size} routes. Blocking at the DNS layer only; lower the route budget."
            plan = RoutePlan(emptyList(), emptyList(), wanted.associateWith { 0f })
            fd = establish(true, plan)
        }
        if (fd == null) {
            CaptureState.lastError.value = "Android refused to start the VPN. Open Egress and grant VPN permission."
            stopCapture()
            stopSelf()
            return
        }
        if (!running) {
            runCatching { fd.close() }
            return
        }

        val old = tun
        tun = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        ipDb = db
        blocked = if (db != null) wanted else emptySet()
        Thread({ readLoop(fd) }, "egress-tun-reader").start()
        try { old?.close() } catch (_: IOException) { }

        CaptureState.startedAt.value = CaptureState.startedAt.value.takeIf { old != null } ?: System.currentTimeMillis()
        CaptureState.running.value = true
        CaptureState.block.value = BlockStatus(blocked, plan?.size ?: 0, plan?.coverage.orEmpty(), error)
        updateNotification()
        Log.i(TAG, "tunnel up: blocking=$blocked routes=${plan?.size ?: 0}")
    }

    private fun establish(blocking: Boolean, plan: RoutePlan?): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Egress")
            .setMtu(MTU)
            .addAddress(VPN_ADDR, 32)
            .addRoute(DNS_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .setBlocking(true)
            // Without this Android marks the whole device "metered" while the VPN is up.
            .setMetered(false)
        try {
            builder.addDisallowedApplication(packageName) // our own geo lookups bypass the tap
        } catch (_: PackageManager.NameNotFoundException) { }
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        if (blocking) {
            builder.addAddress(VPN_ADDR6, 128)
            builder.addRoute(SINK_V4, 32)
            builder.addRoute(SINK_V6, 128)
            plan?.v4?.forEach { builder.addRoute(InetAddress.getByAddress(it.address), it.prefix) }
            plan?.v6?.forEach { builder.addRoute(InetAddress.getByAddress(it.address), it.prefix) }
        } else {
            // A VPN that configures no IPv6 at all blocks IPv6 for every app. We only need IPv6 for blocking.
            builder.allowFamily(OsConstants.AF_INET6)
        }
        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
    }

    private fun stopCapture() {
        running = false
        scope?.cancel()
        scope = null
        pool?.shutdownNow()
        pool = null
        try { tun?.close() } catch (_: IOException) { }
        tun = null
        tunOut = null
        blocked = emptySet()
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        CaptureState.running.value = false
        CaptureState.block.value = BlockStatus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ---------------------------------------------------------------- packet loop

    private fun readLoop(tunFd: ParcelFileDescriptor) {
        val input = FileInputStream(tunFd.fileDescriptor)
        val buf = ByteArray(MTU + 64)
        val pfd = StructPollfd()
        pfd.fd = tunFd.fileDescriptor
        pfd.events = OsConstants.POLLIN.toShort()
        // Exit when capture stops or this interface was replaced by a newer one.
        while (running && tun === tunFd) {
            try {
                // Poll with a timeout so the loop notices stopCapture() even if no packets arrive.
                if (Os.poll(arrayOf(pfd), 500) <= 0) continue
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                handlePacket(buf, n)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                break
            } catch (e: IOException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "packet loop", e)
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        // Only the resolver, the sinks and blocked countries' prefixes are routed here, so anything that
        // isn't a DNS query is blocked traffic.
        when (val p = Packets.parse(buf, len)) {
            is Packets.Udp -> if (p.dstPort == 53 && p.dst.contentEquals(dnsAddrBytes)) {
                pool?.execute { resolve(p) }
            } // else: UDP (QUIC, …) to a blocked destination: drop. Apps fall back to TCP, which gets reset.
            is Packets.Tcp -> {
                // DNS-over-TCP / DNS-over-TLS probes to our resolver, or a connection to a blocked country:
                // refuse immediately so nothing hangs until a timeout.
                Packets.buildTcpReset(p)?.let(::write)
                if (p.syn && !p.hasAck && !p.dst.contentEquals(dnsAddrBytes)) countHit(p.dst)
            }
            is Packets.Other, null -> Unit
        }
    }

    private fun countHit(dst: ByteArray) {
        val key = when {
            dst.contentEquals(sinkV4) || dst.contentEquals(sinkV6) -> "DNS"
            else -> ipDb?.country(dst) ?: "??"
        }
        hits.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastHitPublish > 1000) {
            lastHitPublish = now
            CaptureState.blockedHits.value = hits.mapValues { it.value.get() }
        }
    }

    private fun resolve(p: Packets.Udp) {
        val query = Dns.parseQuery(p.payload) ?: return
        val ts = System.currentTimeMillis()
        val uid = ownerUid(p)

        var response: ByteArray? = null
        for (server in upstreamServers()) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 2500
                    s.send(DatagramPacket(p.payload, p.payload.size, server, 53))
                    val rb = ByteArray(MTU)
                    val dp = DatagramPacket(rb, rb.size)
                    s.receive(dp)
                    val candidate = rb.copyOf(dp.length)
                    if (candidate.size >= 12 && Packets.u16(candidate, 0) == query.id) response = candidate
                }
            } catch (_: IOException) {
                // timeout / unreachable → try the next server
            }
            if (response != null) break
        }

        val resp = response
        if (resp == null) {
            record(uid, query, ts, emptyList(), ok = false, blocked = false)
            return
        }
        // Read the real addresses first (they're what gets logged and mapped), then sink blocked ones.
        val answer = Dns.inspectResponse(resp, EgressApp.get().prefs.ttlCap)
        val bl = blocked
        val db = ipDb
        val sunk = if (bl.isEmpty() || db == null) emptyList() else {
            Dns.sinkAddresses(resp, sinkV4, sinkV6, SINK_TTL) { ip -> db.country(ip) in bl }
        }
        val maxPayload = MTU - 28
        if (resp.size <= maxPayload) {
            write(Packets.buildUdp(p.dst, p.src, 53, p.srcPort, resp))
        }
        record(uid, query, ts, answer?.ips.orEmpty(), ok = answer != null && answer.rcode == 0, blocked = sunk.isNotEmpty())
    }

    private fun record(uid: Int, q: Dns.Query, ts: Long, ips: List<String>, ok: Boolean, blocked: Boolean) {
        val name = q.name.trimEnd('.')
        if (name.isEmpty() || name.endsWith(".arpa") || name.endsWith(".local")) return
        // A, AAAA and HTTPS queries for the same name are merged by the repository into one "lookup".
        EgressApp.get().repo.record(uid, name, ts, ips, ok, blocked)
    }

    private fun ownerUid(p: Packets.Udp): Int = try {
        cm.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(p.src), p.srcPort),
            InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort),
        )
    } catch (e: Exception) {
        -1
    }

    private fun write(packet: ByteArray) {
        val out = tunOut ?: return
        synchronized(writeLock) {
            try { out.write(packet) } catch (_: IOException) { }
        }
    }

    // ---------------------------------------------------------------- upstream resolvers

    private fun upstreamServers(): List<InetAddress> {
        val list = upstream
        return list.ifEmpty { FALLBACK_DNS.map { InetAddress.getByName(it) } }
    }

    private fun watchUnderlyingNetwork() {
        if (netCallback != null) return
        // We exclude ourselves from the VPN, so *our* default network is the real Wi-Fi / mobile network.
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                applyLinkProperties(lp)
            }
        }
        netCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        cm.activeNetwork?.let { n -> cm.getLinkProperties(n)?.let { applyLinkProperties(it) } }
    }

    private fun applyLinkProperties(lp: LinkProperties) {
        val servers = lp.dnsServers.filter { !it.hostAddress.orEmpty().startsWith("10.111.222.") }
        // Prefer IPv4 servers first (no scope-id surprises), then IPv6, then public fallbacks.
        val ordered = servers.sortedBy { if (it is Inet4Address) 0 else 1 } +
            FALLBACK_DNS.map { InetAddress.getByName(it) }
        upstream = ordered.distinct()
        CaptureState.privateDnsStrict.value = lp.privateDnsServerName != null
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Egress is mapping network traffic"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, EgressVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bl = blocked
        val text = if (bl.isEmpty()) "Watching which apps contact which servers"
        else "Blocking " + bl.sorted().joinToString(", ") { Locale("", it).displayCountry.ifEmpty { it } }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_egress)
            .setContentTitle("Mapping outbound traffic")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF3CF0FF.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }
}
