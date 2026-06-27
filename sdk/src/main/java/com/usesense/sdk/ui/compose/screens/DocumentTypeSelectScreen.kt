package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 * "Pick a document" — the document-type chooser, matching the hosted run page's
 * select-type phase. Pure presentation.
 */
@Composable
fun DocumentTypeSelectScreen(
    documentTypes: List<String>,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
    brandColor: Color? = null,
    displayName: String? = null,
    logoUrl: String? = null,
    title: String = "Pick a document",
    body: String = "Choose a document to verify your identity. We don't accept scans or copies.",
    continueText: String = "Continue",
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        val brand = brandColor ?: colors.primary
        var selected by remember { mutableStateOf(documentTypes.firstOrNull() ?: "") }
        USScreenScaffold(
            modifier = modifier,
            header = if (displayName != null || logoUrl != null) {
                { USBrandingHeader(displayName = displayName, logoUrl = logoUrl) }
            } else {
                null
            },
            footer = {
                USButton(
                    text = continueText,
                    onClick = { if (selected.isNotEmpty()) onContinue(selected) },
                    variant = USButtonVariant.Primary,
                    size = USButtonSize.Large,
                )
            },
        ) {
            Spacer(Modifier.height(4.dp))
            Text(title, style = USType.h2.copy(fontSize = 24.sp), color = colors.foreground)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = USType.body,
                color = colors.mutedForeground,
            )
            Spacer(Modifier.height(24.dp))
            documentTypes.forEachIndexed { i, type ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                DocTypeRow(type, type == selected, brand, colors) { selected = type }
            }
        }
    }
}

@Composable
private fun DocTypeRow(
    type: String,
    isSelected: Boolean,
    brand: Color,
    colors: UseSenseColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) brand.copy(alpha = 0.04f) else colors.card)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) brand else colors.border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Description, null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            type,
            style = USType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isSelected) brand else colors.borderStrong, CircleShape),
            )
            if (isSelected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(brand))
            }
        }
    }
}

/**
 * The document-step primer ("Get your <type> ready"), matching the hosted copy.
 * Thin convenience over [PrimerScreen].
 */
@Composable
fun DocumentPrimerScreen(
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    documentType: String? = null,
    categoryLabel: String = "document",
    issuingCountries: List<String> = emptyList(),
    allowCamera: Boolean = true,
    allowUpload: Boolean = true,
    onSecondary: (() -> Unit)? = null,
    isBusy: Boolean = false,
    brandColor: Color? = null,
    displayName: String? = null,
    logoUrl: String? = null,
    title: String? = null,
    body: String = "We'll capture it and check it's clear and readable.",
    scanText: String = "Take a photo",
    uploadText: String = "Upload a file",
    uploadInsteadText: String = "Upload a file instead",
) {
    val note: String? = if (issuingCountries.isEmpty()) {
        null
    } else {
        val label = if (issuingCountries.size == 1) "country" else "countries"
        val list = issuingCountries.take(6).joinToString(", ")
        val ellipsis = if (issuingCountries.size > 6) "…" else ""
        "Accepted issuing $label: $list$ellipsis."
    }
    PrimerScreen(
        icon = Icons.Filled.Description,
        title = title
            ?: documentType?.let { "Get your ${it.lowercase()} ready" }
            ?: "Get your $categoryLabel ready",
        subtitle = body,
        points = listOf(
            PrimerPoint(Icons.Filled.CameraAlt, "Place it on a flat, dark surface in good light."),
            PrimerPoint(Icons.Filled.AutoAwesome, "Make sure all four corners are visible and details are sharp."),
        ),
        note = note,
        primaryText = if (allowCamera) scanText else uploadText,
        onPrimary = onPrimary,
        secondaryText = if (allowCamera && allowUpload) uploadInsteadText else null,
        onSecondary = if (allowCamera && allowUpload) onSecondary else null,
        isBusy = isBusy,
        brandColor = brandColor,
        displayName = displayName,
        logoUrl = logoUrl,
        modifier = modifier,
    )
}
