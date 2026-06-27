package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USResultIcon
import com.usesense.sdk.ui.compose.components.USResultKind
import com.usesense.sdk.ui.compose.components.USScreenScaffold

enum class FlowResultKind { Success, Review, NotVerified }

/**
 * Terminal outcome screen matching the hosted run page exactly (icon + title +
 * subtitle + optional continue): APPROVE / MANUAL_REVIEW / not-verified.
 */
@Composable
fun FlowResultScreen(
    kind: FlowResultKind,
    modifier: Modifier = Modifier,
    continueText: String? = null,
    onContinue: (() -> Unit)? = null,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        val title = when (kind) {
            FlowResultKind.Success -> "Verification complete"
            FlowResultKind.Review -> "Under review"
            FlowResultKind.NotVerified -> "Not verified"
        }
        val subtitle = when (kind) {
            FlowResultKind.Success -> "Thank you. You can close this page."
            FlowResultKind.Review -> "Your details are being reviewed."
            FlowResultKind.NotVerified -> "We could not complete your verification."
        }
        val iconKind = when (kind) {
            FlowResultKind.Success -> USResultKind.Success
            FlowResultKind.Review -> USResultKind.Review
            FlowResultKind.NotVerified -> USResultKind.Error
        }
        USScreenScaffold(
            modifier = modifier,
            footer = if (continueText != null && onContinue != null) {
                {
                    USButton(continueText, onClick = onContinue, variant = USButtonVariant.Primary, size = USButtonSize.Large)
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
                USResultIcon(iconKind)
                Spacer(Modifier.height(16.dp))
                Text(title, style = USType.h2.copy(fontSize = 24.sp), color = colors.foreground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = USType.body,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
