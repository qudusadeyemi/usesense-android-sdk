package com.usesense.sdk.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Adaptive brand colour set mirroring the hosted page tokens (theme.css :root /
 * .dark). Resolved per light/dark by [UseSenseTheme] and read via
 * `UseSenseTheme.colors`.
 */
@Immutable
data class UseSenseColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val secondary: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val borderStrong: Color,
    val primary: Color,
    val primaryForeground: Color,
    val deepSense: Color,
    val liveSense: Color,
    val matchSense: Color,
    val success: Color,
    val successText: Color,
    val destructive: Color,
    val criticalText: Color,
    val warning: Color,
    val warningText: Color,
    val magic: Color,
    val magicText: Color,
    val brandBg: Color,
    val successBg: Color,
    val criticalBg: Color,
    val warningBg: Color,
    val magicBg: Color,
    val isDark: Boolean,
)

val UseSenseLightColors = UseSenseColors(
    background = Color(0xFFFDFCFA),
    foreground = Color(0xFF1C1A17),
    card = Color(0xFFFFFFFF),
    secondary = Color(0xFFF5F3EF),
    muted = Color(0xFFF5F3EF),
    mutedForeground = Color(0xFF6B6760),
    border = Color(0xFFE8E5DE),
    borderStrong = Color(0xFFD0CCBF),
    primary = Color(0xFF4F7CFF),
    primaryForeground = Color(0xFFFFFFFF),
    deepSense = Color(0xFF4F7CFF),
    liveSense = Color(0xFF7C5CFC),
    matchSense = Color(0xFF00D4AA),
    success = Color(0xFF00D4AA),
    successText = Color(0xFF008066),
    destructive = Color(0xFFFF6B4A),
    criticalText = Color(0xFFB73520),
    warning = Color(0xFFFFB84D),
    warningText = Color(0xFFB77829),
    magic = Color(0xFF7C5CFC),
    magicText = Color(0xFF4C35B0),
    brandBg = Color(0xFFEBF0FF),
    successBg = Color(0xFFE6FBF5),
    criticalBg = Color(0xFFFFF0EC),
    warningBg = Color(0xFFFFF7E8),
    magicBg = Color(0xFFF2EFFE),
    isDark = false,
)

val UseSenseDarkColors = UseSenseColors(
    background = Color(0xFF1C1A17),
    foreground = Color(0xFFF5F3EF),
    card = Color(0xFF2A2723),
    secondary = Color(0xFF3D3A35),
    muted = Color(0xFF3D3A35),
    mutedForeground = Color(0xFF9E9A92),
    border = Color(0xFF39352F),
    borderStrong = Color(0xFF4A453E),
    primary = Color(0xFF4F7CFF),
    primaryForeground = Color(0xFFFFFFFF),
    deepSense = Color(0xFF4F7CFF),
    liveSense = Color(0xFF7C5CFC),
    matchSense = Color(0xFF00D4AA),
    success = Color(0xFF00D4AA),
    successText = Color(0xFF33DFAF),
    destructive = Color(0xFFFF6B4A),
    criticalText = Color(0xFFFF8867),
    warning = Color(0xFFFFB84D),
    warningText = Color(0xFFFFCF75),
    magic = Color(0xFF7C5CFC),
    magicText = Color(0xFFB09FF9),
    brandBg = Color(0xFF232A3F),
    successBg = Color(0xFF10322A),
    criticalBg = Color(0xFF3A1F18),
    warningBg = Color(0xFF3A2F17),
    magicBg = Color(0xFF261D3D),
    isDark = true,
)

val LocalUseSenseColors = staticCompositionLocalOf { UseSenseLightColors }
