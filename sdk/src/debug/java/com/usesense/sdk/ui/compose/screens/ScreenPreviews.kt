package com.usesense.sdk.ui.compose.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.usesense.sdk.flows.FormField
import com.usesense.sdk.flows.FormFieldType

// Compose previews for the UI-parity screens, in light AND dark, so every parity
// surface is visible in the Android Studio canvas without a live flow, device, or
// camera. This lives in src/debug, so it never ships in the release AAR.
//
// Each screen wraps itself in UseSenseTheme (which reads isSystemInDarkTheme), so
// the UI_MODE_NIGHT_YES preview renders the dark palette automatically.

@Preview(name = "Light", showSystemUi = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
private annotation class LightDark

private val sampleDocTypes = listOf("Passport", "Driver's License", "National ID card")

private val sampleIdTypes = listOf(
    IdTypeOption(value = "nin", label = "NIN", hint = "Your 11-digit National ID number", field = "id_number", maxLength = 11, numeric = true),
    IdTypeOption(value = "bvn", label = "BVN", hint = "Your 11-digit Bank Verification Number", field = "id_number", maxLength = 11, numeric = true),
)

private fun sampleFormState() = FormState(
    listOf(
        FormField(key = "first_name", type = FormFieldType.TEXT, label = "First name", placeholder = "Ada"),
        FormField(key = "country", type = FormFieldType.COUNTRY, label = "Country", allowedCountries = listOf("NG", "GH", "KE")),
        FormField(key = "dob", type = FormFieldType.DATE, label = "Date of birth"),
        FormField(key = "consent", type = FormFieldType.CHECKBOX, label = "I agree to the terms"),
    ),
)

private fun sampleBitmap(): Bitmap =
    Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }

@LightDark
@Composable
private fun FacePrimerPreview() = FacePrimerScreen(onStart = {})

@LightDark
@Composable
private fun DocumentPrimerPreview() =
    DocumentPrimerScreen(onPrimary = {}, documentType = "Passport", issuingCountries = listOf("NG", "GH"), onSecondary = {})

@LightDark
@Composable
private fun DocumentTypeSelectPreview() = DocumentTypeSelectScreen(documentTypes = sampleDocTypes, onContinue = {})

@LightDark
@Composable
private fun DocumentConfirmPreview() = DocumentConfirmScreen(bitmap = sampleBitmap(), onUse = {}, onRetake = {}, onUploadInstead = {})

@LightDark
@Composable
private fun IdNumberPreview() = IdNumberScreen(idTypes = sampleIdTypes, onSubmit = { _, _, _ -> })

@LightDark
@Composable
private fun FormPreview() = FormScreen(state = sampleFormState(), onContinue = {})

@LightDark
@Composable
private fun ResultSuccessPreview() = FlowResultScreen(kind = FlowResultKind.Success, continueText = "Continue", onContinue = {})

@LightDark
@Composable
private fun ResultReviewPreview() = FlowResultScreen(kind = FlowResultKind.Review)

@LightDark
@Composable
private fun ResultNotVerifiedPreview() = FlowResultScreen(kind = FlowResultKind.NotVerified)

@LightDark
@Composable
private fun LoadingPreview() = FlowLoadingScreen()

@LightDark
@Composable
private fun ErrorPreview() = FlowErrorScreen(message = "This link is invalid or has expired.", retryText = "Try again", onRetry = {})
