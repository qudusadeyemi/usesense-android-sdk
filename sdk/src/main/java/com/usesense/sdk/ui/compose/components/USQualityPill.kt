package com.usesense.sdk.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseTheme

enum class USQualitySeverity { Good, Adjust, Critical }

/** Compact capture-quality cue shown over the camera (e.g. "Move closer"). */
@Composable
fun USQualityPill(
    text: String,
    severity: USQualitySeverity = USQualitySeverity.Adjust,
    modifier: Modifier = Modifier,
) {
    val colors = UseSenseTheme.colors
    val tint = when (severity) {
        USQualitySeverity.Good -> colors.success
        USQualitySeverity.Adjust -> colors.liveSense
        USQualitySeverity.Critical -> colors.destructive
    }
    val icon = when (severity) {
        USQualitySeverity.Good -> Icons.Filled.CheckCircle
        USQualitySeverity.Adjust -> Icons.Filled.CenterFocusWeak
        USQualitySeverity.Critical -> Icons.Filled.Error
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(tint)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = USType.button.copy(fontSize = 13.sp), color = Color.White)
    }
}
