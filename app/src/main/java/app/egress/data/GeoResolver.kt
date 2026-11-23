package app.egress.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/**
 * Geolocates destination IPs with ip-api.com's free batch endpoint (100 IPs/request, 15 requests/min,
 * non-commercial use). Only bare IP addresses are sent — never domains or app names. Results are cached
 * forever in the `geo` table, so each IP is looked up once.
 *
 * Swap this class for an offline MaxMind/DB-IP .mmdb reader if you don't want any third party involved.
 */
class GeoResolver(private val repo: Repository, private val prefs: Prefs) {

    suspend fun run() {
        while (true) {
            var wait = 8_000L
            val pause = pauseUntil - System.currentTimeMillis()
            if (pause > 0) delay(pause)
            try {
                refreshHome()
                val n = resolveBatch()
                if (n > 0) wait = 4_200L // stays under 15 batch requests / minute
            } catch (e: RateLimited) {
                wait = e.seconds * 1000L + 500
            } catch (e: Exception) {
                wait = 30_000L
            }
            delay(wait)
        }
    }

    private class RateLimited(val seconds: Int) : Exception()

    private fun refreshHome() {
        val h = prefs.home.value
        if (h != null && System.currentTimeMillis() - h.ts < 3 * 3_600_000L) return
        // No IP in the path → ip-api reports the caller's own public IP location. We're excluded from the VPN,
        // so this is the real network you're on (follows you between cities and SIMs).
        val body = http("http://ip-api.com/json/?fields=status,country,countryCode,city,lat,lon", null)
        val o = JSONObject(body)
        if (o.optString("status") == "success") {
            prefs.saveHome(
                Home(o.getDouble("lat"), o.getDouble("lon"), o.optString("city"), o.optString("countryCode"), System.currentTimeMillis())
            )
        }
    }

    private fun resolveBatch(): Int {
        val pending = repo.pendingIps(100)
        if (pending.isEmpty()) return 0
        val (routable, local) = pending.partition { isPublic(it) }
        repo.saveGeo(local.map { GeoRow(it, ok = -1) })
        if (routable.isEmpty()) return local.size

        val req = JSONArray().apply { routable.forEach { put(it) } }.toString()
        val body = http(
            "http://ip-api.com/batch?fields=status,country,countryCode,city,lat,lon,isp,org,as,query",
            req,
        )
        val arr = JSONArray(body)
        val rows = ArrayList<GeoRow>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ip = o.optString("query")
            if (ip.isEmpty()) continue
            seen += ip
            rows += if (o.optString("status") == "success") {
                GeoRow(
                    ip = ip, ok = 1,
                    cc = o.optString("countryCode").ifEmpty { null },
                    country = o.optString("country").ifEmpty { null },
                    city = o.optString("city").ifEmpty { null },
                    lat = o.optDouble("lat"), lon = o.optDouble("lon"),
                    org = o.optString("org").ifEmpty { o.optString("isp") }.ifEmpty { null },
                    asn = o.optString("as").ifEmpty { null },
                )
            } else GeoRow(ip, ok = 0)
        }
        routable.filter { it !in seen }.forEach { rows += GeoRow(it, ok = 0) }
        repo.saveGeo(rows)
        return pending.size
    }

    private fun http(url: String, postBody: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            if (postBody != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(postBody.toByteArray()) }
            }
            val code = c.responseCode
            val remaining = c.getHeaderField("X-Rl")?.toIntOrNull()
            val ttl = c.getHeaderField("X-Ttl")?.toIntOrNull() ?: 60
            if (code == 429) throw RateLimited(ttl)
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            // Quota for this minute is used up: keep the data, pause before the next request.
            if (remaining == 0) pauseUntil = System.currentTimeMillis() + ttl * 1000L + 500
            return text
        } finally {
            c.disconnect()
        }
    }

    @Volatile private var pauseUntil = 0L

    companion object {
        fun isPublic(ip: String): Boolean {
            val addr = try {
                // Literal IPs only; never triggers a DNS lookup.
                if (!ip.any { it == '.' || it == ':' }) return false
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                return false
            }
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
            ) return false
            val b = addr.address
            return when (addr) {
                is Inet4Address -> {
                    val b0 = b[0].toInt() and 0xFF
                    val b1 = b[1].toInt() and 0xFF
                    !(b0 == 0 || (b0 == 100 && b1 in 64..127) || (b0 == 198 && b1 in 18..19) || b0 >= 240)
                }
                is Inet6Address -> (b[0].toInt() and 0xFE) != 0xFC // not ULA fc00::/7
                else -> false
            }
        }
    }
}
