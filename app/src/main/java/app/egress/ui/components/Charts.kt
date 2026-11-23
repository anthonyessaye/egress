package app.egress.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.egress.data.Histogram
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lookups-over-time bars with a gradient fill and a soft peak marker. */
@Composable
fun ActivityChart(h: Histogram, modifier: Modifier = Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900)) }
    val maxV = h.buckets.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val peakIdx = h.buckets.indices.maxByOrNull { h.buckets[it].count } ?: -1

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = h.buckets.size
            if (n == 0) return@Canvas
            val gap = 3.dp.toPx()
            val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            // baseline grid
            for (g in 1..3) {
                val y = size.height * g / 4f
                drawLine(Palette.Stroke, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            h.buckets.forEachIndexed { i, b ->
                val x = i * (bw + gap)
                val frac = b.count / maxV.toFloat() * grow.value
                val bh = (size.height * frac).coerceAtLeast(if (b.count > 0) 3.dp.toPx() else 1.dp.toPx())
                val top = size.height - bh
                val brush = if (b.count == 0) Brush.verticalGradient(listOf(Palette.SurfaceTop, Palette.SurfaceTop))
                else Brush.verticalGradient(listOf(Palette.Cyan, Palette.Violet.copy(alpha = 0.55f)), startY = top, endY = size.height)
                drawRoundRect(brush, Offset(x, top), Size(bw, bh), CornerRadius(bw / 2.5f, bw / 2.5f))
                if (i == peakIdx && b.count > 0) {
                    drawCircle(Palette.Cyan.copy(alpha = 0.25f), radius = bw * 1.4f, center = Offset(x + bw / 2, top))
                    drawCircle(Palette.Cyan, radius = bw * 0.9f, center = Offset(x + bw / 2, top), style = Stroke(1.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (h.buckets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(axisLabel(h.buckets.first().start, h.bucketMs), style = Type.Mono.copy(color = Palette.TextLo), modifier = Modifier.weight(1f))
                Text(axisLabel(h.buckets[h.buckets.size / 2].start, h.bucketMs), style = Type.Mono.copy(color = Palette.TextLo), modifier = Modifier.weight(1f))
                Text("now", style = Type.Mono.copy(color = Palette.TextLo))
            }
        }
    }
}

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dm = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault())
private fun axisLabel(ts: Long, bucketMs: Long): String =
    if (bucketMs >= 6 * 3_600_000L) dm.format(Instant.ofEpochMilli(ts)) else hm.format(Instant.ofEpochMilli(ts))
