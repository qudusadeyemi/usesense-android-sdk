package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Badge
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.usesense.sdk.ui.compose.components.USTextField

data class IdTypeOption(
    val value: String,
    val label: String,
    val hint: String? = null,
    val field: String,
    val maxLength: Int? = null,
    val numeric: Boolean = false,
)

/**
 * "Select an option" — the ID-number step, matching the hosted run page's
 * IdNumberFlow (ID-type chooser + a typed value field). Pure presentation.
 */
@Composable
fun IdNumberScreen(
    idTypes: List<IdTypeOption>,
    onSubmit: (idType: String, field: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    brandColor: Color? = null,
    displayName: String? = null,
    logoUrl: String? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        val brand = brandColor ?: colors.primary
        var chosen by remember { mutableStateOf(if (idTypes.size == 1) idTypes.first().value else null) }
        var value by remember { mutableStateOf("") }
        val selected = idTypes.firstOrNull { it.value == chosen }
        val tooShort = selected?.maxLength?.let { value.trim().length < it } ?: value.trim().isEmpty()

        USScreenScaffold(
            modifier = modifier,
            header = if (displayName != null || logoUrl != null) {
                { USBrandingHeader(displayName = displayName, logoUrl = logoUrl) }
            } else {
                null
            },
            footer = {
                USButton(
                    text = "Continue",
                    onClick = {
                        val sel = selected
                        if (sel != null && !tooShort) onSubmit(sel.value, sel.field, value.trim())
                    },
                    variant = USButtonVariant.Primary,
                    size = USButtonSize.Large,
                    loading = isBusy,
                )
            },
        ) {
            Spacer(Modifier.height(4.dp))
            Text("Select an option", style = USType.h2.copy(fontSize = 24.sp), color = colors.foreground)
            Spacer(Modifier.height(6.dp))
            Text("Choose the type of ID to validate.", style = USType.body, color = colors.mutedForeground)
            Spacer(Modifier.height(24.dp))
            idTypes.forEachIndexed { i, opt ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                IdTypeRow(opt, opt.value == chosen, brand, colors) { chosen = opt.value; value = "" }
            }
            if (selected != null) {
                Spacer(Modifier.height(20.dp))
                USTextField(
                    value = value,
                    onValueChange = { raw ->
                        var next = if (selected.numeric) raw.filter { it.isDigit() } else raw
                        val max = selected.maxLength
                        if (max != null && next.length > max) next = next.take(max)
                        value = next
                    },
                    label = "Enter your ${selected.label}",
                    placeholder = selected.hint ?: "",
                    keyboardType = if (selected.numeric) KeyboardType.Number else KeyboardType.Text,
                )
            }
        }
    }
}

@Composable
private fun IdTypeRow(
    option: IdTypeOption,
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
            Icon(Icons.Filled.Badge, null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = USType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = colors.foreground,
            )
            if (option.hint != null) {
                Text(option.hint, style = USType.body.copy(fontSize = 12.sp), color = colors.mutedForeground)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .border(2.dp, if (isSelected) brand else colors.borderStrong, CircleShape),
            )
            if (isSelected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(brand))
            }
        }
    }
}
