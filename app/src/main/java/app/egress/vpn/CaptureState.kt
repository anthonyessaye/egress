package app.egress.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide capture status, observed by the UI. */
object CaptureState {
    val running = MutableStateFlow(false)
    val startedAt = MutableStateFlow(0L)
    /** True when the underlying network uses Private DNS in strict (hostname) mode, which bypasses the tap. */
    val privateDnsStrict = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    /** What the country block is currently enforcing (empty when nothing is blocked or capture is off). */
    val block = MutableStateFlow(BlockStatus())
    /** Connections to blocked countries refused at the route layer since capture started, per country. */
    val blockedHits = MutableStateFlow<Map<String, Int>>(emptyMap())
}

data class BlockStatus(
    val countries: Set<String> = emptySet(),
    val routes: Int = 0,
    /** Share of each blocked country's IPv4 space covered by routes. DNS blocking covers it all regardless. */
    val coverage: Map<String, Float> = emptyMap(),
    val error: String? = null,
)
