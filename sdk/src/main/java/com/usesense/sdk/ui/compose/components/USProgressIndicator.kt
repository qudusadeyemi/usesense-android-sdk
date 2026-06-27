package com.usesense.sdk.ui.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.usesense.sdk.ui.compose.USMotion
import com.usesense.sdk.ui.compose.UseSenseTheme

/** Slim stepped progress bar with animated brand-blue fill. `current` is 1-based. */
@Composable
fun USProgressIndicator(total: Int, current: Int, modifier: Modifier = Modifier) {
    val colors = UseSenseTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total.coerceAtLeast(1)) {
            val fill by animateColorAsState(
                targetValue = if (i < current) colors.primary else colors.border,
                animationSpec = tween(USMotion.Normal, easing = USMotion.Ease),
                label = "us_step_$i",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(fill),
            )
        }
    }
}
