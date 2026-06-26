package com.usesense.sdk.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.flows.FormField
import com.usesense.sdk.flows.FormFieldType
import com.usesense.sdk.ui.compose.USType
import com.usesense.sdk.ui.compose.UseSenseTheme
import com.usesense.sdk.ui.compose.components.USButton
import com.usesense.sdk.ui.compose.components.USButtonSize
import com.usesense.sdk.ui.compose.components.USButtonVariant
import com.usesense.sdk.ui.compose.components.USScreenScaffold
import com.usesense.sdk.ui.compose.components.USTextField

/**
 * Form surface state. Validation / coercion stays in the flow runner (single
 * source); this holder collects values and carries server/client errors so the
 * subject's input survives an invalid_input re-render.
 */
class FormState(val fields: List<FormField>, serverErrors: Map<String, String> = emptyMap()) {
    val values = mutableStateMapOf<String, String>()
    val booleans = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>().apply { putAll(serverErrors) }
    var isBusy by mutableStateOf(false)

    /** Raw value the runner validates/coerces: Boolean for checkbox, String otherwise. */
    fun raw(field: FormField): Any =
        if (field.type == FormFieldType.CHECKBOX) booleans[field.key] ?: false else values[field.key] ?: ""
}

/**
 * "A few details" — the typed-field form, matching the hosted FieldForm on the
 * Phase 0 kit (brand inputs + inline errors).
 */
@Composable
fun FormScreen(
    state: FormState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    brandColor: Color? = null,
) {
    UseSenseTheme {
        val colors = UseSenseTheme.colors
        val brand = brandColor ?: colors.primary
        USScreenScaffold(
            modifier = modifier,
            footer = {
                USButton(
                    text = "Continue",
                    onClick = onContinue,
                    variant = USButtonVariant.Primary,
                    size = USButtonSize.Large,
                    loading = state.isBusy,
                )
            },
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(4.dp))
                Text("A few details", style = USType.h2.copy(fontSize = 24.sp), color = colors.foreground)
                Spacer(Modifier.height(18.dp))
                state.fields.forEachIndexed { i, field ->
                    if (i > 0) Spacer(Modifier.height(18.dp))
                    if (field.type == FormFieldType.CHECKBOX) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.booleans[field.key] ?: false,
                                    onCheckedChange = { state.booleans[field.key] = it },
                                    colors = CheckboxDefaults.colors(checkedColor = brand),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(field.label ?: humanise(field.key), style = USType.body.copy(fontSize = 15.sp), color = colors.foreground)
                            }
                            val err = state.errors[field.key]
                            if (err != null) {
                                Text(err, style = USType.body.copy(fontSize = 12.sp), color = colors.criticalText, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    } else {
                        USTextField(
                            value = state.values[field.key] ?: "",
                            onValueChange = { state.values[field.key] = it },
                            label = field.label ?: humanise(field.key),
                            placeholder = field.placeholder ?: "",
                            error = state.errors[field.key],
                            keyboardType = keyboardType(field.type),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun keyboardType(type: FormFieldType): KeyboardType = when (type) {
    FormFieldType.NUMBER -> KeyboardType.Number
    FormFieldType.EMAIL -> KeyboardType.Email
    FormFieldType.TEL -> KeyboardType.Phone
    else -> KeyboardType.Text
}

private fun humanise(key: String): String =
    key.replace('_', ' ').replaceFirstChar { it.uppercase() }
