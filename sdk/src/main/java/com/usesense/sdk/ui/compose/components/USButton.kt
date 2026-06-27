package com.usesense.sdk.ui.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usesense.sdk.flows.AppearanceShape
import com.usesense.sdk.ui.compose.USMotion
import com.usesense.sdk.ui.compose.UseSenseShape
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.UseSenseTypeRamp

enum class USButtonVariant { Primary, Secondary, Ghost }

enum class USButtonSize(val height: Dp, val hPadding: Dp) {
    Small(36.dp, 14.dp),
    Default(44.dp, 18.dp),
    Large(52.dp, 22.dp),
}

/**
 * Brand button matching the hosted page: DeepSense-blue primary, warm secondary,
 * ghost; heights 36/44/52, 10dp radius, DM Sans 600 label, pressed scale on the
 * brand spring, and an inline loading state.
 */
@Composable
fun USButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: USButtonVariant = USButtonVariant.Primary,
    size: USButtonSize = USButtonSize.Default,
    fullWidth: Boolean = true,
    loading: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = UseSenseTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(USMotion.Fast, easing = USMotion.Ease),
        label = "us_btn_scale",
    )

    // White-label: the resolved appearance can request an outlined primary
    // (buttonStyle = outline) and a custom corner radius. Outline turns the
    // filled primary into a transparent, brand-bordered button.
    val outlinePrimary =
        variant == USButtonVariant.Primary && UseSenseShape.buttonStyle == AppearanceShape.ButtonStyle.OUTLINE
    val bg = when {
        outlinePrimary -> Color.Transparent
        variant == USButtonVariant.Primary -> colors.primary
        variant == USButtonVariant.Secondary -> colors.secondary
        else -> Color.Transparent // Ghost
    }
    val fg = when {
        outlinePrimary -> colors.primary
        variant == USButtonVariant.Primary -> colors.primaryForeground
        variant == USButtonVariant.Secondary -> colors.foreground
        else -> colors.primary // Ghost
    }
    val shape = RoundedCornerShape(UseSenseShape.buttonRadius)
    val borderStroke: BorderStroke? = when {
        outlinePrimary -> BorderStroke(1.5.dp, colors.primary)
        variant == USButtonVariant.Secondary -> BorderStroke(1.dp, colors.border)
        else -> null
    }

    Box(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(size.height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (!enabled) 0.5f else if (pressed) 0.9f else 1f
            }
            .clip(shape)
            .background(bg)
            .then(
                if (borderStroke != null) {
                    Modifier.border(borderStroke, shape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
            ) { onClick() }
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (loading) {
                CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            } else if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = UseSenseTypeRamp.button, color = fg)
        }
    }
}
