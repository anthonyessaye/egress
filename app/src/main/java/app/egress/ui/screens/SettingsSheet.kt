package app.egress.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.egress.ui.MainViewModel
import app.egress.ui.components.Pill
import app.egress.ui.components.SectionHeader
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type
import app.egress.data.CountryCount
import app.egress.data.TimeRange
import app.egress.ui.components.SelectSheet
import app.egress.ui.components.SheetItem
import app.egress.ui.components.compact
import app.egress.ui.components.flag
import app.egress.ui.components.rememberQuery
import app.egress.vpn.CaptureState
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val ttl by vm.prefs.ttlFlow.collectAsStateWithLifecycle()
    val retention by vm.prefs.retentionFlow.collectAsStateWithLifecycle()
    val home by vm.prefs.home.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var pickCountries by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        scrimColor = Palette.Bg.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Settings", style = Type.Title)
            Spacer(Modifier.height(20.dp))

            SectionHeader("Attribution accuracy")
            Spacer(Modifier.height(6.dp))
            Text(
                "Android caches DNS answers system-wide, so a second app hitting the same server can be invisible. " +
                    "Shortening cache lifetimes catches more of them, at the cost of a few extra lookups.",
                style = Type.Small,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 5 to "5s", 10 to "10s", 30 to "30s", 60 to "60s").forEach { (v, l) ->
                    Pill(l, active = ttl == v, onClick = { vm.prefs.ttlCap = v })
                }
            }

            Spacer(Modifier.height(22.dp))
            BlockingSection(vm) { pickCountries = true }

            Spacer(Modifier.height(22.dp))
            SectionHeader("Keep history for")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 day", 7 to "7 days", 30 to "30 days").forEach { (v, l) ->
                    Pill(l, active = retention == v, onClick = { vm.prefs.retentionDays = v; vm.repo.cleanupNow() })
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionHeader("Your location")
            Spacer(Modifier.height(6.dp))
            Text(
                home?.let { "${it.city}, ${it.cc} — from your public IP, refreshed every few hours. Arcs start here." }
                    ?: "Not known yet (needs one request to ip-api.com).",
                style = Type.Small,
            )

            Spacer(Modifier.height(22.dp))
            SectionHeader("Data")
            Spacer(Modifier.height(10.dp))
            Pill(
                if (confirmClear) "Tap again to erase all history" else "Clear history",
                active = confirmClear,
                onClick = {
                    if (confirmClear) { vm.repo.clearAll(); confirmClear = false; onDismiss() } else confirmClear = true
                },
            )

            Spacer(Modifier.height(22.dp))
            SectionHeader("How it works")
            Spacer(Modifier.height(6.dp))
            Text(
                "Egress runs a local VPN that only intercepts DNS. Your traffic itself never passes through it, so it can't " +
                    "slow anything down. Each lookup is tied to the app that made it by the kernel, and destination IPs " +
                    "are geolocated via ip-api.com (only bare IPs are sent). Locations are where a server sits, which for big " +
                    "services is often a CDN edge near you rather than the company's HQ.",
                style = Type.Small,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Country blocking uses IP to Country Lite by DB-IP.com (CC BY 4.0), bundled offline. " +
                    "Map outlines: Natural Earth.",
                style = Type.Small.copy(color = Palette.TextLo),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
    if (pickCountries) BlockPicker(vm) { pickCountries = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockingSection(vm: MainViewModel, onAdd: () -> Unit) {
    val blocked by vm.prefs.blockedFlow.collectAsStateWithLifecycle()
    val budget by vm.prefs.routeBudgetFlow.collectAsStateWithLifecycle()
    val running by CaptureState.running.collectAsStateWithLifecycle()
    val status by CaptureState.block.collectAsStateWithLifecycle()
    val hits by CaptureState.blockedHits.collectAsStateWithLifecycle()

    SectionHeader("Block countries")
    Spacer(Modifier.height(6.dp))
    Text(
        "Servers in these countries are sinkholed and refused while capture runs. Expect breakage: " +
            "CDNs and cloud regions don't follow borders, so an app you use can depend on a server you've blocked.",
        style = Type.Small,
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocked.sortedBy { countryName(it) }.forEach { cc ->
            Pill(
                "${flag(cc)}  ${countryName(cc)}", active = true,
                leading = { Icon(Icons.Rounded.Block, null, Modifier.size(14.dp), tint = Palette.Red) },
                onClick = { vm.prefs.setBlocked(cc, false) },
            )
        }
        Pill(
            if (blocked.isEmpty()) "Block a country" else "Edit",
            leading = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = Palette.TextMid) },
            onClick = onAdd,
        )
    }
    if (blocked.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        val lines = buildList {
            if (!running) add("Not enforced: capture is paused.")
            else {
                status.countries.sortedBy { countryName(it) }.forEach { cc ->
                    val cov = ((status.coverage[cc] ?: 0f) * 100).toInt()
                    val n = hits[cc] ?: 0
                    add("${countryName(cc)}: routes cover $cov% of its IPv4 space · $n refused")
                }
                add("${compact(status.routes)} routes in use. DNS answers for every blocked country are sunk regardless.")
                hits["DNS"]?.let { add("$it refused via sunk DNS answers") }
            }
            status.error?.let { add(it) }
        }
        lines.forEach { Text(it, style = Type.Mono.copy(fontSize = 11.sp)) }
        Spacer(Modifier.height(12.dp))
        Text("Route budget", style = Type.Small.copy(color = Palette.Text))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000 to "1k", 1500 to "1.5k", 2500 to "2.5k · aggressive").forEach { (v, l) ->
                Pill(l, active = budget == v, onClick = { vm.prefs.routeBudget = v })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (budget > 1500) "Aggressive: Android can't validate a VPN with this many routes, so it shows the " +
                "\"!\" no-internet icon and some apps may think you're offline. Traffic still flows."
            else "More routes cover more of a big country's addresses (the US would need ~270k for 100%). " +
                "Above 1.5k, Android stops validating the network; past ~5k it silently drops every route.",
            style = Type.Small.copy(color = Palette.TextLo),
        )
    }
}

@Composable
private fun BlockPicker(vm: MainViewModel, onDismiss: () -> Unit) {
    val blocked by vm.prefs.blockedFlow.collectAsStateWithLifecycle()
    val seen = rememberQuery(emptyList<CountryCount>(), Unit) { vm.repo.countryOptions(TimeRange.ALL) }
    val counts = seen.associate { it.cc to it.count }
    // Every country, the ones you've actually talked to first.
    val items = remember(counts) {
        Locale.getISOCountries().map { cc -> SheetItem(cc, countryName(cc), counts[cc] ?: 0, flag = flag(cc)) }
            .sortedWith(compareByDescending<SheetItem> { it.count }.thenBy { it.label })
    }
    SelectSheet(
        title = "Block countries",
        items = items,
        selected = blocked,
        onToggle = { cc -> vm.prefs.setBlocked(cc, cc !in blocked) },
        onClear = { vm.prefs.blockedCountries = emptySet() },
        onDismiss = onDismiss,
    )
}

fun countryName(cc: String): String = Locale("", cc).displayCountry.ifEmpty { cc }
