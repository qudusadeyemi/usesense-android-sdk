package com.usesense.sdk.ui.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** Brand corner radii (dp), matching theme.css --radius-*. */
object USRadius {
    val xs = 4.dp; val sm = 6.dp; val md = 10.dp; val lg = 14.dp; val xl = 20.dp
}

/** Brand spacing scale (dp). */
object USSpacing {
    val xxs = 2.dp; val xs = 4.dp; val sm = 8.dp; val md = 16.dp
    val lg = 24.dp; val xl = 32.dp; val xxl = 48.dp; val xxxl = 64.dp
}

/** Brand motion: spring-out easing + standard durations (ms). */
object USMotion {
    val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val Fast = 150
    const val Normal = 250
    const val Slow = 400
}

/**
 * Wraps content in the UseSense brand theme, selecting the light/dark colour set.
 * Read colours inside via `UseSenseTheme.colors`.
 *
 * When a [ResolvedFlowAppearance] has been provided via [LocalFlowAppearance]
 * (by the Flows runner), its resolved palette, mode, button shape and fonts win
 * over the defaults, so the parity screens reflect the white-label appearance
 * automatically. With no appearance present this behaves exactly as before.
 */
@Composable
fun UseSenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val appearance = LocalFlowAppearance.current
    val colors = appearance?.colors ?: if (darkTheme) UseSenseDarkColors else UseSenseLightColors
    CompositionLocalProvider(
        LocalUseSenseColors provides colors,
        LocalContentColor provides colors.foreground,
        LocalUseSenseButtonRadius provides (appearance?.buttonRadius ?: USRadius.md),
        LocalUseSenseButtonStyle provides
            (appearance?.buttonStyle ?: com.usesense.sdk.flows.AppearanceShape.ButtonStyle.FILLED),
        LocalUseSenseFonts provides UseSenseFonts(
            body = appearance?.bodyFont,
            display = appearance?.displayFont,
        ),
        content = content,
    )
}

/** Accessor object (same name as the composable, MaterialTheme-style). */
object UseSenseTheme {
    val colors: UseSenseColors
        @Composable @ReadOnlyComposable get() = LocalUseSenseColors.current

    /** Custom result illustrations / icon-slot URLs, or null for built-in glyphs. */
    val icons: com.usesense.sdk.flows.AppearanceIcons?
        @Composable @ReadOnlyComposable get() = LocalFlowAppearance.current?.icons

    /** Custom loader preset/asset, or null for the built-in spinner. */
    val loader: com.usesense.sdk.flows.AppearanceLoader?
        @Composable @ReadOnlyComposable get() = LocalFlowAppearance.current?.loader
}
