package com.usesense.sdk.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.usesense.sdk.flows.FlowAppearance

/**
 * Wraps a Flows-runner Compose surface in the resolved white-label appearance.
 *
 * Resolves the merged [FlowAppearance] for the active mode (respecting
 * `mode: light/dark/auto` via [isSystemInDarkTheme]), provides it through
 * [LocalFlowAppearance] so [UseSenseTheme] and the parity screens reflect it
 * automatically, and paints the appearance background (color, then optional
 * image) behind the content. Pure presentation — no capture/state logic.
 */
@Composable
fun FlowAppearanceHost(
    appearance: FlowAppearance?,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance?.mode) {
        FlowAppearance.Mode.DARK -> true
        FlowAppearance.Mode.LIGHT -> false
        else -> systemDark
    }
    val resolved = remember(appearance, dark) { resolveFlowAppearance(appearance, dark) }

    CompositionLocalProvider(LocalFlowAppearance provides resolved) {
        Box(Modifier.fillMaxSize().background(resolved.colors.background)) {
            resolved.backgroundImageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            content()
        }
    }
}
