package app.egress.data

import android.content.ContentValues
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

enum class TimeRange(val label: String, val millis: Long?) {
    H1("1H", 3_600_000L),
    H6("6H", 6 * 3_600_000L),
    H24("24H", 86_400_000L),
    D7("7D", 7 * 86_400_000L),
    ALL("All", null),
}

data class Filter(
    val range: TimeRange = TimeRange.H24,
    val uids: Set<Int> = emptySet(),
    val countries: Set<String> = emptySet(),
) {
    val hasSelection get() = uids.isNotEmpty() || countries.isNotEmpty()
}

enum class LogStatus(val label: String) {
    ALL("Any status"),
    LOCATED("Located"),
    UNLOCATED("No location"),
    FAILED("Failed"),
    BLOCKED("Blocked"),
}

/** Log-tab-only refinements, applied on top of the global [Filter]. */
data class LogFilter(
    /** Cluster keys: "CC|City". */
    val cities: Set<String> = emptySet(),
    val orgs: Set<String> = emptySet(),
    val status: LogStatus = LogStatus.ALL,
) {
    val active get() = cities.isNotEmpty() || orgs.isNotEmpty() || status != LogStatus.ALL
}

data class CityCount(val key: String, val city: String, val cc: String, val count: Int)

data class Stats(val lookups: Int = 0, val apps: Int = 0, val domains: Int = 0, val countries: Int = 0)
data class Bucket(val start: Long, val count: Int)
data class Histogram(val bucketMs: Long = 0, val buckets: List<Bucket> = emptyList())
data class CountryCount(val cc: String, val country: String, val count: Int)
data class Cluster(
    val key: String, val lat: Double, val lon: Double, val city: String, val cc: String, val country: String,
    val lookups: Int, val domains: Int, val apps: Int,
)
data class DomainCount(val domain: String, val count: Int, val cc: String?)
data class AppCount(val uid: Int, val label: String, val count: Int)
data class OrgCount(val org: String, val count: Int)
data class AppSummary(
    val uid: Int, val label: String, val pkg: String?, val lookups: Int, val domains: Int,
    val lastTs: Long, val countries: List<String>,
)
data class FeedItem(
    val id: Long, val ts: Long, val uid: Int, val label: String, val domain: String, val ok: Boolean,
    val cc: String?, val city: String?, val org: String?, val ips: List<String>, val blocked: Boolean = false,
)
data class ClusterDetail(
    val domains: List<DomainCount> = emptyList(),
    val apps: List<AppCount> = emptyList(),
    val orgs: List<OrgCount> = emptyList(),
)
data class GeoRow(
    val ip: String, val ok: Int, val cc: String? = null, val country: String? = null, val city: String? = null,
    val lat: Double? = null, val lon: Double? = null, val org: String? = null, val asn: String? = null,
)

/**
 * Single writer thread for inserts; reads run on the caller's (IO) thread against the WAL database.
 * A "lookup" = one app resolving one domain at one moment. The A, AAAA and HTTPS queries a single
 * connection attempt fires within a few seconds are merged into one lookup.
 */
class Repository(private val db: Db, private val apps: AppResolver, private val prefs: Prefs) {

    private val _changes = MutableStateFlow(0L)
    /** Bumped after every write; the UI samples it to refresh. */
    val changes: StateFlow<Long> = _changes

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "egress-db-writer") }

    // Writer-thread-only state
    private val recent = HashMap<String, Pair<Long, Long>>() // "uid|domain" -> (lookup id, first ts)
    private val knownApps = HashSet<Int>()
    private var writesSinceCleanup = 0

    init {
        writer.execute {
            runCatching {
                db.readableDatabase.rawQuery("SELECT uid FROM app", null).use { c ->
                    while (c.moveToNext()) knownApps += c.getInt(0)
                }
                cleanup()
            }
        }
    }

    // ------------------------------------------------------------------ writes

    fun record(uid: Int, domain: String, ts: Long, ips: List<String>, ok: Boolean, blocked: Boolean = false) {
        writer.execute {
            try {
                insertLookup(uid, domain, ts, ips, ok, blocked)
            } catch (e: Exception) {
                Log.w("EgressRepo", "insert failed", e)
            }
        }
    }

    private fun insertLookup(uid: Int, domain: String, ts: Long, ips: List<String>, ok: Boolean, blocked: Boolean) {
        val w = db.writableDatabase
        if (uid !in knownApps) {
            val m = apps.meta(uid)
            w.execSQL("INSERT OR REPLACE INTO app(uid, pkg, label) VALUES (?, ?, ?)", arrayOf<Any?>(uid, m.pkg, m.label))
            knownApps += uid
        }
        val key = "$uid|$domain"
        val prev = recent[key]
        // abs(): A and AAAA resolve on parallel threads, so the second record can carry the earlier timestamp.
        val merge = prev != null && kotlin.math.abs(ts - prev.second) <= MERGE_WINDOW_MS
        w.beginTransaction()
        try {
            val id: Long
            if (merge) {
                id = prev!!.first
                if (ok) w.execSQL("UPDATE lookup SET ok = 1 WHERE id = ?", arrayOf<Any>(id))
                if (blocked) w.execSQL("UPDATE lookup SET blocked = 1 WHERE id = ?", arrayOf<Any>(id))
            } else {
                val cv = ContentValues().apply {
                    put("ts", ts); put("uid", uid); put("domain", domain); put("ok", if (ok) 1 else 0)
                    put("blocked", if (blocked) 1 else 0)
                }
                id = w.insert("lookup", null, cv)
            }
            if (id >= 0) {
                for (ip in ips) {
                    w.execSQL("INSERT OR IGNORE INTO answer(lookup_id, ip) VALUES (?, ?)", arrayOf<Any>(id, ip))
                }
                recent[key] = id to (if (merge) prev!!.second else ts)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        if (recent.size > 4000) recent.entries.removeIf { ts - it.value.second > MERGE_WINDOW_MS }
        if (++writesSinceCleanup >= 5000) {
            writesSinceCleanup = 0
            cleanup()
        }
        bump()
    }

    fun saveGeo(rows: List<GeoRow>) {
        if (rows.isEmpty()) return
        writer.execute {
            val w = db.writableDatabase
            val now = System.currentTimeMillis()
            w.beginTransaction()
            try {
                for (r in rows) {
                    w.execSQL(
                        "INSERT OR REPLACE INTO geo(ip, ok, cc, country, city, lat, lon, org, asn, ts) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(r.ip, r.ok, r.cc, r.country, r.city, r.lat, r.lon, r.org, r.asn, now),
                    )
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
            bump()
        }
    }

    fun clearAll() {
        writer.execute {
            val w = db.writableDatabase
            w.execSQL("DELETE FROM answer")
            w.execSQL("DELETE FROM lookup")
            recent.clear()
            bump()
        }
    }

    fun cleanupNow() = writer.execute { cleanup(); bump() }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - prefs.retentionDays * 86_400_000L
        val w = db.writableDatabase
        w.execSQL("DELETE FROM answer WHERE lookup_id IN (SELECT id FROM lookup WHERE ts < $cutoff)")
        w.execSQL("DELETE FROM lookup WHERE ts < $cutoff")
    }

    private fun bump() {
        _changes.value = _changes.value + 1
    }

    // ------------------------------------------------------------------ reads (call from Dispatchers.IO)

    private val rdb get() = db.readableDatabase

    private fun where(f: Filter, extra: String? = null, extraArgs: List<String> = emptyList()): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        f.range.millis?.let { parts += "l.ts >= ${System.currentTimeMillis() - it}" }
        if (f.uids.isNotEmpty()) parts += "l.uid IN (${f.uids.joinToString(",")})"
        if (f.countries.isNotEmpty()) {
            parts += "g.cc IN (${f.countries.joinToString(",") { "?" }})"
            args += f.countries
        }
        if (extra != null) {
            parts += extra
            args += extraArgs
        }
        val clause = if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
        return clause to args.toTypedArray()
    }

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> =
        rdb.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += map(c)
            out
        }

    fun stats(f: Filter): Stats {
        val (w, a) = where(f)
        return query(
            "SELECT COUNT(DISTINCT l.id), COUNT(DISTINCT l.uid), COUNT(DISTINCT l.domain), COUNT(DISTINCT g.cc) $FROM$w", a,
        ) { Stats(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }.firstOrNull() ?: Stats()
    }

    fun histogram(f: Filter): Histogram {
        val now = System.currentTimeMillis()
        val (w, a) = where(f)
        val start = f.range.millis?.let { now - it }
            ?: query("SELECT MIN(l.ts) $FROM$w", a) { if (it.isNull(0)) null else it.getLong(0) }.firstOrNull()
            ?: (now - 3_600_000L)
        val raw = ((now - start) / 36).coerceAtLeast(1)
        val bucketMs = BUCKETS.firstOrNull { it >= raw } ?: BUCKETS.last()
        val aligned = (start / bucketMs) * bucketMs
        val n = ((now - aligned) / bucketMs + 1).toInt().coerceIn(1, 400)
        val counts = IntArray(n)
        query("SELECT (l.ts - $aligned) / $bucketMs AS b, COUNT(DISTINCT l.id) $FROM$w GROUP BY b", a) {
            it.getLong(0).toInt() to it.getInt(1)
        }.forEach { (b, c) -> if (b in 0 until n) counts[b] = c }
        return Histogram(bucketMs, counts.mapIndexed { i, c -> Bucket(aligned + i * bucketMs, c) })
    }

    fun topCountries(f: Filter, limit: Int = 8): List<CountryCount> {
        val (w, a) = where(f, "g.cc IS NOT NULL")
        return query(
            "SELECT g.cc, MAX(g.country), COUNT(DISTINCT l.id) AS n $FROM$w GROUP BY g.cc ORDER BY n DESC LIMIT $limit", a,
        ) { CountryCount(it.getString(0), it.getString(1) ?: it.getString(0), it.getInt(2)) }
    }

    fun clusters(f: Filter, limit: Int = 250): List<Cluster> {
        val (w, a) = where(f, "g.lat IS NOT NULL")
        return query(
            "SELECT $CLUSTER_KEY AS k, AVG(g.lat), AVG(g.lon), MAX(IFNULL(g.city, '')), MAX(g.cc), MAX(g.country), " +
                "COUNT(DISTINCT l.id) AS n, COUNT(DISTINCT l.domain), COUNT(DISTINCT l.uid) $FROM$w GROUP BY k ORDER BY n DESC LIMIT $limit",
            a,
        ) {
            Cluster(
                key = it.getString(0), lat = it.getDouble(1), lon = it.getDouble(2), city = it.getString(3) ?: "",
                cc = it.getString(4) ?: "", country = it.getString(5) ?: "", lookups = it.getInt(6),
                domains = it.getInt(7), apps = it.getInt(8),
            )
        }
    }

    fun clusterDetail(f: Filter, key: String): ClusterDetail {
        val (w, a) = where(f, "$CLUSTER_KEY = ?", listOf(key))
        val domains = query(
            "SELECT l.domain, COUNT(DISTINCT l.id) AS n, MAX(g.cc) $FROM$w GROUP BY l.domain ORDER BY n DESC LIMIT 12", a,
        ) { DomainCount(it.getString(0), it.getInt(1), it.getString(2)) }
        val appsList = query(
            "SELECT l.uid, $LABEL, COUNT(DISTINCT l.id) AS n $FROM$w GROUP BY l.uid ORDER BY n DESC LIMIT 12", a,
        ) { AppCount(it.getInt(0), it.getString(1), it.getInt(2)) }
        val orgs = query(
            "SELECT g.org, COUNT(DISTINCT l.id) AS n $FROM$w AND g.org IS NOT NULL AND g.org != '' GROUP BY g.org ORDER BY n DESC LIMIT 4", a,
        ) { OrgCount(it.getString(0), it.getInt(1)) }
        return ClusterDetail(domains, appsList, orgs)
    }

    fun apps(f: Filter): List<AppSummary> {
        val (w, a) = where(f)
        return query(
            "SELECT l.uid, $LABEL, MAX(p.pkg), COUNT(DISTINCT l.id) AS n, COUNT(DISTINCT l.domain), MAX(l.ts), " +
                "GROUP_CONCAT(DISTINCT g.cc) $FROM$w GROUP BY l.uid ORDER BY n DESC", a,
        ) {
            AppSummary(
                uid = it.getInt(0), label = it.getString(1), pkg = it.getString(2), lookups = it.getInt(3),
                domains = it.getInt(4), lastTs = it.getLong(5),
                countries = it.getString(6)?.split(',')?.filter { s -> s.isNotBlank() }.orEmpty(),
            )
        }
    }

    fun domains(f: Filter, limit: Int = 100): List<DomainCount> {
        val (w, a) = where(f)
        return query(
            "SELECT l.domain, COUNT(DISTINCT l.id) AS n, MAX(g.cc) $FROM$w GROUP BY l.domain ORDER BY n DESC LIMIT $limit", a,
        ) { DomainCount(it.getString(0), it.getInt(1), it.getString(2)) }
    }

    fun feed(f: Filter, search: String, limit: Int = 300, lf: LogFilter = LogFilter()): List<FeedItem> {
        val s = search.trim()
        val extra = ArrayList<String>()
        val extraArgs = ArrayList<String>()
        if (s.isNotEmpty()) {
            extra += "(l.domain LIKE ? OR p.label LIKE ?)"
            extraArgs += listOf("%$s%", "%$s%")
        }
        // EXISTS rather than filtering the joined rows, so a matching lookup still shows all of its addresses.
        if (lf.cities.isNotEmpty()) {
            extra += "EXISTS ($LOCATED_ANSWER AND (g2.cc || '|' || IFNULL(g2.city, '')) IN (${lf.cities.joinToString(",") { "?" }}))"
            extraArgs += lf.cities
        }
        if (lf.orgs.isNotEmpty()) {
            extra += "EXISTS ($LOCATED_ANSWER AND g2.org IN (${lf.orgs.joinToString(",") { "?" }}))"
            extraArgs += lf.orgs
        }
        when (lf.status) {
            LogStatus.ALL -> Unit
            LogStatus.LOCATED -> extra += "EXISTS ($LOCATED_ANSWER)"
            LogStatus.UNLOCATED -> extra += "l.ok = 1 AND NOT EXISTS ($LOCATED_ANSWER)"
            LogStatus.FAILED -> extra += "l.ok = 0"
            LogStatus.BLOCKED -> extra += "l.blocked = 1"
        }
        val (w, a) = if (extra.isEmpty()) where(f) else where(f, extra.joinToString(" AND "), extraArgs)
        return query(
            "SELECT l.id, l.ts, l.uid, $LABEL, l.domain, l.ok, MAX(g.cc), MAX(g.city), MAX(g.org), GROUP_CONCAT(DISTINCT a.ip), l.blocked " +
                "$FROM$w GROUP BY l.id ORDER BY l.ts DESC LIMIT $limit", a,
        ) {
            FeedItem(
                id = it.getLong(0), ts = it.getLong(1), uid = it.getInt(2), label = it.getString(3),
                domain = it.getString(4), ok = it.getInt(5) == 1, cc = it.getString(6), city = it.getString(7),
                org = it.getString(8), ips = it.getString(9)?.split(',').orEmpty(), blocked = it.getInt(10) == 1,
            )
        }
    }

    /** Apps seen in the time range — options for the app filter sheet. */
    fun appOptions(range: TimeRange): List<AppCount> {
        val (w, a) = where(Filter(range))
        return query(
            "SELECT l.uid, $LABEL, COUNT(DISTINCT l.id) AS n $FROM$w GROUP BY l.uid ORDER BY n DESC", a,
        ) { AppCount(it.getInt(0), it.getString(1), it.getInt(2)) }
    }

    fun countryOptions(range: TimeRange): List<CountryCount> = topCountries(Filter(range), limit = 250)

    /** Destination cities within the global filter — options for the Log's city filter. */
    fun cityOptions(f: Filter): List<CityCount> {
        val (w, a) = where(f, "g.cc IS NOT NULL")
        return query(
            "SELECT $CLUSTER_KEY AS k, MAX(IFNULL(g.city, '')), MAX(g.cc), COUNT(DISTINCT l.id) AS n $FROM$w " +
                "GROUP BY k ORDER BY n DESC LIMIT 400", a,
        ) { CityCount(it.getString(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getInt(3)) }
    }

    /** Network operators (ip-api "org", falling back to ISP) within the global filter. */
    fun orgOptions(f: Filter): List<OrgCount> {
        val (w, a) = where(f, "g.org IS NOT NULL AND g.org != ''")
        return query(
            "SELECT g.org, COUNT(DISTINCT l.id) AS n $FROM$w GROUP BY g.org ORDER BY n DESC LIMIT 400", a,
        ) { OrgCount(it.getString(0), it.getInt(1)) }
    }

    fun pendingIps(limit: Int): List<String> {
        val retryBefore = System.currentTimeMillis() - 6 * 3_600_000L
        return query(
            "SELECT DISTINCT a.ip FROM answer a LEFT JOIN geo g ON g.ip = a.ip " +
                "WHERE g.ip IS NULL OR (g.ok = 0 AND g.ts < $retryBefore) LIMIT $limit",
            emptyArray(),
        ) { it.getString(0) }
    }

    companion object {
        private const val MERGE_WINDOW_MS = 3_000L
        private const val FROM =
            " FROM lookup l LEFT JOIN answer a ON a.lookup_id = l.id " +
                "LEFT JOIN geo g ON g.ip = a.ip AND g.ok = 1 LEFT JOIN app p ON p.uid = l.uid"
        private const val LABEL = "IFNULL(MAX(p.label), 'UID ' || l.uid)"
        private const val CLUSTER_KEY = "(g.cc || '|' || IFNULL(g.city, ''))"
        private const val LOCATED_ANSWER =
            "SELECT 1 FROM answer a2 JOIN geo g2 ON g2.ip = a2.ip AND g2.ok = 1 WHERE a2.lookup_id = l.id"
        private val BUCKETS = listOf(
            60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L,
            7_200_000L, 10_800_000L, 21_600_000L, 43_200_000L, 86_400_000L, 7 * 86_400_000L,
        )
    }
}
