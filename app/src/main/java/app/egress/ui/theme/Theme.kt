package app.egress.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.egress.R

object Palette {
    val Bg = Color(0xFF06080D)
    val Surface = Color(0xFF0B0F17)
    val SurfaceHi = Color(0xFF111726)
    val SurfaceTop = Color(0xFF182033)
    val Stroke = Color(0x17FFFFFF)
    val StrokeHi = Color(0x2EFFFFFF)
    val Text = Color(0xFFEAF0FF)
    val TextMid = Color(0xFF8B95AB)
    val TextLo = Color(0xFF586278)
    val Cyan = Color(0xFF3CF0FF)
    val Violet = Color(0xFF8A5CFF)
    val Magenta = Color(0xFFFF4FD8)
    val Lime = Color(0xFFB6FF5C)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5C7A)
    val Land = Color(0xFF1B2436)

    val Accent = Brush.linearGradient(listOf(Violet, Cyan))
    val AccentSoft = Brush.linearGradient(listOf(Violet.copy(alpha = 0.22f), Cyan.copy(alpha = 0.22f)))
}

val DisplayFont = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
val BodyFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)
val MonoFont = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Medium))

object Type {
    val Hero = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.8).sp, color = Palette.Text)
    val Title = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp, color = Palette.Text)
    val Number = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.6).sp, color = Palette.Text)
    val Body = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Palette.Text)
    val BodyMid = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = Palette.TextMid)
    val Small = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = Palette.TextMid)
    val Label = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.3.sp, color = Palette.TextLo)
    val Mono = TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Palette.TextMid)
}

@Composable
fun EgressTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Cyan,
        onPrimary = Palette.Bg,
        secondary = Palette.Violet,
        tertiary = Palette.Magenta,
        background = Palette.Bg,
        onBackground = Palette.Text,
        surface = Palette.SurfaceHi,
        onSurface = Palette.Text,
        surfaceVariant = Palette.SurfaceTop,
        onSurfaceVariant = Palette.TextMid,
        outline = Palette.StrokeHi,
        error = Palette.Red,
    )
    val base = Typography()
    val typography = Typography(
        titleLarge = base.titleLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = BodyFont),
        bodyLarge = base.bodyLarge.copy(fontFamily = BodyFont),
        bodyMedium = base.bodyMedium.copy(fontFamily = BodyFont),
        bodySmall = base.bodySmall.copy(fontFamily = BodyFont),
        labelLarge = base.labelLarge.copy(fontFamily = BodyFont),
        labelMedium = base.labelMedium.copy(fontFamily = BodyFont),
        labelSmall = base.labelSmall.copy(fontFamily = BodyFont),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
