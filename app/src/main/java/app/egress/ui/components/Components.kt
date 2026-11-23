package app.egress.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.egress.EgressApp
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.annotation.SuppressLint
import java.time.Instant
import java.util.Locale
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ------------------------------------------------------------------ data helpers

/** Runs [block] on IO whenever a key changes; keeps showing the previous value meanwhile (no flicker). */
// Lint's ProduceStateDoesNotAssignValue misfires on `value = withContext(...)`; the lambdas below do assign.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun <T> rememberQuery(initial: T, vararg keys: Any?, block: () -> T): T {
    val latest by rememberUpdatedState(block)
    val state: State<T> = produceState(initial, *keys) {
        value = withContext(Dispatchers.IO) { runCatching { latest() }.getOrElse { value } }
    }
    return state.value
}

fun flag(cc: String?): String {
    if (cc == null || cc.length != 2 || !cc.all { it in 'A'..'Z' || it in 'a'..'z' }) return "🌐"
    val u = cc.uppercase()
    return String(Character.toChars(0x1F1E6 + (u[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (u[1] - 'A')))
}

/** "1 app", "3 apps", "2.1k lookups". */
fun plural(n: Int, one: String, many: String = one + "s"): String = "${compact(n)} ${if (n == 1) one else many}"

fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000f)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1000f)
    else -> n.toString()
}

fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - ts) / 1000
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val dayClockFmt = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())
fun clock(ts: Long): String = clockFmt.format(Instant.ofEpochMilli(ts))
fun dayClock(ts: Long): String = dayClockFmt.format(Instant.ofEpochMilli(ts))

// ------------------------------------------------------------------ surfaces

private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    glow: Brush? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .clip(CardShape)
        .background(Brush.verticalGradient(listOf(Palette.SurfaceHi, Palette.Surface)))
        .let { if (glow != null) it.background(glow) else it }
        .border(1.dp, Brush.verticalGradient(listOf(Palette.StrokeHi, Palette.Stroke)), CardShape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(padding)
    Column(base, content = content)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = Type.Label, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = Type.Small.copy(color = Palette.Cyan, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    GlassCard(modifier, onClick = onClick, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(accent, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), style = Type.Label, modifier = Modifier.weight(1f))
            if (onClick != null) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = Palette.TextLo)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(value, style = Type.Number)
    }
}

/** Thin gradient bar, [fraction] in 0..1. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, brush: Brush = Palette.Accent) {
    Canvas(modifier.height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.SurfaceTop, cornerRadius = r)
        val w = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(size.height)
        drawRoundRect(brush, size = Size(w, size.height), cornerRadius = r)
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (active) Palette.AccentSoft else Brush.linearGradient(listOf(Palette.SurfaceHi, Palette.SurfaceHi)))
            .border(1.dp, if (active) Palette.Cyan.copy(alpha = 0.55f) else Palette.Stroke, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text,
            style = Type.Small.copy(color = if (active) Palette.Text else Palette.TextMid, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ app icon

private val avatarColors = listOf(Palette.Cyan, Palette.Violet, Palette.Magenta, Palette.Lime, Palette.Amber)

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun AppIcon(uid: Int, label: String, size: Dp = 36.dp) {
    val resolver = remember { EgressApp.get().apps }
    val icon by produceState<ImageBitmap?>(null, uid) {
        value = withContext(Dispatchers.IO) { resolver.icon(uid) }
    }
    val shape = RoundedCornerShape(size * 0.3f)
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = label, modifier = Modifier.size(size).clip(shape))
    } else {
        val c = avatarColors[abs(uid) % avatarColors.size]
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(c.copy(alpha = 0.35f), c.copy(alpha = 0.08f))))
                .border(1.dp, c.copy(alpha = 0.4f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = Type.Body.copy(fontSize = (size.value * 0.42f).sp, color = c, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.Body)
        Spacer(Modifier.height(4.dp))
        Text(body, style = Type.Small)
    }
}

/** Modifier.clickable without the ripple, for large hit areas. */
@Composable
fun Modifier.quietClick(onClick: () -> Unit): Modifier =
    this.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun BoxScope.TopFade(height: Dp = 140.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg.copy(alpha = 0.85f), Color.Transparent)))
    )
}

@Composable
fun BoxScope.BottomFade(height: Dp = 180.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Bg.copy(alpha = 0.9f), Palette.Bg)))
    )
}
