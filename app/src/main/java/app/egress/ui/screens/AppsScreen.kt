package app.egress.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.egress.EgressApp
import app.egress.data.AppSummary
import app.egress.data.CountryCount
import app.egress.data.DomainCount
import app.egress.data.Filter
import app.egress.data.Histogram
import app.egress.data.Stats
import app.egress.ui.MainViewModel
import app.egress.ui.components.ActivityChart
import app.egress.ui.components.AppIcon
import app.egress.ui.components.EmptyHint
import app.egress.ui.components.FilterBar
import app.egress.ui.components.GlassCard
import app.egress.ui.components.MeterBar
import app.egress.ui.components.SectionHeader
import app.egress.ui.components.StatTile
import app.egress.ui.components.ago
import app.egress.ui.components.compact
import app.egress.ui.components.plural
import app.egress.ui.components.flag
import app.egress.ui.components.rememberQuery
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type

@Composable
fun AppsScreen(vm: MainViewModel) {
    val detail by vm.appDetail.collectAsStateWithLifecycle()
    val uid = detail
    if (uid != null) AppDetailScreen(vm, uid) else AppList(vm)
}

@Composable
private fun AppList(vm: MainViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    // The app list ignores the app filter itself (it *is* the app picker) but honours time + country.
    val listFilter = filter.copy(uids = emptySet())
    val apps = rememberQuery(emptyList<AppSummary>(), listFilter, tick) { vm.repo.apps(listFilter) }
    val max = apps.maxOfOrNull { it.lookups }?.coerceAtLeast(1) ?: 1

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text("Apps", style = Type.Hero)
                Text(plural(apps.size, "app") + " reached out", style = Type.Small)
            }
        }
        item { FilterBar(listFilter, tick, { vm.setFilter(it.copy(uids = filter.uids)) }, showApps = false) }
        if (apps.isEmpty()) item { EmptyHint("No apps yet", "Start capture, then use your phone normally.") }
        items(apps, key = { it.uid }) { a ->
            GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), onClick = { vm.openApp(a.uid) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.uid, a.label, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, style = Type.Body.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(compact(a.lookups), style = Type.Mono.copy(color = Palette.Text, fontSize = 13.sp))
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                plural(a.domains, "domain") + " · ${ago(a.lastTs)} ago",
                                style = Type.Small, modifier = Modifier.weight(1f), maxLines = 1,
                            )
                            Text(
                                a.countries.take(5).joinToString("") { flag(it) } + if (a.countries.size > 5) " +${a.countries.size - 5}" else "",
                                fontSize = 13.sp, color = Palette.TextMid,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        MeterBar(a.lookups / max.toFloat(), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetailScreen(vm: MainViewModel, uid: Int) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val f = Filter(range = filter.range, uids = setOf(uid), countries = filter.countries)
    val meta = remember(uid) { EgressApp.get().apps.meta(uid) }

    val stats = rememberQuery(Stats(), f, tick) { vm.repo.stats(f) }
    val hist = rememberQuery(Histogram(), f, tick) { vm.repo.histogram(f) }
    val countries = rememberQuery(emptyList<CountryCount>(), f, tick) { vm.repo.topCountries(f, 12) }
    val domains = rememberQuery(emptyList<DomainCount>(), f, tick) { vm.repo.domains(f, 60) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(start = 12.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).clickable { vm.appDetail.value = null },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(uid, meta.label, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(meta.label, style = Type.Title.copy(fontSize = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(meta.pkg ?: "uid $uid", style = Type.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Palette.Accent)
                    .clickable { vm.showAppOnMap(uid) }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Public, null, tint = Palette.Bg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show on map", style = Type.Body.copy(color = Palette.Bg, fontWeight = FontWeight.SemiBold))
            }
        }
        item { FilterBar(filter, tick, vm::setFilter, showApps = false) }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Lookups", compact(stats.lookups), Palette.Cyan, Modifier.weight(1f))
                StatTile("Domains", compact(stats.domains), Palette.Magenta, Modifier.weight(1f))
                StatTile("Countries", compact(stats.countries), Palette.Lime, Modifier.weight(1f))
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                SectionHeader("Activity")
                Spacer(Modifier.height(14.dp))
                if (stats.lookups == 0) EmptyHint("No lookups in this window", "Try a longer time range.")
                else ActivityChart(hist)
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                SectionHeader("Countries")
                Spacer(Modifier.height(6.dp))
                if (countries.isEmpty()) EmptyHint("Not located yet", "Geolocation runs a few seconds behind capture.")
                val max = countries.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                countries.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(flag(c.cc), fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(c.country, style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(compact(c.count), style = Type.Mono.copy(color = Palette.Text))
                            }
                            Spacer(Modifier.height(5.dp))
                            MeterBar(c.count / max.toFloat(), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                SectionHeader("Domains")
                Spacer(Modifier.height(6.dp))
                domains.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(flag(d.cc), fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            d.domain, style = Type.Mono.copy(color = Palette.Text, fontSize = 12.5.sp),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Palette.SurfaceTop)
                                .border(1.dp, Palette.Stroke, RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) { Text(compact(d.count), style = Type.Mono) }
                    }
                }
            }
        }
    }
}
