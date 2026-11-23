package app.egress.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.egress.data.FeedItem
import app.egress.data.Histogram
import app.egress.data.Stats
import app.egress.data.CountryCount
import app.egress.ui.MainViewModel
import app.egress.ui.Tab
import app.egress.ui.components.ActivityChart
import app.egress.ui.components.AppIcon
import app.egress.ui.components.EmptyHint
import app.egress.ui.components.FilterBar
import app.egress.ui.components.GlassCard
import app.egress.ui.components.LinkText
import app.egress.ui.components.MeterBar
import app.egress.ui.components.SectionHeader
import app.egress.ui.components.StatTile
import app.egress.ui.components.ago
import app.egress.ui.components.compact
import app.egress.ui.components.flag
import app.egress.ui.components.rememberQuery
import app.egress.ui.rememberCaptureToggle
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type
import app.egress.vpn.BlockStatus
import app.egress.vpn.CaptureState

@Composable
fun OverviewScreen(vm: MainViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val running by CaptureState.running.collectAsStateWithLifecycle()
    val strict by CaptureState.privateDnsStrict.collectAsStateWithLifecycle()
    val error by CaptureState.lastError.collectAsStateWithLifecycle()
    val block by CaptureState.block.collectAsStateWithLifecycle()
    val refused = CaptureState.blockedHits.collectAsStateWithLifecycle().value.values.sum()
    val toggle = rememberCaptureToggle()
    var settings by remember { mutableStateOf(false) }
    var breakdown by remember { mutableStateOf<Breakdown?>(null) }

    val stats = rememberQuery(Stats(), filter, tick) { vm.repo.stats(filter) }
    val hist = rememberQuery(Histogram(), filter, tick) { vm.repo.histogram(filter) }
    val countries = rememberQuery(emptyList<CountryCount>(), filter, tick) { vm.repo.topCountries(filter, 6) }
    val feed = rememberQuery(emptyList<FeedItem>(), filter, tick) { vm.repo.feed(filter, "", 8) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Egress", style = Type.Hero)
                    Text("Where your phone talks to", style = Type.Small)
                }
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Palette.SurfaceHi)
                        .border(1.dp, Palette.Stroke, CircleShape)
                        .clickable { settings = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Tune, "Settings", tint = Palette.TextMid, modifier = Modifier.size(20.dp)) }
            }
        }
        item { CaptureCard(running, block, refused, toggle, Modifier.padding(horizontal = 20.dp)) }
        if (strict || error != null) item {
            WarningCard(
                if (strict) "Private DNS is set to a specific provider. Apps will resolve names over encrypted DNS and " +
                    "bypass Egress. Set Settings → Network → Private DNS to Automatic or Off."
                else error.orEmpty(),
                Modifier.padding(horizontal = 20.dp),
            )
        }
        item { FilterBar(filter, tick, vm::setFilter) }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Lookups", compact(stats.lookups), Palette.Cyan, Modifier.weight(1f)) { vm.searchLog("") }
                StatTile("Apps", compact(stats.apps), Palette.Violet, Modifier.weight(1f)) { breakdown = Breakdown.Apps }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Domains", compact(stats.domains), Palette.Magenta, Modifier.weight(1f)) { breakdown = Breakdown.Domains }
                StatTile("Countries", compact(stats.countries), Palette.Lime, Modifier.weight(1f)) { breakdown = Breakdown.Countries }
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                SectionHeader("Activity")
                Spacer(Modifier.height(14.dp))
                if (stats.lookups == 0) EmptyHint("Quiet so far", "Lookups will stack up here as apps go online.")
                else ActivityChart(hist)
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                SectionHeader("Top destinations") { LinkText("Map") { vm.tab.value = Tab.Map } }
                Spacer(Modifier.height(8.dp))
                if (countries.isEmpty()) EmptyHint("No locations yet", "Destinations appear once IPs are geolocated.")
                val max = countries.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                countries.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.showCountryOnMap(c.cc) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(flag(c.cc), fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(c.country, style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(compact(c.count), style = Type.Mono.copy(color = Palette.Text))
                            }
                            Spacer(Modifier.height(6.dp))
                            MeterBar(c.count / max.toFloat(), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                SectionHeader("Live") { LinkText("Full log") { vm.tab.value = Tab.Activity } }
                Spacer(Modifier.height(6.dp))
                if (feed.isEmpty()) EmptyHint("Nothing yet", if (running) "Open any app to see it appear." else "Start capture above.")
                feed.forEach { FeedRowCompact(it) { vm.openApp(it.uid) } }
            }
        }
    }

    if (settings) SettingsSheet(vm) { settings = false }
    breakdown?.let { BreakdownSheet(it, vm, filter, tick) { breakdown = null } }
}

@Composable
private fun CaptureCard(running: Boolean, block: BlockStatus, refused: Int, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val glow = if (running) Brush.radialGradient(
        listOf(Palette.Cyan.copy(alpha = 0.16f), Color.Transparent),
        center = Offset(120f, 120f), radius = 700f,
    ) else null
    GlassCard(modifier.fillMaxWidth(), onClick = onToggle, glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CaptureOrb(running)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (running) "Capturing" else "Paused", style = Type.Title)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (running) "Watching every app's outbound lookups"
                    else "Tap to start mapping where your traffic goes",
                    style = Type.Small,
                )
                if (running && block.countries.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Blocking " + block.countries.sorted().joinToString(" ") { flag(it) } +
                            if (refused > 0) "  ·  ${compact(refused)} refused" else "",
                        style = Type.Small.copy(color = Palette.Red),
                    )
                }
            }
            Switchy(running)
        }
    }
}

@Composable
private fun CaptureOrb(running: Boolean) {
    val t = rememberInfiniteTransition(label = "orb")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p")
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) {
            val c = center
            if (running) {
                for (k in 0..1) {
                    val q = (p + k * 0.5f) % 1f
                    drawCircle(Palette.Cyan.copy(alpha = (1f - q) * 0.6f), radius = size.minDimension * (0.25f + 0.25f * q), center = c, style = Stroke(1.5.dp.toPx()))
                }
                drawCircle(Brush.linearGradient(listOf(Palette.Violet, Palette.Cyan)), radius = size.minDimension * 0.26f, center = c)
            } else {
                drawCircle(Palette.SurfaceTop, radius = size.minDimension * 0.30f, center = c)
                drawCircle(Palette.StrokeHi, radius = size.minDimension * 0.30f, center = c, style = Stroke(1.dp.toPx()))
            }
        }
        Icon(
            Icons.Rounded.PowerSettingsNew, null, Modifier.size(20.dp),
            tint = if (running) Palette.Bg else Palette.TextMid,
        )
    }
}

@Composable
private fun Switchy(on: Boolean) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(shape)
            .background(if (on) Palette.Accent else Brush.linearGradient(listOf(Palette.SurfaceTop, Palette.SurfaceTop)))
            .border(1.dp, if (on) Color.Transparent else Palette.StrokeHi, shape)
            .padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Palette.Bg else Palette.TextLo))
    }
}

@Composable
private fun WarningCard(text: String, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), glow = Brush.linearGradient(listOf(Palette.Amber.copy(alpha = 0.10f), Color.Transparent))) {
        Row {
            Icon(Icons.Rounded.WarningAmber, null, tint = Palette.Amber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = Type.Small.copy(color = Palette.Text))
        }
    }
}

@Composable
fun FeedRowCompact(item: FeedItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(item.uid, item.label, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.domain, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(item.label)
                    if (item.cc != null) append("  ·  ").append(flag(item.cc)).append(' ').append(item.city ?: item.cc)
                },
                style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(ago(item.ts), style = Type.Mono.copy(color = Palette.TextLo, fontWeight = FontWeight.Medium))
    }
}
