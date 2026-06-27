package com.usesense.sdk.ui.compose.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseColors
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Post-capture confirm step matching the hosted DocConfirm: a preview of the
 * captured document plus a client-side quality check (DocumentQuality). A detected
 * issue surfaces a warning and flips the CTAs to Retake / Use anyway. Pure
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
        var checking by remember { mutableStateOf(true) }
        var issue by remember { mutableStateOf<DocumentQuality.Issue?>(null) }
        LaunchedEffect(bitmap) {
            val result = withContext(Dispatchers.Default) { DocumentQuality.assess(bitmap) }
            issue = result
            checking = false
        }

        USScreenScaffold(
            modifier = modifier,
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (issue != null) {
                        USButton("Retake", onClick = onRetake, variant = USButtonVariant.Primary, size = USButtonSize.Large)
                        USButton("Use anyway", onClick = onUse, variant = USButtonVariant.Secondary, size = USButtonSize.Large)
                    } else {
                        USButton("Use this photo", onClick = onUse, variant = USButtonVariant.Primary, size = USButtonSize.Large, loading = checking)
                        USButton("Retake", onClick = onRetake, variant = USButtonVariant.Secondary, size = USButtonSize.Large)
                    }
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
            issue?.let {
                IssueBanner(it, colors)
                Spacer(Modifier.height(12.dp))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.secondary)
                    .border(1.dp, colors.border, RoundedCornerShape(18.dp)),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (checking) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Checking image quality…", style = USType.body.copy(fontSize = 13.sp), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueBanner(issue: DocumentQuality.Issue, colors: UseSenseColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.criticalBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(colors.destructive),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PriorityHigh, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(issue.title, style = USType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = colors.criticalText)
            Text(issue.detail, style = USType.body.copy(fontSize = 12.sp), color = colors.criticalText)
        }
    }
}
