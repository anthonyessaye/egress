package app.egress.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.egress.data.Cluster
import app.egress.data.Home
import app.egress.ui.theme.BodyFont
import app.egress.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Vector world map (Natural Earth country outlines) with glowing arcs from your current location to every
 * destination city. Countries you talk to are tinted by lookup count, and country names appear as you zoom.
 * Pinch to zoom, drag to pan, tap a destination to select it, double-tap to reset.
 *
 * Two canvases: the country layer only redraws on pan/zoom/data changes; the animated layer (arcs, pulses)
 * redraws every frame without re-rendering 30k points of coastline.
 */
@Composable
fun WorldMap(
    clusters: List<Cluster>,
    home: Home?,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    focus: Pair<Double, Double>? = null,
    blocked: Set<String> = emptySet(),
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val labelCache = remember { HashMap<String, TextLayoutResult>() }
    val shapes by produceState(CountryShapes.peek()) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { CountryShapes.load(context) }.getOrNull() }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var topLeft by remember { mutableStateOf(Offset.Unspecified) }
    val currentClusters by rememberUpdatedState(clusters)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentHome by rememberUpdatedState(home)

    // Pixels per degree at scale 1: the whole 360° fits the view width.
    val unit = if (viewSize.width > 0) viewSize.width / MapSpace.WIDTH else 0f
    val baseH = unit * MapSpace.HEIGHT

    fun project(lat: Double, lon: Double): Offset = Offset(MapSpace.x(lon) * unit, MapSpace.y(lat) * unit)

    fun clamp(tl: Offset, s: Float): Offset {
        val mw = viewSize.width * s
        val mh = baseH * s
        val x = tl.x.coerceIn(min(0f, viewSize.width - mw), 0f)
        val y = if (mh <= viewSize.height) (viewSize.height - mh) / 2f else tl.y.coerceIn(viewSize.height - mh, 0f)
        return Offset(x, y)
    }

    fun centeredOn(lat: Double, lon: Double, s: Float): Offset {
        val p = project(lat, lon)
        return clamp(Offset(viewSize.width / 2f - p.x * s, viewSize.height * 0.45f - p.y * s), s)
    }

    fun defaultScale(): Float = if (baseH <= 0f) 1f else (viewSize.height * 0.5f / baseH).coerceIn(1.6f, 3.2f)

    // Initial framing: centred on where you are.
    LaunchedEffect(viewSize, home != null) {
        if (viewSize.width == 0 || viewSize.height == 0) return@LaunchedEffect
        if (topLeft.isUnspecified || home != null) {
            val s = defaultScale()
            scale = s
            topLeft = centeredOn(home?.lat ?: 25.0, home?.lon ?: 10.0, s)
        }
    }

    // Glide to an externally requested focus (e.g. a destination card was tapped).
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        if (viewSize.width == 0 || topLeft.isUnspecified) return@LaunchedEffect
        val targetScale = max(scale, defaultScale() * 1.25f).coerceAtMost(8f)
        val from = topLeft
        val fromScale = scale
        val to = centeredOn(f.first, f.second, targetScale)
        animate(0f, 1f, animationSpec = tween(700, easing = FastOutSlowInEasing)) { t, _ ->
            scale = fromScale + (targetScale - fromScale) * t
            topLeft = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }
    }

    val infinite = rememberInfiniteTransition(label = "map")
    val phase by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "phase",
    )
    val pulse by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )

    // Lookups per destination country, for the tint. Selected cluster's country gets the selection colour.
    val countryWeight = remember(clusters) {
        val sums = HashMap<String, Int>()
        clusters.forEach { if (it.cc.isNotEmpty()) sums[it.cc] = (sums[it.cc] ?: 0) + it.lookups }
        val maxC = sums.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        sums.mapValues { sqrt(it.value / maxC.toFloat()) }
    }
    val selectedCc = clusters.firstOrNull { it.key == selectedKey }?.cc

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(viewSize) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (topLeft.isUnspecified) return@detectTransformGestures
                    val ns = (scale * zoom).coerceIn(1f, 14f)
                    val k = ns / scale
                    val tl = Offset(
                        centroid.x - (centroid.x - topLeft.x) * k + pan.x,
                        centroid.y - (centroid.y - topLeft.y) * k + pan.y,
                    )
                    scale = ns
                    topLeft = clamp(tl, ns)
                }
            }
            .pointerInput(viewSize) {
                detectTapGestures(
                    onDoubleTap = {
                        val s = defaultScale()
                        scale = s
                        topLeft = centeredOn(currentHome?.lat ?: 25.0, currentHome?.lon ?: 10.0, s)
                    },
                    onTap = { pos ->
                        if (topLeft.isUnspecified) return@detectTapGestures
                        val maxL = currentClusters.maxOfOrNull { it.lookups }?.coerceAtLeast(1) ?: 1
                        var best: Cluster? = null
                        var bestD = Float.MAX_VALUE
                        for (c in currentClusters.take(MAX_DRAWN)) {
                            val p = topLeft + project(c.lat, c.lon) * scale
                            val d = hypot(p.x - pos.x, p.y - pos.y)
                            val hit = with(density) { (20 + 10 * sqrt(c.lookups / maxL.toFloat())).dp.toPx() }
                            if (d < hit && d < bestD) { best = c; bestD = d }
                        }
                        currentOnSelect(best?.key)
                    },
                )
            },
    ) {
        // ---------------------------------------------------------------- static layer: countries
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF0B1424), Palette.Bg),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = max(size.width, size.height) * 0.75f,
                )
            )
            val sh = shapes
            if (unit == 0f || topLeft.isUnspecified) return@Canvas
            val tl = topLeft
            val u = unit * scale
            val px = density.density

            withTransform({
                translate(tl.x, tl.y)
                scale(u, u, pivot = Offset.Zero)
            }) {
                // Graticule every 30°, barely there.
                val grid = Palette.Stroke.copy(alpha = 0.05f)
                for (lon in 0..360 step 30) drawLine(grid, Offset(lon.toFloat(), 0f), Offset(lon.toFloat(), MapSpace.HEIGHT), strokeWidth = px / u)
                for (lat in -30..60 step 30) {
                    val y = MapSpace.y(lat.toDouble())
                    drawLine(grid, Offset(0f, y), Offset(MapSpace.WIDTH, y), strokeWidth = px / u)
                }
                if (sh == null) return@withTransform

                drawPath(sh.land, LandFill)
                for ((cc, w) in countryWeight) {
                    val tint = if (cc == selectedCc) Palette.Magenta else Palette.Cyan
                    val a = if (cc == selectedCc) 0.30f else 0.07f + 0.20f * w
                    sh.byCc[cc]?.forEach { drawPath(it.path, tint.copy(alpha = a)) }
                }
                for (cc in blocked) sh.byCc[cc]?.forEach { drawPath(it.path, Palette.Red.copy(alpha = 0.16f)) }
                drawPath(sh.land, Border, style = Stroke(width = 0.9f * px / u))
                for (cc in blocked) {
                    sh.byCc[cc]?.forEach { drawPath(it.path, Palette.Red.copy(alpha = 0.7f), style = Stroke(width = 1.1f * px / u)) }
                }
                for (cc in countryWeight.keys) {
                    val c = if (cc == selectedCc) Palette.Magenta.copy(alpha = 0.85f) else Palette.Cyan.copy(alpha = 0.45f)
                    sh.byCc[cc]?.forEach { drawPath(it.path, c, style = Stroke(width = 1.1f * px / u)) }
                }
            }

            if (sh != null) countryLabels(sh, measurer, labelCache, tl, u, px, countryWeight.keys, selectedCc, blocked)
        }

        // ---------------------------------------------------------------- animated layer: arcs, destinations, you
        Canvas(Modifier.fillMaxSize()) {
            if (unit == 0f || topLeft.isUnspecified) return@Canvas
            val tl = topLeft
            val s = scale
            val drawn = clusters.take(MAX_DRAWN)
            val maxL = drawn.maxOfOrNull { it.lookups }?.coerceAtLeast(1) ?: 1
            val homeP = home?.let { tl + project(it.lat, it.lon) * s }
            val px = density.density

            if (homeP != null) {
                for (c in drawn.asReversed()) {
                    val p = tl + project(c.lat, c.lon) * s
                    val d = hypot(p.x - homeP.x, p.y - homeP.y)
                    if (d < 6 * px) continue
                    val w = sqrt(c.lookups / maxL.toFloat())
                    val dim = selectedKey != null && c.key != selectedKey
                    val a = if (dim) 0.10f else 0.35f + 0.55f * w
                    val ctrl = arcControl(homeP, p)
                    val path = Path().apply {
                        moveTo(homeP.x, homeP.y)
                        quadraticTo(ctrl.x, ctrl.y, p.x, p.y)
                    }
                    val tint = when {
                        c.key == selectedKey -> Palette.Magenta
                        c.cc in blocked -> Palette.Red
                        else -> Palette.Cyan
                    }
                    drawPath(
                        path,
                        Brush.linearGradient(listOf(Palette.Violet.copy(alpha = a * 0.8f), tint.copy(alpha = a)), homeP, p),
                        style = Stroke(width = (0.8f + 2.2f * w) * px, cap = StrokeCap.Round),
                    )
                    if (!dim) {
                        val t = (phase + (c.key.hashCode() and 0xFFFF) / 65535f) % 1f
                        val q = quad(homeP, ctrl, p, t)
                        val fade = (1f - abs(t - 0.5f) * 2f).coerceIn(0f, 1f)
                        drawCircle(tint.copy(alpha = 0.25f * fade), radius = 5f * px, center = q)
                        drawCircle(Color.White.copy(alpha = 0.9f * fade), radius = 1.6f * px, center = q)
                    }
                }
            }

            for (c in drawn.asReversed()) {
                val p = tl + project(c.lat, c.lon) * s
                if (p.x < -40 || p.y < -40 || p.x > size.width + 40 || p.y > size.height + 40) continue
                val w = sqrt(c.lookups / maxL.toFloat())
                val r = (3f + 8f * w) * px
                val selected = c.key == selectedKey
                val dim = selectedKey != null && !selected
                val tint = when {
                    selected -> Palette.Magenta
                    c.cc in blocked -> Palette.Red
                    else -> Palette.Cyan
                }
                glowDot(p, r, tint, if (dim) 0.35f else 1f)
                if (selected) {
                    drawCircle(tint.copy(alpha = (1f - pulse) * 0.8f), radius = r + 18f * px * pulse, center = p, style = Stroke(1.5f * px))
                    cityLabel(measurer, c.city.ifEmpty { c.country }, p, r, px)
                }
            }

            if (homeP != null) {
                drawCircle(Palette.Violet.copy(alpha = (1f - pulse) * 0.7f), radius = 6f * px + 26f * px * pulse, center = homeP, style = Stroke(1.5f * px))
                glowDot(homeP, 6f * px, Palette.Violet, 1f)
                drawCircle(Color.White, radius = 2.2f * px, center = homeP)
            }
        }
    }
}

private const val MAX_DRAWN = 90
private val LandFill = Color(0xFF121A2A)
private val Border = Color(0xFF3CF0FF).copy(alpha = 0.16f)

private val CountryLabel = TextStyle(
    fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.1.sp,
    color = Palette.TextLo,
)
private val CountryLabelHot = CountryLabel.copy(color = Palette.Text, fontSize = 10.sp)

/**
 * Country names, placed greedily: countries you talk to first, then bigger countries. A name is drawn only
 * if it fits inside the country at the current zoom and doesn't collide with one already placed.
 */
private fun DrawScope.countryLabels(
    shapes: CountryShapes,
    measurer: TextMeasurer,
    cache: HashMap<String, TextLayoutResult>,
    tl: Offset,
    u: Float,
    px: Float,
    hot: Set<String>,
    selectedCc: String?,
    blocked: Set<String>,
) {
    val placed = ArrayList<Rect>(64)
    val ordered = shapes.countries.sortedWith(
        compareByDescending<CountryShape> { it.cc in hot }.thenBy { it.rank }.thenByDescending { it.area },
    )
    val pad = 4f * px
    for (c in ordered) {
        val isHot = c.cc in hot
        val onScreenW = c.mainWidth * u
        // Cheap pre-check before measuring: very small on screen, and not a country we care about.
        if (!isHot && onScreenW < 28f * px) continue
        val cx = tl.x + c.labelX * u
        val cy = tl.y + c.labelY * u
        if (cx < -100 * px || cx > size.width + 100 * px || cy < -20 * px || cy > size.height + 20 * px) continue
        val key = (if (isHot) "h|" else "n|") + c.name
        val layout = cache.getOrPut(key) { measurer.measure(c.name.uppercase(), if (isHot) CountryLabelHot else CountryLabel) }
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        if (!isHot && onScreenW < w * 0.9f) continue
        if (isHot && onScreenW < w * 0.35f) continue
        val r = Rect(cx - w / 2 - pad, cy - h / 2 - pad / 2, cx + w / 2 + pad, cy + h / 2 + pad / 2)
        if (placed.any { it.overlaps(r) }) continue
        placed += r
        val color = when {
            c.cc == selectedCc -> Palette.Magenta
            c.cc in blocked -> Palette.Red
            isHot -> Palette.Text.copy(alpha = 0.9f)
            else -> Palette.TextMid.copy(alpha = 0.75f)
        }
        drawText(layout, color = color, topLeft = Offset(cx - w / 2, cy - h / 2))
    }
}

private fun DrawScope.glowDot(c: Offset, r: Float, tint: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(tint.copy(alpha = 0.45f * alpha), Color.Transparent), center = c, radius = r * 2.8f),
        radius = r * 2.8f, center = c,
    )
    drawCircle(tint.copy(alpha = 0.18f * alpha), radius = r, center = c)
    drawCircle(tint.copy(alpha = 0.9f * alpha), radius = r, center = c, style = Stroke(1.2f * density))
    drawCircle(tint.copy(alpha = alpha), radius = max(1.8f * density, r * 0.38f), center = c)
}

private fun DrawScope.cityLabel(measurer: TextMeasurer, text: String, p: Offset, r: Float, px: Float) {
    val layout = measurer.measure(
        text,
        TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.Text),
    )
    val padH = 8f * px
    val padV = 4f * px
    val w = layout.size.width + padH * 2
    val h = layout.size.height + padV * 2
    val x = (p.x - w / 2).coerceIn(4f * px, size.width - w - 4f * px)
    val y = p.y - r - h - 10f * px
    drawRoundRect(Palette.Bg.copy(alpha = 0.85f), topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(h / 2))
    drawRoundRect(
        Palette.Magenta.copy(alpha = 0.6f), topLeft = Offset(x, y), size = Size(w, h),
        cornerRadius = CornerRadius(h / 2), style = Stroke(1f * px),
    )
    drawText(layout, topLeft = Offset(x + padH, y + padV))
}

/** Control point that bows the arc "upward" (toward the pole side), proportional to distance. */
private fun arcControl(a: Offset, b: Offset): Offset {
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    var nx = -dy / len
    var ny = dx / len
    if (ny > 0) { nx = -nx; ny = -ny }
    val h = len * 0.28f
    return Offset(mid.x + nx * h, mid.y + ny * h)
}

private fun quad(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val u = 1 - t
    return Offset(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y)
}
