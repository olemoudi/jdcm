package dev.jdcm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jdcm.data.ThemeMode
import dev.jdcm.notify.Notifier

/**
 * The look the friend asked for: Marca's results screen. Near-black surfaces separated by thin
 * lines rather than shadows, one warm orange for "selected / now", a soft blue for links, and
 * scores in big tabular figures. Light mode keeps the same structure for daylight at the pitch.
 */
@Immutable
data class Extra(
    val link: Color,
    val win: Color,
    val draw: Color,
    val loss: Color,
    /** Changed by the organisation (time, field): the site's own orange. */
    val modified: Color,
    /** Postponed, taken off the calendar. */
    val danger: Color,
    val line: Color,
    val muted: Color,
    val highlight: Color,
    val dark: Boolean,
)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color.Blue, Color.Green, Color.Gray, Color.Red, Color(0xFFFF9B00), Color.Red, Color.Gray, Color.Gray, Color.LightGray, true)
}

private val Orange = Color(0xFFF2643B)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1F16),
    onPrimaryContainer = Color(0xFFFFB59C),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF0B1B33),
    secondaryContainer = Color(0xFF2E3036),
    onSecondaryContainer = Color(0xFFEDEDEE),
    background = Color(0xFF111214),
    onBackground = Color(0xFFEDEDEE),
    surface = Color(0xFF111214),
    onSurface = Color(0xFFEDEDEE),
    surfaceVariant = Color(0xFF1C1D20),
    onSurfaceVariant = Color(0xFFA7AAB0),
    surfaceContainerLowest = Color(0xFF0C0D0E),
    surfaceContainerLow = Color(0xFF16171A),
    surfaceContainer = Color(0xFF1A1B1E),
    surfaceContainerHigh = Color(0xFF212226),
    surfaceContainerHighest = Color(0xFF2A2B30),
    outline = Color(0xFF3A3B40),
    outlineVariant = Color(0xFF2B2C30),
    error = Color(0xFFFF6B6B),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFD9481C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D8),
    onPrimaryContainer = Color(0xFF5A1A05),
    secondary = Color(0xFF1A63C9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E5E9),
    onSecondaryContainer = Color(0xFF17181A),
    background = Color(0xFFF4F4F5),
    onBackground = Color(0xFF17181A),
    surface = Color(0xFFF4F4F5),
    onSurface = Color(0xFF17181A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5D6066),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F0F2),
    surfaceContainerHighest = Color(0xFFE6E6E9),
    outline = Color(0xFFCFD0D4),
    outlineVariant = Color(0xFFE2E3E6),
    error = Color(0xFFC62828),
)

private val DarkExtra = Extra(
    link = Color(0xFF8AB4F8), win = Color(0xFF5BC27A), draw = Color(0xFFA7AAB0), loss = Color(0xFFFF6B6B),
    modified = Color(0xFFFFA43A), danger = Color(0xFFFF6B6B), line = Color(0xFF2E2F34), muted = Color(0xFFA7AAB0),
    highlight = Color(0xFF1F2024), dark = true,
)

private val LightExtra = Extra(
    link = Color(0xFF1A63C9), win = Color(0xFF1E8E45), draw = Color(0xFF6B6E74), loss = Color(0xFFC62828),
    modified = Color(0xFFB85C00), danger = Color(0xFFC62828), line = Color(0xFFE0E1E4), muted = Color(0xFF5D6066),
    highlight = Color(0xFFF7F0EC), dark = false,
)

/** Scores and clock times: tabular figures so columns line up. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

fun kidColor(index: Int): Color = Color(Notifier.KID_COLORS[index.mod(Notifier.KID_COLORS.size)])

@Composable
fun JdcmTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, content = content)
    }
}

object JdcmColors {
    val extra: Extra @Composable get() = LocalExtra.current
}
