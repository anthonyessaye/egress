package app.egress.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.egress.data.Cluster
import app.egress.data.ClusterDetail
import app.egress.ui.MainViewModel
import app.egress.ui.components.AppIcon
import app.egress.ui.components.BottomFade
import app.egress.ui.components.FilterBar
import app.egress.ui.components.GlassCard
import app.egress.ui.components.MeterBar
import app.egress.ui.components.Pill
import app.egress.ui.components.SectionHeader
import app.egress.ui.components.TopFade
import app.egress.ui.components.WorldMap
import app.egress.ui.components.compact
import app.egress.ui.components.flag
import app.egress.ui.components.plural
import app.egress.ui.components.rememberQuery
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type

@Composable
fun MapScreen(vm: MainViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val home by vm.prefs.home.collectAsStateWithLifecycle()
    val selected by vm.selectedCluster.collectAsStateWithLifecycle()
    val blocked by vm.prefs.blockedFlow.collectAsStateWithLifecycle()
    var focus by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val clusters = rememberQuery(emptyList<Cluster>(), filter, tick) { vm.repo.clusters(filter) }
    val selectedCluster = clusters.firstOrNull { it.key == selected }

    // Filtering down to a single country (e.g. from the Pulse breakdown): fly to its busiest city.
    val singleCountry = filter.countries.singleOrNull()
    LaunchedEffect(singleCountry, clusters.firstOrNull()?.cc == singleCountry) {
        if (singleCountry == null) return@LaunchedEffect
        clusters.firstOrNull { it.cc == singleCountry }?.let { focus = it.lat to it.lon }
    }

    Box(Modifier.fillMaxSize()) {
        WorldMap(
            clusters = clusters,
            home = home,
            selectedKey = selected,
            onSelect = { vm.selectedCluster.value = it },
            focus = focus,
            blocked = blocked,
        )
        TopFade(190.dp)
        BottomFade(300.dp)

        Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Destinations", style = Type.Hero)
                    val cities = clusters.size
                    val countries = clusters.map { it.cc }.distinct().size
                    Text(
                        if (cities == 0) "Nothing located yet"
                        else plural(cities, "city", "cities") + " · " + plural(countries, "country", "countries") + (home?.let { " · from ${it.city.ifEmpty { it.cc }}" } ?: ""),
                        style = Type.Small,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FilterBar(filter, tick, vm::setFilter)
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 86.dp),
        ) {
            AnimatedContent(
                targetState = selectedCluster,
                transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() },
                contentKey = { it?.key },
                label = "detail",
            ) { c ->
                if (c != null) {
                    ClusterCard(c, vm, onClose = { vm.selectedCluster.value = null })
                } else if (clusters.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(clusters.take(20), key = { it.key }) { cl ->
                            DestinationChip(cl, clusters.first().lookups) {
                                vm.selectedCluster.value = cl.key
                                focus = cl.lat to cl.lon
                            }
                        }
                    }
                } else {
                    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        Text("No destinations yet", style = Type.Body)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Start capture on the Pulse tab. Each server an app contacts lands here within a few seconds.",
                            style = Type.Small,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationChip(c: Cluster, max: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .width(150.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.SurfaceHi.copy(alpha = 0.95f), Palette.Surface.copy(alpha = 0.95f))))
            .border(1.dp, Palette.Stroke, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(flag(c.cc), fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(c.city.ifEmpty { c.country }, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(compact(c.lookups), style = Type.Number.copy(fontSize = 22.sp))
        Text(plural(c.apps, "app") + " · " + plural(c.domains, "domain"), style = Type.Small, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        MeterBar(c.lookups / max.coerceAtLeast(1).toFloat(), Modifier.fillMaxWidth())
    }
}

@Composable
private fun ClusterCard(c: Cluster, vm: MainViewModel, onClose: () -> Unit) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val detail = rememberQuery(ClusterDetail(), c.key, filter, tick) { vm.repo.clusterDetail(filter, c.key) }
    val blocked by vm.prefs.blockedFlow.collectAsStateWithLifecycle()
    val isBlocked = c.cc.isNotEmpty() && c.cc in blocked

    GlassCard(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        glow = Brush.radialGradient(listOf(Palette.Magenta.copy(alpha = 0.12f), Color.Transparent), radius = 900f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(flag(c.cc), fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.city.ifEmpty { c.country }, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${c.country} · ${detail.orgs.joinToString(", ") { it.org }.ifEmpty { "—" }}",
                    style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Palette.SurfaceTop).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Close", Modifier.size(16.dp), tint = Palette.TextMid) }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Metric(compact(c.lookups), "lookups", Palette.Magenta)
            Metric(c.domains.toString(), "domains", Palette.Cyan)
            Metric(c.apps.toString(), "apps", Palette.Violet)
            Spacer(Modifier.weight(1f))
            if (c.cc.isNotEmpty()) {
                Pill(
                    if (isBlocked) "Unblock" else "Block ${c.cc}",
                    active = isBlocked,
                    leading = { Icon(Icons.Rounded.Block, null, Modifier.size(14.dp), tint = if (isBlocked) Palette.Red else Palette.TextMid) },
                    onClick = { vm.prefs.setBlocked(c.cc, !isBlocked) },
                )
            }
        }
        if (isBlocked) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${c.country} is blocked: its servers are sinkholed and refused while capture runs.",
                style = Type.Small.copy(color = Palette.Red),
            )
        }
        if (detail.apps.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionHeader("Apps")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                detail.apps.forEach { a ->
                    Column(
                        Modifier.width(56.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.openApp(a.uid) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(a.uid, a.label, 40.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(a.label, style = Type.Small.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (detail.domains.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("Domains")
            Spacer(Modifier.height(4.dp))
            detail.domains.take(5).forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(d.domain, style = Type.Mono.copy(color = Palette.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(compact(d.count), style = Type.Mono)
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String, accent: Color) {
    Column {
        Text(value, style = Type.Number.copy(fontSize = 22.sp, color = accent))
        Text(label, style = Type.Small.copy(fontWeight = FontWeight.Medium))
    }
}
