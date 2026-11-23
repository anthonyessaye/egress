package app.egress.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Home(val lat: Double, val lon: Double, val city: String, val cc: String, val ts: Long)

const val DEFAULT_ROUTE_BUDGET = 1500
/**
 * Hard ceiling. Android ships the whole route list to system services in one binder parcel (~146 bytes/route).
 * Measured on an API 36 emulator, cold start:
 *   1,000 / 1,500 routes (~147 / ~220 KB): clean, VPN network VALIDATED.
 *   2,000 – 3,000 (~293 – 440 KB): routes install and blocking works, but NetworkMonitor can't receive the network
 *     (TransactionTooLargeException), so the VPN stays NOT validated: the "!" icon, and apps that wait for a
 *     validated network may act offline.
 *   5,000 (731 KB): fails *silently*: establish() succeeds but no routes are installed, not even the DNS one.
 */
const val MAX_ROUTE_BUDGET = 2500

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("egress", Context.MODE_PRIVATE)

    /** Max TTL (seconds) handed back to apps. 0 = untouched. Lower = better per-app attribution. */
    var ttlCap: Int
        get() = sp.getInt("ttl_cap", 10)
        set(v) { sp.edit().putInt("ttl_cap", v).apply(); _ttl.value = v }
    private val _ttl = MutableStateFlow(ttlCap)
    val ttlFlow: StateFlow<Int> = _ttl

    var retentionDays: Int
        get() = sp.getInt("retention_days", 7)
        set(v) { sp.edit().putInt("retention_days", v).apply(); _ret.value = v }
    private val _ret = MutableStateFlow(retentionDays)
    val retentionFlow: StateFlow<Int> = _ret

    /** ISO codes of countries whose servers are blocked while capture runs. */
    var blockedCountries: Set<String>
        get() = sp.getStringSet("blocked_countries", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("blocked_countries", v.toSet()).apply(); _blocked.value = v.toSet() }
    private val _blocked = MutableStateFlow(blockedCountries)
    val blockedFlow: StateFlow<Set<String>> = _blocked

    fun setBlocked(cc: String, block: Boolean) {
        blockedCountries = if (block) blockedCountries + cc else blockedCountries - cc
    }

    /** Max routes handed to Android for country blocking. Bigger = more of a big country's address space covered. */
    var routeBudget: Int
        get() = sp.getInt("route_budget", DEFAULT_ROUTE_BUDGET).coerceIn(0, MAX_ROUTE_BUDGET)
        set(v) { sp.edit().putInt("route_budget", v).apply(); _budget.value = v }
    private val _budget = MutableStateFlow(routeBudget)
    val routeBudgetFlow: StateFlow<Int> = _budget

    private val _home = MutableStateFlow(readHome())
    val home: StateFlow<Home?> = _home

    fun saveHome(h: Home) {
        sp.edit()
            .putString("home_lat", h.lat.toString()).putString("home_lon", h.lon.toString())
            .putString("home_city", h.city).putString("home_cc", h.cc).putLong("home_ts", h.ts)
            .apply()
        _home.value = h
    }

    private fun readHome(): Home? {
        val lat = sp.getString("home_lat", null)?.toDoubleOrNull() ?: return null
        val lon = sp.getString("home_lon", null)?.toDoubleOrNull() ?: return null
        return Home(lat, lon, sp.getString("home_city", "") ?: "", sp.getString("home_cc", "") ?: "", sp.getLong("home_ts", 0))
    }
}
