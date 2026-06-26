package com.usesense.sdk.ui.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.usesense.sdk.ui.compose.USMotion
import com.usesense.sdk.ui.compose.UseSenseTheme

enum class USResultKind { Success, Error, Review }

/** Large animated outcome badge for result screens; draws on with the brand spring. */
@Composable
fun USResultIcon(kind: USResultKind, modifier: Modifier = Modifier) {
    val colors = UseSenseTheme.colors
    val tint = when (kind) {
        USResultKind.Success -> colors.success
        USResultKind.Error -> colors.destructive
        USResultKind.Review -> colors.warning
    }
    val icon = when (kind) {
        USResultKind.Success -> Icons.Filled.Check
        USResultKind.Error -> Icons.Filled.Close
        USResultKind.Review -> Icons.Filled.Schedule
    }
    var shown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.6f,
        animationSpec = tween(USMotion.Normal, easing = USMotion.Ease),
        label = "us_result_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(USMotion.Normal),
        label = "us_result_alpha",
    )
    LaunchedEffect(Unit) { shown = true }

    Box(
        modifier
            .size(104.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)))
        Box(Modifier.size(68.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
