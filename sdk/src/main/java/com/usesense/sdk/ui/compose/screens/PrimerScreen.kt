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
import androidx.compose.material.icons.filled.VerifiedUser
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
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USBrandingHeader
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USScreenScaffold

data class PrimerPoint(val icon: ImageVector, val text: String)

/**
 * Generic pre-step primer matching the hosted run page's `Primer` (icon tile,
 * title, "why", do's, optional note, primary + optional secondary CTA). Wraps
 * itself in [UseSenseTheme]. Pure presentation.
 */
@Composable
fun PrimerScreen(
    icon: ImageVector,
    title: String,
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    points: List<PrimerPoint> = emptyList(),
    note: String? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    isBusy: Boolean = false,
    brandColor: Color? = null,
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
                    if (note != null) {
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
                            Text(note, style = USType.body.copy(fontSize = 12.sp), color = colors.mutedForeground)
                        }
                    }
                    USButton(primaryText, onClick = onPrimary, variant = USButtonVariant.Primary, size = USButtonSize.Large, loading = isBusy)
                    if (secondaryText != null && onSecondary != null) {
                        USButton(secondaryText, onClick = onSecondary, variant = USButtonVariant.Secondary, size = USButtonSize.Large)
                    }
                }
            },
        ) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(brand.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = brand, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = USType.h2.copy(fontSize = 24.sp), color = colors.foreground)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = USType.body, color = colors.mutedForeground)
            }
            if (points.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                points.forEachIndexed { i, p ->
                    if (i > 0) Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(colors.secondary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(p.icon, null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            p.text,
                            style = USType.body.copy(fontSize = 14.sp),
                            color = colors.foreground,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
