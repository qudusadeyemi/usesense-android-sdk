package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USScreenScaffold

/** Branded loading screen (link resolution / busy) — centered spinner + title. */
@Composable
fun FlowLoadingScreen(
    title: String = "Loading",
    modifier: Modifier = Modifier,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        USScreenScaffold(modifier = modifier) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = colors.primary, strokeWidth = 4.dp, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(18.dp))
                Text(title, style = USType.h2.copy(fontSize = 20.sp), color = colors.foreground)
            }
        }
    }
}

/** Friendly terminal/error screen — never a raw stack or code. */
@Composable
fun FlowErrorScreen(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Unavailable",
    retryText: String? = null,
    onRetry: (() -> Unit)? = null,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        USScreenScaffold(
            modifier = modifier,
            footer = if (retryText != null && onRetry != null) {
                {
                    USButton(retryText, onClick = onRetry, variant = USButtonVariant.Primary, size = USButtonSize.Large)
                }
            } else {
                null
            },
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(colors.warning.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ReportProblem, null, tint = colors.warning, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(title, style = USType.h2.copy(fontSize = 22.sp), color = colors.foreground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = USType.body,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
