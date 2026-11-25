// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rescuecodec.app.R

/**
 * Design world: the same map sheet as the command-post dashboard, in two lights.
 * Day is paper, night is the same sheet under a red-filter lamp. Neutrals lean to forest green.
 * Colour has jobs: red is Mayday, amber is open, blue is accepted, green is done.
 * The primary colour is plain ink, so the four state colours are the only colour on screen.
 */
@Immutable
class RcColors(
    val mayday: Color, val onMayday: Color, val maydaySoft: Color, val maydayText: Color,
    val open: Color, val openSoft: Color, val openSolid: Color,
    val accepted: Color, val acceptedSoft: Color, val acceptSolid: Color,
    val done: Color, val doneSoft: Color, val doneSolid: Color,
    val declined: Color, val declinedSoft: Color,
    val measured: Color, val estimated: Color,
    val ink3: Color, val surface3: Color, val line: Color,
    val onSolid: Color,
    /** My own chat bubble and its text. */
    val mine: Color, val onMine: Color,
    /** The chat ground behind the bubbles. */
    val chatGround: Color,
    /** Sender name colours: never red, amber, green or blue, so they cannot be read as a state. */
    val senders: List<Color>,
    /** The grey ring around the mic and the outline of chips. */
    val ring: Color,
    /** Muted text: times, small notes. */
    val muted: Color,
    val dark: Boolean,
)

private val LightRc = RcColors(
    mayday = Color(0xFFC40D19), onMayday = Color(0xFFFFFFFF), maydaySoft = Color(0xFFFBE2E2), maydayText = Color(0xFFA30A14),
    open = Color(0xFF9A4F00), openSoft = Color(0xFFFBE8C9), openSolid = Color(0xFFE08A14),
    accepted = Color(0xFF14578C), acceptedSoft = Color(0xFFD8E9F6), acceptSolid = Color(0xFF14578C),
    done = Color(0xFF23653A), doneSoft = Color(0xFFD9ECDC), doneSolid = Color(0xFF23653A),
    declined = Color(0xFF5B5F62), declinedSoft = Color(0xFFE4E5E3),
    measured = Color(0xFF23653A), estimated = Color(0xFF8A4B00),
    ink3 = Color(0xFF3A4A40), surface3 = Color(0xFFDDE3D4), line = Color(0xFFC3CAB8),
    onSolid = Color(0xFFFFFFFF),
    mine = Color(0xFFDDE3D4), onMine = Color(0xFF17231C),
    chatGround = Color(0xFFE6EADF),
    senders = listOf(Color(0xFF17231C)),
    ring = Color(0xFF7E8C82), muted = Color(0xFF5E6E63),
    dark = false,
)

private val DarkRc = RcColors(
    mayday = Color(0xFFE5202C), onMayday = Color(0xFFFFFFFF), maydaySoft = Color(0xFF3A1417), maydayText = Color(0xFFFF8A91),
    open = Color(0xFFF2AD4E), openSoft = Color(0xFF3A2A10), openSolid = Color(0xFFF2AD4E),
    accepted = Color(0xFF6CB9F2), acceptedSoft = Color(0xFF12293B), acceptSolid = Color(0xFF2D7FC2),
    done = Color(0xFF77CF90), doneSoft = Color(0xFF12301D), doneSolid = Color(0xFF2F8A4C),
    declined = Color(0xFFA3ADB0), declinedSoft = Color(0xFF20282A),
    measured = Color(0xFF77CF90), estimated = Color(0xFFF2AD4E),
    ink3 = Color(0xFFC9D4CC), surface3 = Color(0xFF21322A), line = Color(0xFF2A3C33),
    onSolid = Color(0xFFFFFFFF),
    mine = Color(0xFF21322A), onMine = Color(0xFFE5EDE6),
    chatGround = Color(0xFF0B1310),
    senders = listOf(Color(0xFFE5EDE6)),
    ring = Color(0xFF7E8C82), muted = Color(0xFF9DB0A4),
    dark = true,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF17231C), onPrimary = Color(0xFFF6F7F1),
    primaryContainer = Color(0xFFDDE3D4), onPrimaryContainer = Color(0xFF17231C),
    secondary = Color(0xFF435349), onSecondary = Color(0xFFF6F7F1),
    secondaryContainer = Color(0xFFDDE3D4), onSecondaryContainer = Color(0xFF17231C),
    tertiary = Color(0xFF14578C), onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFC40D19), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE2E2), onErrorContainer = Color(0xFF7A0710),
    background = Color(0xFFE6EADF), onBackground = Color(0xFF17231C),
    surface = Color(0xFFF6F7F1), onSurface = Color(0xFF17231C),
    surfaceVariant = Color(0xFFEAEEE3), onSurfaceVariant = Color(0xFF3A4A40),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F7F1),
    surfaceContainer = Color(0xFFEFF2E9), surfaceContainerHigh = Color(0xFFEAEEE3), surfaceContainerHighest = Color(0xFFDDE3D4),
    outline = Color(0xFF6B7B70), outlineVariant = Color(0xFFC3CAB8),
    inverseSurface = Color(0xFF17231C), inverseOnSurface = Color(0xFFF6F7F1), inversePrimary = Color(0xFFE5EDE6),
    scrim = Color(0x8017231C),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE5EDE6), onPrimary = Color(0xFF0B1310),
    primaryContainer = Color(0xFF21322A), onPrimaryContainer = Color(0xFFE5EDE6),
    secondary = Color(0xFFAEBDB3), onSecondary = Color(0xFF0B1310),
    secondaryContainer = Color(0xFF21322A), onSecondaryContainer = Color(0xFFE5EDE6),
    tertiary = Color(0xFF6CB9F2), onTertiary = Color(0xFF0B1310),
    error = Color(0xFFE5202C), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A1417), onErrorContainer = Color(0xFFFFB3B8),
    background = Color(0xFF0B1310), onBackground = Color(0xFFE5EDE6),
    surface = Color(0xFF121C17), onSurface = Color(0xFFE5EDE6),
    surfaceVariant = Color(0xFF18251F), onSurfaceVariant = Color(0xFFC9D4CC),
    surfaceContainerLowest = Color(0xFF0B1310), surfaceContainerLow = Color(0xFF121C17),
    surfaceContainer = Color(0xFF15211B), surfaceContainerHigh = Color(0xFF18251F), surfaceContainerHighest = Color(0xFF21322A),
    outline = Color(0xFF8FA196), outlineVariant = Color(0xFF2A3C33),
    inverseSurface = Color(0xFFE5EDE6), inverseOnSurface = Color(0xFF0B1310), inversePrimary = Color(0xFF17231C),
    scrim = Color(0x99000000),
)

val LocalRc = staticCompositionLocalOf { LightRc }

/** The state colours and measured/estimated colours that Material roles do not cover. */
object Rc {
    val colors: RcColors @Composable @ReadOnlyComposable get() = LocalRc.current
}

private val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

/** Barlow Condensed: big numbers and headings. */
val BarlowCondensed = FontFamily(
    Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlowcondensed_bold, FontWeight.Bold),
)

private fun t(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/** Material type scale in Barlow. Nothing below 13 sp: it must read in sunlight. */
private val RcTypography = Typography(
    displayLarge = t(BarlowCondensed, FontWeight.Bold, 56, 56),
    displayMedium = t(BarlowCondensed, FontWeight.Bold, 44, 46),
    displaySmall = t(BarlowCondensed, FontWeight.Bold, 36, 38),
    headlineLarge = t(BarlowCondensed, FontWeight.Bold, 32, 36),
    headlineMedium = t(BarlowCondensed, FontWeight.Bold, 28, 32),
    headlineSmall = t(BarlowCondensed, FontWeight.Bold, 24, 28),
    titleLarge = t(BarlowCondensed, FontWeight.Bold, 22, 26),
    titleMedium = t(Barlow, FontWeight.SemiBold, 18, 24, 0.1),
    titleSmall = t(Barlow, FontWeight.SemiBold, 16, 22, 0.1),
    bodyLarge = t(Barlow, FontWeight.Normal, 18, 26),
    bodyMedium = t(Barlow, FontWeight.Normal, 16, 23),
    bodySmall = t(Barlow, FontWeight.Normal, 14, 20),
    labelLarge = t(Barlow, FontWeight.SemiBold, 16, 20, 0.2),
    labelMedium = t(Barlow, FontWeight.SemiBold, 14, 18, 0.3),
    labelSmall = t(Barlow, FontWeight.SemiBold, 12, 16, 0.3),
)

/** Tight, plain corners, like the dashboard: 6 to 12 dp. */
private val RcShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Day, night, or follow the phone. For now the app always uses [DAY] (see RescueRoot); the night code stays. */
enum class ThemeMode { AUTO, DAY, NIGHT;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: AUTO }
}

@Composable
fun RescueTheme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    CompositionLocalProvider(LocalRc provides if (dark) DarkRc else LightRc) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = RcTypography,
            shapes = RcShapes,
            content = content,
        )
    }
}
