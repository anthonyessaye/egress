package app.egress.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import app.egress.data.CityCount
import app.egress.data.Filter
import app.egress.data.LogFilter
import app.egress.data.LogStatus
import app.egress.data.OrgCount
import app.egress.ui.components.Pill
import app.egress.ui.components.SelectSheet
import app.egress.ui.components.SheetItem
import app.egress.data.FeedItem
import app.egress.ui.MainViewModel
import app.egress.ui.components.AppIcon
import app.egress.ui.components.EmptyHint
import app.egress.ui.components.FilterBar
import app.egress.ui.components.LinkText
import app.egress.ui.components.SearchField
import app.egress.ui.components.clock
import app.egress.ui.components.dayClock
import app.egress.ui.components.flag
import app.egress.ui.components.plural
import app.egress.ui.components.rememberQuery
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type
import app.egress.vpn.CaptureState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(vm: MainViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val logFilter by vm.logFilter.collectAsStateWithLifecycle()
    val search by vm.logSearch.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val running by CaptureState.running.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<Long?>(null) }

    val feed = rememberQuery(emptyList<FeedItem>(), filter, logFilter, tick, search) {
        vm.repo.feed(filter, search, LOG_LIMIT, logFilter)
    }
    val now = System.currentTimeMillis()
    val anyFilter = filter.hasSelection || logFilter.active || search.isNotBlank()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log", style = Type.Hero, modifier = Modifier.weight(1f))
                    if (running) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Lime))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE", style = Type.Label.copy(color = Palette.Lime))
                    }
                }
                Text("Every lookup, newest first", style = Type.Small)
                Spacer(Modifier.height(14.dp))
            }
        }
        stickyHeader {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg, Palette.Bg.copy(alpha = 0.92f))))
                    .padding(bottom = 10.dp),
            ) {
                SearchField(search, { vm.logSearch.value = it }, "Search domain or app", Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(10.dp))
                FilterBar(filter, tick, vm::setFilter)
                Spacer(Modifier.height(8.dp))
                LogFilterBar(vm, filter, logFilter, tick)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (feed.size >= LOG_LIMIT) "Latest ${LOG_LIMIT} lookups" else plural(feed.size, "lookup"),
                        style = Type.Mono, modifier = Modifier.weight(1f),
                    )
                    if (anyFilter) LinkText("Clear all") {
                        vm.setFilter(filter.copy(uids = emptySet(), countries = emptySet()))
                        vm.logFilter.value = LogFilter()
                        vm.logSearch.value = ""
                    }
                }
            }
        }
        if (feed.isEmpty()) item { EmptyHint("No matching lookups", "Widen the time range or clear filters.") }
        items(feed, key = { it.id }) { f ->
            FeedRow(
                f, now,
                expanded = expanded == f.id,
                onClick = { expanded = if (expanded == f.id) null else f.id },
                onApp = { vm.openApp(f.uid) },
            )
        }
    }
}

private const val LOG_LIMIT = 500

/** Log-only refinements: destination city, network operator and lookup status. */
@Composable
private fun LogFilterBar(vm: MainViewModel, filter: Filter, lf: LogFilter, tick: Long) {
    var sheet by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            when (lf.cities.size) {
                0 -> "All cities"
                1 -> lf.cities.first().substringAfter('|').ifEmpty { lf.cities.first().substringBefore('|') }
                else -> "${lf.cities.size} cities"
            },
            active = lf.cities.isNotEmpty(),
            leading = { Icon(Icons.Rounded.LocationCity, null, Modifier.size(16.dp), tint = Palette.TextMid) },
            onClick = { sheet = "cities" },
        )
        Pill(
            when (lf.orgs.size) {
                0 -> "All providers"
                1 -> lf.orgs.first()
                else -> "${lf.orgs.size} providers"
            },
            active = lf.orgs.isNotEmpty(),
            leading = { Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp), tint = Palette.TextMid) },
            onClick = { sheet = "orgs" },
        )
        LogStatus.entries.filter { it != LogStatus.ALL }.forEach { st ->
            val on = lf.status == st
            Pill(
                st.label, active = on,
                leading = {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            when (st) {
                                LogStatus.FAILED -> Palette.Amber
                                LogStatus.UNLOCATED -> Palette.TextLo
                                LogStatus.BLOCKED -> Palette.Red
                                else -> Palette.Lime
                            }
                        )
                    )
                },
                onClick = { vm.logFilter.value = lf.copy(status = if (on) LogStatus.ALL else st) },
            )
        }
    }

    when (sheet) {
        "cities" -> {
            val options = rememberQuery(emptyList<CityCount>(), filter, tick) { vm.repo.cityOptions(filter) }
            SelectSheet(
                title = "Filter by destination city",
                items = options.map {
                    SheetItem(it.key, if (it.city.isEmpty()) "${it.cc} (city unknown)" else "${it.city}, ${it.cc}", it.count, flag = flag(it.cc))
                },
                selected = lf.cities,
                onToggle = { k -> vm.logFilter.value = lf.copy(cities = if (k in lf.cities) lf.cities - k else lf.cities + k) },
                onClear = { vm.logFilter.value = lf.copy(cities = emptySet()) },
                onDismiss = { sheet = null },
            )
        }
        "orgs" -> {
            val options = rememberQuery(emptyList<OrgCount>(), filter, tick) { vm.repo.orgOptions(filter) }
            SelectSheet(
                title = "Filter by provider",
                items = options.map { SheetItem(it.org, it.org, it.count) },
                selected = lf.orgs,
                onToggle = { k -> vm.logFilter.value = lf.copy(orgs = if (k in lf.orgs) lf.orgs - k else lf.orgs + k) },
                onClear = { vm.logFilter.value = lf.copy(orgs = emptySet()) },
                onDismiss = { sheet = null },
            )
        }
    }
}

@Composable
private fun FeedRow(f: FeedItem, now: Long, expanded: Boolean, onClick: () -> Unit, onApp: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIcon(f.uid, f.label, 38.dp)
                if (!f.ok || f.blocked) {
                    Box(
                        Modifier.size(10.dp).align(Alignment.TopEnd).clip(CircleShape)
                            .background(if (f.blocked) Palette.Red else Palette.Amber)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.domain, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (f.blocked) {
                        Spacer(Modifier.width(6.dp))
                        Text("BLOCKED", style = Type.Label.copy(color = Palette.Red, fontSize = 9.sp))
                    }
                }
                Text(
                    buildString {
                        append(f.label)
                        if (f.cc != null) append("  ·  ").append(flag(f.cc)).append(' ').append(f.city ?: f.cc)
                        else if (f.ips.isEmpty()) append("  ·  no address")
                    },
                    style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (now - f.ts < 86_400_000L) clock(f.ts) else dayClock(f.ts),
                style = Type.Mono.copy(color = Palette.TextLo, fontSize = 11.sp),
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier
                    .padding(start = 50.dp, top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.SurfaceHi)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (f.org != null) Text(f.org, style = Type.Small.copy(color = Palette.Text))
                if (f.blocked) Text("Answer pointed into a blocked country; the app was given a sinkhole address instead", style = Type.Small.copy(color = Palette.Red))
                if (!f.ok) Text("Lookup failed or returned an error", style = Type.Small.copy(color = Palette.Amber))
                f.ips.forEach { Text(it, style = Type.Mono) }
                if (f.ips.isEmpty()) Text("No A/AAAA answers", style = Type.Small)
                Spacer(Modifier.height(2.dp))
                LinkText("Open ${f.label}", onApp)
            }
        }
    }
}
