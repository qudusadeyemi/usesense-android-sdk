package com.usesense.sdk.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.usesense.sdk.ui.compose.USRadius
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseTheme

/** Top-of-screen org lockup: optional logo + display name + "Secured by UseSense". */
@Composable
fun USBrandingHeader(
    modifier: Modifier = Modifier,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    val colors = UseSenseTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(USRadius.sm)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column {
            if (displayName != null) {
                Text(
                    displayName,
                    style = USType.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = colors.foreground,
                )
            }
            Text(
                "Secured by UseSense",
                style = USType.bodyMedium.copy(fontSize = 11.sp),
                color = colors.mutedForeground,
            )
        }
    }
}
