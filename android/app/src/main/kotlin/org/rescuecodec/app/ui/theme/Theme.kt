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
 * Design world: an emergency tool, so plain neutral surfaces (white by day, near black by night, high contrast for
 * sunlight) and colour only for meaning, with the meanings everyone knows:
 * red is Mayday, SOS, urgent and errors; amber is open, someone must act; green is done, OK and connected;
 * blue is accepted and in progress, and the one accent (my own bubbles, primary buttons); grey is neutral info.
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
    val dark: Boolean,
)

private val LightRc = RcColors(
    mayday = Color(0xFFC5221F), onMayday = Color(0xFFFFFFFF), maydaySoft = Color(0xFFFCE8E6), maydayText = Color(0xFFA50E0E),
    open = Color(0xFF9C4A00), openSoft = Color(0xFFFEEFD6), openSolid = Color(0xFFF29900),
    accepted = Color(0xFF0B57D0), acceptedSoft = Color(0xFFE3ECFC), acceptSolid = Color(0xFF0B57D0),
    done = Color(0xFF137333), doneSoft = Color(0xFFE4F3E8), doneSolid = Color(0xFF137333),
    declined = Color(0xFF5F6368), declinedSoft = Color(0xFFEDEFF1),
    measured = Color(0xFF137333), estimated = Color(0xFF9C4A00),
    ink3 = Color(0xFF454B52), surface3 = Color(0xFFE9ECEF), line = Color(0xFFD5D9DE),
    onSolid = Color(0xFFFFFFFF),
    mine = Color(0xFFDCE8FC), onMine = Color(0xFF0E1726),
    chatGround = Color(0xFFF1F3F5),
    senders = listOf(Color(0xFF7B3FA0), Color(0xFF00737A), Color(0xFF8A5A2B), Color(0xFFB0306A), Color(0xFF4A5694), Color(0xFF5F6B12)),
    dark = false,
)

private val DarkRc = RcColors(
    mayday = Color(0xFFE5383F), onMayday = Color(0xFFFFFFFF), maydaySoft = Color(0xFF3D1517), maydayText = Color(0xFFFF8F94),
    open = Color(0xFFFFB74D), openSoft = Color(0xFF3A2A10), openSolid = Color(0xFFFFB74D),
    accepted = Color(0xFF8AB4F8), acceptedSoft = Color(0xFF16284A), acceptSolid = Color(0xFF2F6FDB),
    done = Color(0xFF6DD58C), doneSoft = Color(0xFF12301C), doneSolid = Color(0xFF2B8A4B),
    declined = Color(0xFFA9AFB6), declinedSoft = Color(0xFF25282C),
    measured = Color(0xFF6DD58C), estimated = Color(0xFFFFB74D),
    ink3 = Color(0xFFC4C9CF), surface3 = Color(0xFF2A2E33), line = Color(0xFF33373D),
    onSolid = Color(0xFFFFFFFF),
    mine = Color(0xFF1E3A66), onMine = Color(0xFFEEF2F8),
    chatGround = Color(0xFF0B0C0E),
    senders = listOf(Color(0xFFD0A4F0), Color(0xFF6FD3D8), Color(0xFFE2B47F), Color(0xFFF2A0C6), Color(0xFFA9B4F5), Color(0xFFC6D26B)),
    dark = true,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FC), onPrimaryContainer = Color(0xFF0E1726),
    secondary = Color(0xFF454B52), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9ECEF), onSecondaryContainer = Color(0xFF111418),
    tertiary = Color(0xFF0B57D0), onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFC5221F), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E6), onErrorContainer = Color(0xFF7A0A0A),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF111418),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFF1F3F5), onSurfaceVariant = Color(0xFF4A5159),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFF1F3F5), surfaceContainerHigh = Color(0xFFE9ECEF), surfaceContainerHighest = Color(0xFFDFE3E7),
    outline = Color(0xFF737A82), outlineVariant = Color(0xFFD5D9DE),
    inverseSurface = Color(0xFF1F2328), inverseOnSurface = Color(0xFFF1F3F5), inversePrimary = Color(0xFF8AB4F8),
    scrim = Color(0x99000000),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0A1A33),
    primaryContainer = Color(0xFF1E3A66), onPrimaryContainer = Color(0xFFEEF2F8),
    secondary = Color(0xFFC4C9CF), onSecondary = Color(0xFF111418),
    secondaryContainer = Color(0xFF2A2E33), onSecondaryContainer = Color(0xFFECEEF0),
    tertiary = Color(0xFF8AB4F8), onTertiary = Color(0xFF0A1A33),
    error = Color(0xFFFF6B70), onError = Color(0xFF2B0003),
    errorContainer = Color(0xFF3D1517), onErrorContainer = Color(0xFFFFB3B6),
    background = Color(0xFF0B0C0E), onBackground = Color(0xFFECEEF0),
    surface = Color(0xFF16181B), onSurface = Color(0xFFECEEF0),
    surfaceVariant = Color(0xFF1D2024), onSurfaceVariant = Color(0xFFB9BFC6),
    surfaceContainerLowest = Color(0xFF0B0C0E), surfaceContainerLow = Color(0xFF131518),
    surfaceContainer = Color(0xFF16181B), surfaceContainerHigh = Color(0xFF1D2024), surfaceContainerHighest = Color(0xFF2A2E33),
    outline = Color(0xFF8B929A), outlineVariant = Color(0xFF33373D),
    inverseSurface = Color(0xFFECEEF0), inverseOnSurface = Color(0xFF16181B), inversePrimary = Color(0xFF0B57D0),
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

/** Plain corners: 6 to 16 dp, rounder for chat bubbles (see the chat package). */
private val RcShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Day, night, or follow the phone. */
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
