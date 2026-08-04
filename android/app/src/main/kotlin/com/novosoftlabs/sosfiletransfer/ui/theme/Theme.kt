package com.novosoftlabs.sosfiletransfer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same "Calm Signal" tokens as shared/style.css's :root and
// :root[data-theme="light"] — kept in sync by hand since there's no shared
// source of truth across a CSS file and a Kotlin one. If the web palette
// changes, update both.

private val DarkBg = Color(0xFF060B12)
private val DarkSurface = Color(0xFF0E1620)
private val DarkGlass = Color(0x1CFFFFFF) // rgba(255,255,255,0.11)
private val DarkText = Color(0xFFEAF2F5)
private val DarkMuted = Color(0xFF8FA3AC)
private val Accent = Color(0xFF3FDCC0)
private val AccentHi = Color(0xFF6BEBD3)
private val Accent2 = Color(0xFF6FB8FF)
private val AccentInkDark = Color(0xFF052420)
private val GreenDark = Color(0xFF57D9A3)
private val RedDark = Color(0xFFFF8A80)

private val LightBg = Color(0xFFF4F8FA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightGlass = Color(0x8CFFFFFF) // rgba(255,255,255,0.55)
private val LightText = Color(0xFF10202A)
private val LightMuted = Color(0xFF5C7078)
private val AccentLight = Color(0xFF0E9C88)
private val Accent2Light = Color(0xFF2C7BE0)
private val AccentInkLight = Color(0xFFFFFFFF)
private val GreenLight = Color(0xFF128A6B)
private val RedLight = Color(0xFFC43D3D)

/** The handful of extra tokens Material3's ColorScheme doesn't have a slot
 *  for — glass fills and the two functional colors (verified/error) that stay
 *  separate from the brand accent, same discipline as the web CSS. */
data class CalmSignalExtendedColors(
    val glass: Color,
    val muted: Color,
    val success: Color,
    val danger: Color,
    val accentInk: Color,
)

private val LocalExtendedColors = androidx.compose.runtime.staticCompositionLocalOf {
    CalmSignalExtendedColors(DarkGlass, DarkMuted, GreenDark, RedDark, AccentInkDark)
}

val MaterialTheme.calmSignal: CalmSignalExtendedColors
    @Composable get() = LocalExtendedColors.current

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInkDark,
    secondary = Accent2,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkGlass,
    onSurfaceVariant = DarkMuted,
    error = RedDark,
    tertiary = AccentHi,
)

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = AccentInkLight,
    secondary = Accent2Light,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightGlass,
    onSurfaceVariant = LightMuted,
    error = RedLight,
)

@Composable
fun SosFileTransferTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val extended = if (darkTheme) {
        CalmSignalExtendedColors(DarkGlass, DarkMuted, GreenDark, RedDark, AccentInkDark)
    } else {
        CalmSignalExtendedColors(LightGlass, LightMuted, GreenLight, RedLight, AccentInkLight)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
