package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseColors
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USBrandingHeader
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USScreenScaffold

/**
 * Pre-capture primer for the face/liveness step, matching the hosted run page's
 * FacePrimer exactly (copy + layout). Wraps itself in [UseSenseTheme] so it can be
 * dropped into a ComposeView and themes light/dark automatically. Pure
 * presentation — no capture logic.
 */
@Composable
fun FacePrimerScreen(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    brandColor: Color? = null,
    isBusy: Boolean = false,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        val brand = brandColor ?: colors.primary
        USScreenScaffold(
            modifier = modifier,
            header = if (displayName != null || logoUrl != null) {
                { USBrandingHeader(displayName = displayName, logoUrl = logoUrl) }
            } else {
                null
            },
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.background)
                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.VerifiedUser, null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Your face and surroundings are captured during this check and processed securely.",
                            style = USType.body.copy(fontSize = 12.sp),
                            color = colors.mutedForeground,
                        )
                    }
                    USButton(
                        text = "Start face scan",
                        onClick = onStart,
                        variant = USButtonVariant.Primary,
                        size = USButtonSize.Large,
                        loading = isBusy,
                    )
                }
            },
        ) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(brand.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CenterFocusStrong, null, tint = brand, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Take a selfie", style = USType.h2.copy(fontSize = 24.sp), color = colors.foreground)
            Spacer(Modifier.height(6.dp))
            Text(
                "A quick, secure face scan confirms you're a real, live person.",
                style = USType.body,
                color = colors.mutedForeground,
            )
            Spacer(Modifier.height(28.dp))
            PrimerPoint(Icons.Filled.Visibility, "Face forward and make sure your eyes are clearly visible.", colors)
            Spacer(Modifier.height(16.dp))
            PrimerPoint(Icons.Filled.Face, "Remove anything that covers your face. Eyeglasses are okay.", colors)
        }
    }
}

@Composable
private fun PrimerPoint(icon: ImageVector, text: String, colors: UseSenseColors) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(colors.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = USType.body.copy(fontSize = 14.sp),
            color = colors.foreground,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
