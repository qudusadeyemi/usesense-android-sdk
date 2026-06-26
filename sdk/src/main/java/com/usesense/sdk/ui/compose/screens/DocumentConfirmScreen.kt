package com.usesense.sdk.ui.compose.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USScreenScaffold

/**
 * Post-capture confirm step matching the hosted DocConfirm: a preview of the
 * captured document with Use / Retake (and an optional upload-instead). Pure
 * presentation.
 */
@Composable
fun DocumentConfirmScreen(
    bitmap: Bitmap,
    onUse: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
    onUploadInstead: (() -> Unit)? = null,
    brandColor: Color? = null,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        USScreenScaffold(
            modifier = modifier,
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    USButton("Use this photo", onClick = onUse, variant = USButtonVariant.Primary, size = USButtonSize.Large)
                    USButton("Retake", onClick = onRetake, variant = USButtonVariant.Secondary, size = USButtonSize.Large)
                    if (onUploadInstead != null) {
                        Text(
                            "Upload a different file",
                            style = USType.body.copy(fontSize = 12.sp),
                            color = colors.mutedForeground,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { onUploadInstead() },
                        )
                    }
                }
            },
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Check your document is clear",
                style = USType.body.copy(fontSize = 15.sp),
                color = colors.foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.secondary)
                    .border(1.dp, colors.border, RoundedCornerShape(18.dp)),
            )
        }
    }
}
