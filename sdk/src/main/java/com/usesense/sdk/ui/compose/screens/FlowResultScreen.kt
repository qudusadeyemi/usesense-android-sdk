package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    successTitle: String = "Verification complete",
    successBody: String = "Thank you. You can close this page.",
    reviewTitle: String = "Under review",
    reviewBody: String = "Your details are being reviewed.",
    notVerifiedTitle: String = "Not verified",
    notVerifiedBody: String = "We could not complete your verification.",
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        val title = when (kind) {
            FlowResultKind.Success -> successTitle
            FlowResultKind.Review -> reviewTitle
            FlowResultKind.NotVerified -> notVerifiedTitle
        }
        val subtitle = when (kind) {
            FlowResultKind.Success -> successBody
            FlowResultKind.Review -> reviewBody
            FlowResultKind.NotVerified -> notVerifiedBody
        }
        val iconKind = when (kind) {
            FlowResultKind.Success -> USResultKind.Success
            FlowResultKind.Review -> USResultKind.Review
            FlowResultKind.NotVerified -> USResultKind.Error
        }
        val customIllustrationUrl = UseSenseTheme.icons?.let { icons ->
            when (kind) {
                FlowResultKind.Success -> icons.success
                FlowResultKind.Review -> icons.review
                FlowResultKind.NotVerified -> icons.notVerified
            }
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
                if (customIllustrationUrl != null) {
                    AsyncImage(
                        model = customIllustrationUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(104.dp),
                    )
                } else {
                    USResultIcon(iconKind)
                }
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
