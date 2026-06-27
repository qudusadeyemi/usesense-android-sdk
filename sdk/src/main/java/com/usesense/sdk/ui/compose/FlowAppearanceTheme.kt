package com.usesense.sdk.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usesense.sdk.flows.AppearanceShape
import com.usesense.sdk.flows.FlowAppearance
import com.usesense.sdk.flows.forMode

/**
 * The Flows runner's resolved white-label appearance, expressed in Compose terms.
 * Built by [resolveFlowAppearance] from a merged [FlowAppearance] for the active
 * (light/dark) mode, then provided through [LocalFlowAppearance]. [UseSenseTheme]
 * consults it so the parity screens reflect the operator/developer customization
 * automatically (they already read [UseSenseTheme.colors] / shape / fonts).
 */
@Immutable
data class ResolvedFlowAppearance(
    val colors: UseSenseColors,
    val buttonRadius: Dp,
    val buttonStyle: AppearanceShape.ButtonStyle,
    /** Body font; null = keep the bundled DM Sans. */
    val bodyFont: FontFamily?,
    /** Display/heading font; null = keep the bundled Outfit. */
    val displayFont: FontFamily?,
    /** Background image URL (drawn behind the screen) if the operator set one. */
    val backgroundImageUrl: String?,
    val logoUrl: String?,
    val logoPlacement: com.usesense.sdk.flows.AppearanceLogo.Placement?,
    val logoHeight: Dp?,
    val isDark: Boolean,
)

/** Provided by the Flows runner. Null means "no appearance" -> built-in defaults. */
val LocalFlowAppearance = staticCompositionLocalOf<ResolvedFlowAppearance?> { null }

/** Resolved button radius for the active appearance (defaults to brand md). */
val LocalUseSenseButtonRadius = staticCompositionLocalOf { USRadius.md }

/** Resolved button style for the active appearance (defaults to filled). */
val LocalUseSenseButtonStyle =
    staticCompositionLocalOf { AppearanceShape.ButtonStyle.FILLED }

/** Resolved body / display font families (null = bundled DM Sans / Outfit). */
val LocalUseSenseFonts = staticCompositionLocalOf<UseSenseFonts> { UseSenseFonts() }

/** Resolved fonts; null fields keep the bundled families. */
@Immutable
data class UseSenseFonts(val body: FontFamily? = null, val display: FontFamily? = null)

/**
 * Build a [ResolvedFlowAppearance] from a merged [FlowAppearance] for the given
 * mode, layering supplied tokens over the built-in light/dark palette. Mirrors
 * `resolveTheme` in the web SDK.
 */
fun resolveFlowAppearance(appearance: FlowAppearance?, dark: Boolean): ResolvedFlowAppearance {
    val base = if (dark) UseSenseDarkColors else UseSenseLightColors
    if (appearance == null) {
        return ResolvedFlowAppearance(
            colors = base,
            buttonRadius = USRadius.md,
            buttonStyle = AppearanceShape.ButtonStyle.FILLED,
            bodyFont = null,
            displayFont = null,
            backgroundImageUrl = null,
            logoUrl = null,
            logoPlacement = null,
            logoHeight = null,
            isDark = dark,
        )
    }

    val layer = appearance.colors?.forMode(dark)
    val bg = parseColor(appearance.background?.color) ?: parseColor(layer?.background) ?: base.background
    val colors = base.copy(
        background = bg,
        foreground = parseColor(layer?.foreground) ?: base.foreground,
        card = parseColor(layer?.surface) ?: base.card,
        secondary = parseColor(layer?.surface) ?: base.secondary,
        muted = parseColor(layer?.muted) ?: base.muted,
        mutedForeground = parseColor(layer?.muted) ?: base.mutedForeground,
        border = parseColor(layer?.border) ?: base.border,
        primary = parseColor(layer?.primary) ?: base.primary,
        primaryForeground = parseColor(layer?.primaryForeground) ?: base.primaryForeground,
        success = parseColor(layer?.success) ?: base.success,
        destructive = parseColor(layer?.error) ?: base.destructive,
        warning = parseColor(layer?.warning) ?: base.warning,
    )

    val sh = appearance.shape
    val buttonRadius = (sh?.buttonRadius ?: sh?.radius)?.dp ?: USRadius.md
    val buttonStyle = sh?.buttonStyle ?: AppearanceShape.ButtonStyle.FILLED

    return ResolvedFlowAppearance(
        colors = colors,
        buttonRadius = buttonRadius,
        buttonStyle = buttonStyle,
        bodyFont = appearance.typography?.fontFamily?.let(::systemFontFamily),
        displayFont = (appearance.typography?.displayFamily ?: appearance.typography?.fontFamily)
            ?.let(::systemFontFamily),
        backgroundImageUrl = appearance.background?.imageUrl,
        logoUrl = appearance.logo?.url,
        logoPlacement = appearance.logo?.placement,
        logoHeight = appearance.logo?.height?.dp,
        isDark = dark,
    )
}

/** Accessor for the resolved button shape, read inside the brand components. */
object UseSenseShape {
    val buttonRadius: Dp
        @Composable @ReadOnlyComposable get() = LocalUseSenseButtonRadius.current
    val buttonStyle: AppearanceShape.ButtonStyle
        @Composable @ReadOnlyComposable get() = LocalUseSenseButtonStyle.current
}

/**
 * Resolve a CSS-ish "#RRGGBB" / "#AARRGGBB" / "#RGB" hex string to a Compose
 * [Color]. Returns null for blank / unparseable input so callers fall back to
 * the base token. Non-hex CSS colors (rgba(), named) are not supported on the
 * wire for native; the dashboard emits hex.
 */
internal fun parseColor(value: String?): Color? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!raw.startsWith("#")) return null
    val hex = raw.substring(1)
    return try {
        when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2).toInt(16)
                val g = hex[1].toString().repeat(2).toInt(16)
                val b = hex[2].toString().repeat(2).toInt(16)
                Color(r, g, b)
            }
            6 -> {
                val v = hex.toLong(16)
                Color(0xFF000000 or v)
            }
            8 -> Color(hex.toLong(16))
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

/**
 * Map a font-family *name* (e.g. "DM Sans", "Roboto") to a system [FontFamily].
 * The native SDK cannot fetch web fonts at runtime, so a supplied family resolves
 * to the matching platform family when known, otherwise the default sans-serif —
 * the bundled brand fonts stay in use when typography is omitted entirely.
 */
private fun systemFontFamily(name: String): FontFamily {
    val first = name.split(',').firstOrNull()?.trim()?.trim('\'', '"')?.lowercase() ?: return FontFamily.Default
    return when (first) {
        "serif", "times", "times new roman", "georgia" -> FontFamily.Serif
        "monospace", "mono", "courier", "courier new", "jetbrains mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }
}
