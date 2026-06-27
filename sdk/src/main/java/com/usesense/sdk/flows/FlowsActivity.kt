package com.usesense.sdk.flows

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.flows.FlowError.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.usesense.sdk.ui.HostedPageActivity
import com.usesense.sdk.ui.compose.FlowAppearanceHost
import com.usesense.sdk.ui.compose.screens.DocumentConfirmScreen
import com.usesense.sdk.ui.compose.screens.DocumentPrimerScreen
import com.usesense.sdk.ui.compose.screens.DocumentTypeSelectScreen
import com.usesense.sdk.ui.compose.screens.FacePrimerScreen
import com.usesense.sdk.ui.compose.screens.FlowLoadingScreen
import com.usesense.sdk.ui.compose.screens.FormScreen
import com.usesense.sdk.ui.compose.screens.FormState
import com.usesense.sdk.ui.compose.screens.IdNumberScreen
import com.usesense.sdk.ui.compose.screens.IdTypeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Drives a Flow Run end to end. One state machine; one of four surfaces
 * rendered per parked action (face / document / form / consent). Mirrors the
 * iOS FlowsRunnerViewController and the web SDK's FlowRunner so subject UX is
 * identical across platforms.
 *
 * Face capture wiring lands in slice 5b-2: the existing UseSenseSession on
 * Android is currently `internal class`; opening a public injection seam is
 * the focused follow-up. Today the runner surfaces FlowError.UNSUPPORTED_ACTION
 * for face steps with a clear hint pointing at Sessions.
 */
internal class FlowsActivity : ComponentActivity() {
    private lateinit var options: RunFlowOptions
    private lateinit var callback: FlowsCallback
    private lateinit var client: FlowsClient

    private var view: FlowRunView? = null
    private lateinit var root: FrameLayout
    /** Branded full-screen loader (FlowLoadingScreen): between steps and during
     *  document upload. The message updates in place via loadingMessage. */
    private var loadingView: ComposeView? = null
    private val loadingMessage = mutableStateOf("Loading")
    private lateinit var content: LinearLayout
    private var facePrimerView: ComposeView? = null
    private var documentChromeView: ComposeView? = null
    private var documentConfirmView: ComposeView? = null
    private var formState: FormState? = null
    private var formChromeView: ComposeView? = null
    private var idNumberChromeView: ComposeView? = null
    private var finishedReporting = false
    /** Per-field server validation errors from the last advance(). Cleared on
     *  the next success so a recovered form does not show stale highlights. */
    private var fieldErrors: Map<String, String> = emptyMap()
    /** Has the info action's external URL been opened? Drives the primary CTA
     *  copy and the next tap's behaviour (advance vs. open). */
    private var infoOpenUrlPresented = false

    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            handlePickedDocument(result.data!!)
        } else {
            lifecycleScope.launch { cancelRun() }
        }
    }

    /** Result of the ML Kit Document Scanner (rear-camera capture, edge-detected
     *  and deskewed). The first scanned page is uploaded like a picked file. */
    private val scanDocument = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.firstOrNull()?.imageUri
            if (uri != null) confirmDocument(uri) { launchDocumentScanner() } else lifecycleScope.launch { cancelRun() }
        } else {
            lifecycleScope.launch { cancelRun() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val opts = UseSenseFlows.pendingOptions
        val cb = UseSenseFlows.pendingCallback
        if (opts == null || cb == null) {
            // Activity was recreated after a process death without going through
            // UseSenseFlows.run again; safest to just finish silently.
            finish(); return
        }
        options = opts
        callback = cb
        client = FlowsClient(options.flowRunId, options.sdkToken, options.apiBaseUrl)

        installScaffold()
        lifecycleScope.launch { refreshAndDrive() }
    }

    override fun onDestroy() {
        // Best-effort cleanup. If a successful result already fired, the
        // statics were cleared in reportSuccess/reportError; otherwise the host
        // app might re-launch with the same callback before they GC.
        super.onDestroy()
    }

    // ── White-label appearance ─────────────────────────────────────────────────

    /**
     * The merged FlowAppearance driving the runner theme, resolved fresh each
     * time a surface is rendered so it reflects the latest server branding.
     * Merge order (highest first): SDK-init (options.appearance) > server
     * (branding.appearance) > legacy primaryColor (synthesised into an
     * appearance) > built-in hosted-page tokens.
     */
    private fun mergedAppearance(): FlowAppearance? {
        val sdkInit = options.appearance
        val serverAppearance = view?.branding?.appearance
        // Legacy fallback: fold the flat primary_color into an appearance so a
        // server that only sends primaryColor still tints the runner.
        val legacy = view?.branding?.primaryColor
            ?.takeIf { it.isNotBlank() && serverAppearance?.colors?.primary == null && sdkInit?.colors?.primary == null }
            ?.let { FlowAppearance(colors = AppearanceColors(primary = it)) }
        return mergeAppearance(sdkInit, mergeAppearance(serverAppearance, legacy))
    }

    /** Wrap a Compose surface in the resolved white-label appearance. */
    private fun ComposeView.themedContent(content: @Composable () -> Unit) {
        val appearance = mergedAppearance()
        setContent { FlowAppearanceHost(appearance) { content() } }
    }

    // ── White-label copy ────────────────────────────────────────────────────────

    /**
     * The merged FlowCopy driving the runner's subject-facing strings, resolved
     * fresh each time a surface is rendered so it reflects the latest server
     * branding. Merge order (highest first): SDK-init (options.copy) > server
     * (branding.copy) > built-in defaults.
     */
    private fun mergedCopy(): FlowCopy? = mergeCopy(options.copy, view?.branding?.copy)

    // ── Driver ────────────────────────────────────────────────────────────────

    private suspend fun refreshAndDrive() {
        load()
        val v = view ?: return
        if (v.state == FlowRunState.PENDING && v.pendingAction == null) {
            advance(JSONObject())
        }
    }

    private suspend fun load() {
        showSpinner()
        try {
            val next = withContext(Dispatchers.IO) { client.get() }
            view = next
            render()
        } catch (e: FlowError) {
            reportError(e)
        } catch (e: Throwable) {
            reportError(FlowError(Code.UNKNOWN, e.message ?: "Unknown error"))
        }
    }

    private suspend fun advance(inputs: JSONObject) {
        showSpinner()
        try {
            val next = withContext(Dispatchers.IO) { client.advance(inputs) }
            view = next
            fieldErrors = emptyMap()
            render()
        } catch (e: FlowError) {
            if (e.code == Code.INVALID_INPUT) {
                // Inline per-field errors; do not terminate the run.
                fieldErrors = e.details
                render()
            } else {
                reportError(e)
            }
        } catch (e: Throwable) {
            reportError(FlowError(Code.UNKNOWN, e.message ?: "Unknown error"))
        }
    }

    private suspend fun cancelRun() {
        try {
            val next = withContext(Dispatchers.IO) { client.cancel() }
            view = next
            render()
        } catch (e: FlowError) {
            reportError(e)
        } catch (e: Throwable) {
            reportError(FlowError(Code.UNKNOWN, e.message ?: "Unknown error"))
        }
    }

    private fun render() {
        val v = view ?: return
        hideSpinner()
        if (v.state in TERMINAL_STATES) {
            reportSuccess(FlowRunResult(v.id, v.state, v.outcome))
            return
        }
        val action = v.pendingAction
        if (action == null) {
            showSpinner()
            return
        }
        when (action) {
            is PendingAction.CaptureFace -> launchFaceCapture(action.toolId)
            is PendingAction.CaptureDocument -> presentDocumentCapture(action)
            is PendingAction.CaptureForm -> installFormSurface(action.fields)
            is PendingAction.CaptureIdNumber -> presentIdNumber(action.idTypes)
            is PendingAction.Info -> installInfoSurface(action.info)
            is PendingAction.RedirectToConsent -> launchConsent(action.consentUrl)
        }
    }

    // ── Surfaces ──────────────────────────────────────────────────────────────

    /** Per-field binding holding the read function + error label so a 422
     *  invalid_input response can flip the matching error visible without
     *  rebuilding the form. */
    private data class FieldBinding(val read: () -> Any, val errorLabel: TextView)

    private fun installFormSurface(fields: List<FormField>) {
        // Re-render after a server invalid_input: refresh the existing form's
        // errors instead of re-overlaying (keeps the subject's input).
        val existing = formState
        if (existing != null) {
            existing.errors.clear()
            existing.errors.putAll(fieldErrors)
            existing.isBusy = false
            return
        }
        val state = FormState(fields, fieldErrors)
        formState = state
        val c = mergedCopy()
        val view = ComposeView(this).apply {
            themedContent {
                FormScreen(
                    state = state,
                    onContinue = { submitForm() },
                    title = text(c?.form?.title, "A few details"),
                    continueText = text(c?.buttons?.continueLabel, "Continue"),
                )
            }
        }
        formChromeView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun submitForm() {
        val state = formState ?: return
        // Client-side echo of the server validation; the server stays authoritative.
        val clientErrors = LinkedHashMap<String, String>()
        val values = JSONObject()
        for (field in state.fields) {
            val raw = state.raw(field)
            val err = validate(field, raw)
            if (err != null) clientErrors[field.key] = err else values.put(field.key, coerce(field, raw))
        }
        if (clientErrors.isNotEmpty()) {
            state.errors.clear()
            state.errors.putAll(clientErrors)
            return
        }
        state.errors.clear()
        state.isBusy = true
        lifecycleScope.launch {
            advance(values)
            // Still parked on a form -> server rejected (errors refreshed via the
            // re-render guard); otherwise the run moved on, so remove the overlay.
            if (view?.pendingAction is PendingAction.CaptureForm) {
                state.isBusy = false
            } else {
                removeFormChrome()
            }
        }
    }

    private fun presentIdNumber(idTypes: List<IdTypeSpec>) {
        val options = idTypes.map {
            IdTypeOption(
                value = it.value,
                label = it.label,
                hint = it.hint,
                field = it.field,
                maxLength = it.maxLength,
                numeric = it.numeric,
            )
        }
        val c = mergedCopy()
        val view = ComposeView(this).apply {
            themedContent {
                IdNumberScreen(
                    idTypes = options,
                    onSubmit = { idType, field, value ->
                        removeIdNumberChrome()
                        // Mirror the hosted page: advance({ id_type, [field]: value }).
                        val inputs = JSONObject().put("id_type", idType).put(field, value)
                        lifecycleScope.launch { advance(inputs) }
                    },
                    title = text(c?.idNumber?.title, "Select an option"),
                    body = text(c?.idNumber?.body, "Choose the type of ID to validate."),
                    continueText = text(c?.buttons?.continueLabel, "Continue"),
                )
            }
        }
        idNumberChromeView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun removeIdNumberChrome() {
        idNumberChromeView?.let { root.removeView(it) }
        idNumberChromeView = null
    }

    private fun removeFormChrome() {
        formChromeView?.let { root.removeView(it) }
        formChromeView = null
        formState = null
    }

    private fun addFieldRow(field: FormField, serverError: String?): FieldBinding {
        val labelText = (field.label ?: humanise(field.key)) + if (field.required) " *" else ""
        val label = TextView(this).apply {
            text = labelText
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        content.addView(label)

        val read: () -> Any
        when (field.type) {
            FormFieldType.SELECT, FormFieldType.COUNTRY -> {
                val items: List<Pair<String, String>> = if (field.type == FormFieldType.COUNTRY)
                    (field.allowedCountries ?: emptyList()).map { it to it }
                else
                    (field.options ?: emptyList()).map { it.value to it.label }
                val labels = listOf(field.placeholder ?: "Select…") + items.map { it.second }
                val spinner = Spinner(this).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
                }
                content.addView(spinner)
                val values = listOf("") + items.map { it.first }
                (field.initial as? String)?.let { init ->
                    val idx = items.indexOfFirst { it.first == init }
                    if (idx >= 0) spinner.setSelection(idx + 1)
                }
                read = { values.getOrElse(spinner.selectedItemPosition) { "" } }
            }
            FormFieldType.CHECKBOX -> {
                val cb = CheckBox(this).apply {
                    text = field.hint ?: ""
                    isChecked = (field.initial as? Boolean) ?: false
                }
                content.addView(cb)
                read = { cb.isChecked }
            }
            else -> {
                val edit = EditText(this).apply {
                    inputType = when (field.type) {
                        FormFieldType.EMAIL  -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        FormFieldType.TEL    -> InputType.TYPE_CLASS_PHONE
                        FormFieldType.NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        FormFieldType.DATE   -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                        else                 -> InputType.TYPE_CLASS_TEXT
                    }
                    hint = field.placeholder ?: ""
                    (field.initial as? String)?.let { setText(it) }
                    field.validators?.maxLength?.let { max ->
                        filters = arrayOf(android.text.InputFilter.LengthFilter(max))
                    }
                }
                content.addView(edit)
                read = { edit.text.toString() }
            }
        }

        if (!field.hint.isNullOrEmpty() && field.type != FormFieldType.CHECKBOX) {
            content.addView(TextView(this).apply {
                text = field.hint
                textSize = 12f
                setPadding(0, 4, 0, 0)
                setTextColor(Color.parseColor("#888888"))
            })
        }

        val errorLabel = TextView(this).apply {
            textSize = 12f
            setPadding(0, 4, 0, 0)
            setTextColor(Color.parseColor("#DC2626"))
            text = serverError ?: ""
            visibility = if (serverError != null) View.VISIBLE else View.GONE
        }
        content.addView(errorLabel)
        return FieldBinding(read, errorLabel)
    }

    private fun validate(field: FormField, raw: Any): String? {
        val v = field.validators
        val isBlank = (raw as? String)?.trim().isNullOrEmpty()
        if (isBlank) return if (field.required) "${field.label ?: humanise(field.key)} is required" else null
        val s = raw as? String
        if (s != null && v != null) {
            v.pattern?.let { pattern ->
                try {
                    if (!Regex(pattern).containsMatchIn(s)) {
                        return v.errorMessage ?: "${field.label ?: humanise(field.key)} is not in the expected format"
                    }
                } catch (_: Throwable) { /* trust server on bad pattern */ }
            }
            v.minLength?.let { if (s.length < it) return v.errorMessage ?: "Must be at least $it characters" }
            v.maxLength?.let { if (s.length > it) return v.errorMessage ?: "Must be at most $it characters" }
        }
        if (field.type == FormFieldType.NUMBER && s != null) {
            val n = s.toDoubleOrNull()
                ?: return v?.errorMessage ?: "${field.label ?: humanise(field.key)} must be a number"
            v?.minNumber?.let { if (n < it) return v.errorMessage ?: "Must be at least $it" }
            v?.maxNumber?.let { if (n > it) return v.errorMessage ?: "Must be at most $it" }
        }
        if (field.type == FormFieldType.DATE && s != null) {
            v?.minString?.let { if (s < it) return v.errorMessage ?: "Must be on or after $it" }
            v?.maxString?.let { if (s > it) return v.errorMessage ?: "Must be on or before $it" }
        }
        return null
    }

    private fun coerce(field: FormField, raw: Any): Any = when (field.type) {
        FormFieldType.CHECKBOX -> (raw as? Boolean) ?: false
        FormFieldType.NUMBER -> (raw as? String)?.toDoubleOrNull() ?: raw
        else -> raw
    }

    private fun installInfoSurface(info: InfoAction) {
        content.removeAllViews()
        val title = TextView(this).apply {
            text = info.title
            textSize = 22f
            setPadding(0, 0, 0, 12)
        }
        content.addView(title)
        if (!info.body.isNullOrEmpty()) {
            content.addView(TextView(this).apply {
                text = info.body
                textSize = 14f
                setPadding(0, 0, 0, 16)
                setTextColor(Color.parseColor("#555555"))
            })
        }
        for (b in info.bullets) {
            content.addView(TextView(this).apply {
                text = "${bulletGlyph(b.icon)}   ${b.text}"
                textSize = 14f
                setPadding(0, 8, 0, 0)
            })
        }
        val primaryLabel = if (infoOpenUrlPresented && info.primary.openUrl != null) "I'm back, continue" else info.primary.label
        val primaryBtn = Button(this).apply {
            text = primaryLabel
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                // Open the external URL via ACTION_VIEW first; the next tap
                // advances. Custom Tabs would be smoother but adds a dep —
                // keep the SDK dependency-light for v1.
                val url = info.primary.openUrl
                if (url != null && !infoOpenUrlPresented) {
                    infoOpenUrlPresented = true
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    // Refresh the button copy when the subject returns.
                    text = "I'm back, continue"
                    return@setOnClickListener
                }
                infoOpenUrlPresented = false
                lifecycleScope.launch { advance(JSONObject()) }
            }
        }
        content.addView(primaryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 24 })
        info.secondary?.let { secondary ->
            content.addView(Button(this).apply {
                text = secondary.label
                setPadding(24, 16, 24, 16)
                setOnClickListener {
                    when (secondary.action) {
                        InfoSecondaryCta.Action.CANCEL -> lifecycleScope.launch { cancelRun() }
                        InfoSecondaryCta.Action.ADVANCE -> lifecycleScope.launch { advance(JSONObject()) }
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12 })
        }
    }

    private fun bulletGlyph(icon: InfoBulletIcon?): String = when (icon) {
        // Single-char glyphs keep the SDK icon-library-free. Unknown icons fall
        // back to the default info dot per the contract — never block.
        InfoBulletIcon.CHECK -> "✓"
        InfoBulletIcon.SHIELD -> "⛨"
        InfoBulletIcon.CAMERA -> "📷"
        InfoBulletIcon.WARNING -> "!"
        InfoBulletIcon.INFO, null -> "i"
    }

    /**
     * Initialise a session via /init-session, then launch HostedPageActivity
     * with the pre-minted credentials. On success the bridged callback runs
     * on the main thread (HostedPageActivity itself dispatches that way),
     * extracts sessionId + identityId from UseSenseResult, and advances.
     *
     * Cancellation closes the capture and cancels the run (cancel webhook
     * fires server-side so the customer's backend sees a definite end).
     */
    private fun launchFaceCapture(toolId: String?) {
        // Hosted parity: show the face primer first ("Take a selfie" + the do's),
        // then mint the capture session on the CTA and hand off to the existing
        // capture UI. The CTA shows progress while init-session is in flight.
        val busy = mutableStateOf(false)
        val c = mergedCopy()
        val primer = ComposeView(this).apply {
            themedContent {
                FacePrimerScreen(
                    onStart = {
                        busy.value = true
                        beginFaceCapture(toolId)
                    },
                    isBusy = busy.value,
                    title = text(c?.face?.title, "Take a selfie"),
                    body = text(c?.face?.body, "A quick, secure face scan confirms you're a real, live person."),
                    startText = text(c?.face?.start, "Start face scan"),
                )
            }
        }
        facePrimerView = primer
        root.addView(
            primer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun removeFacePrimer() {
        facePrimerView?.let { root.removeView(it) }
        facePrimerView = null
    }

    private fun beginFaceCapture(toolId: String?) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { client.initSession(toolId) }
                val endpoint = options.apiBaseUrl.trimEnd('/') + "/v1"
                // Placeholder apiKey: every downstream call from the capture
                // engine authenticates with the session token / nonce set by
                // injectHostedSessionData; the api key is never read.
                val config = UseSenseConfig(apiKey = "flow_runner", baseUrl = endpoint)
                val bridge = object : UseSenseCallback {
                    override fun onSuccess(result: UseSenseResult) {
                        val inputs = JSONObject().put("sessionId", result.sessionId)
                        result.identityId?.let { inputs.put("identityId", it) }
                        lifecycleScope.launch { advance(inputs) }
                    }

                    override fun onError(error: UseSenseError) {
                        reportError(FlowError(
                            Code.PROVIDER_UNAVAILABLE,
                            error.message ?: text(mergedCopy()?.errors?.providerUnavailable, "Face capture failed"),
                        ))
                    }

                    override fun onCancelled() {
                        lifecycleScope.launch { cancelRun() }
                    }
                }
                HostedPageActivity.startWithPrebuiltSession(
                    context = this@FlowsActivity,
                    config = config,
                    response = response,
                    sessionType = SessionType.ENROLLMENT,
                    callback = bridge,
                )
                removeFacePrimer()
            } catch (e: FlowError) {
                removeFacePrimer()
                reportError(e)
            } catch (e: Throwable) {
                removeFacePrimer()
                reportError(FlowError(Code.UNKNOWN, e.message ?: "Unknown error"))
            }
        }
    }

    /** Offer the subject every method the operator allows. Default is both:
     *  rear-camera scan (ML Kit) and file upload. */
    private fun presentDocumentCapture(action: PendingAction.CaptureDocument) {
        val methods = action.captureMethods
        val canScan = methods.isEmpty() || methods.contains("camera")
        val canUpload = methods.isEmpty() || methods.contains("upload")
        val c = mergedCopy()

        fun showPrimer(docType: String?) {
            val primer = ComposeView(this).apply {
                themedContent {
                    DocumentPrimerScreen(
                        onPrimary = {
                            removeDocumentChrome()
                            if (canScan) launchDocumentScanner() else launchDocumentPicker()
                        },
                        documentType = docType,
                        categoryLabel = documentCategoryLabel(action.category),
                        issuingCountries = action.issuingCountries,
                        allowCamera = canScan,
                        allowUpload = canUpload,
                        onSecondary = if (canScan && canUpload) {
                            { removeDocumentChrome(); launchDocumentPicker() }
                        } else {
                            null
                        },
                        // primerTitle is optional: null keeps the computed
                        // "Get your <type> ready" default in the screen.
                        title = c?.document?.primerTitle?.takeIf { it.isNotBlank() },
                        body = text(c?.document?.primerBody, "We'll capture it and check it's clear and readable."),
                        scanText = text(c?.buttons?.scan, "Take a photo"),
                        uploadText = text(c?.buttons?.upload, "Upload a file"),
                        uploadInsteadText = text(c?.buttons?.uploadInstead, "Upload a file instead"),
                    )
                }
            }
            replaceDocumentChrome(primer)
        }

        if (action.documentTypes.isNotEmpty()) {
            val typeView = ComposeView(this).apply {
                themedContent {
                    DocumentTypeSelectScreen(
                        documentTypes = action.documentTypes,
                        onContinue = { selected -> showPrimer(selected) },
                        title = text(c?.document?.selectTitle, "Pick a document"),
                        body = text(c?.document?.selectBody, "Choose a document to verify your identity. We don't accept scans or copies."),
                        continueText = text(c?.buttons?.continueLabel, "Continue"),
                    )
                }
            }
            replaceDocumentChrome(typeView)
        } else {
            showPrimer(null)
        }
    }

    /** Hosted parity: the document chrome (type chooser + primer) is shown as a
     *  Compose overlay; the primer's CTAs are the method choice, then the existing
     *  ML Kit scan / file picker runs. */
    private fun replaceDocumentChrome(view: ComposeView) {
        removeDocumentChrome()
        documentChromeView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun removeDocumentChrome() {
        documentChromeView?.let { root.removeView(it) }
        documentChromeView = null
    }

    private fun documentCategoryLabel(category: String): String = when (category) {
        "identity" -> "identity document"
        "proof_of_address" -> "proof of address"
        "organisation_doc" -> "organisation document"
        "tax_doc" -> "tax document"
        "invoice" -> "invoice"
        else -> "document"
    }

    /** Rear-camera document scan via ML Kit (edge detect, deskew, glare/finger
     *  detection). Falls back to the file picker if the scanner is unavailable. */
    private fun launchDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .setGalleryImportAllowed(false)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scanDocument.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                // Scanner unavailable (e.g. no Play Services): fall back to upload.
                launchDocumentPicker()
            }
    }

    private fun launchDocumentPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickDocument.launch(Intent.createChooser(intent, "Choose a document"))
    }

    private fun handlePickedDocument(data: Intent) {
        val uri = data.data ?: return
        confirmDocument(uri) { launchDocumentPicker() }
    }

    /** Hosted parity: confirm the captured document (preview + Use / Retake)
     *  before uploading. Falls back to a direct upload if the preview can't be
     *  decoded. */
    private fun confirmDocument(uri: Uri, retake: () -> Unit) {
        val bitmap = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            null
        }
        if (bitmap == null) {
            uploadDocumentUri(uri)
            return
        }
        val c = mergedCopy()
        val confirmView = ComposeView(this).apply {
            themedContent {
                DocumentConfirmScreen(
                    bitmap = bitmap,
                    onUse = { removeDocumentConfirm(); uploadDocumentUri(uri) },
                    onRetake = { removeDocumentConfirm(); retake() },
                    onUploadInstead = { removeDocumentConfirm(); launchDocumentPicker() },
                    title = text(c?.document?.confirmTitle, "Check your document is clear"),
                    retakeText = text(c?.buttons?.retake, "Retake"),
                    useThisPhotoText = text(c?.buttons?.useThisPhoto, "Use this photo"),
                    uploadInsteadText = text(c?.buttons?.uploadInstead, "Upload a different file"),
                    checkingQualityText = text(c?.loading?.checkingQuality, "Checking image quality…"),
                )
            }
        }
        documentConfirmView = confirmView
        root.addView(
            confirmView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun removeDocumentConfirm() {
        documentConfirmView?.let { root.removeView(it) }
        documentConfirmView = null
    }

    /** Upload a document (picked or scanned) and advance the run. */
    private fun uploadDocumentUri(uri: Uri) {
        // Branded upload loader (mirrors the hosted page + iOS) so the subject
        // sees progress instead of a bare spinner during the POST.
        val c = mergedCopy()
        showSpinner(text(c?.loading?.submittingDocument, "Submitting your document…"))
        lifecycleScope.launch {
            try {
                val base64 = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Failed to open document" }
                        val bytes = input.readBytes()
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
                val pending = view?.pendingAction as? PendingAction.CaptureDocument
                val docType = pending?.category ?: "identity"
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val response = withContext(Dispatchers.IO) {
                    client.uploadDocument(data = base64, mimeType = mime, side = "single", documentType = docType)
                }
                if (response.status == "failed") {
                    val code = if (response.reason == "provider") Code.PROVIDER_UNAVAILABLE else Code.UNKNOWN
                    val message = if (response.reason == "provider")
                        text(c?.errors?.providerUnavailable, "Verification is temporarily unavailable.")
                    else
                        text(c?.errors?.documentUnreadable, "We couldn't read that document. Please retake it.")
                    reportError(FlowError(code, message))
                    return@launch
                }
                advance(JSONObject().put("document_id", response.documentId))
            } catch (e: FlowError) {
                reportError(e)
            } catch (e: Throwable) {
                reportError(FlowError(Code.UNKNOWN, e.message ?: "Unknown error"))
            }
        }
    }

    private fun launchConsent(consentUrl: String) {
        content.removeAllViews()
        val title = TextView(this).apply {
            text = "Consent required"
            textSize = 22f
            setPadding(0, 0, 0, 12)
        }
        val sub = TextView(this).apply {
            text = "Open the secure consent page, grant consent, then come back and continue."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        val openBtn = Button(this).apply {
            text = "Open consent page"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consentUrl))
                startActivity(intent)
            }
        }
        val continueBtn = Button(this).apply {
            text = "I've granted consent"
            setOnClickListener { lifecycleScope.launch { advance(JSONObject()) } }
        }
        content.addView(title)
        content.addView(sub)
        content.addView(openBtn)
        content.addView(continueBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 16 })
    }

    // ── Scaffold ──────────────────────────────────────────────────────────────

    private fun installScaffold() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48)
        }
        val loader = ComposeView(this).apply {
            visibility = View.GONE
            // SDK-init appearance only here (server branding may not have loaded
            // yet); the per-step surfaces re-resolve with server branding folded in.
            themedContent { FlowLoadingScreen(title = loadingMessage.value) }
        }
        loadingView = loader
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(loader, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
    }

    /** Show the branded loader. With no explicit message the generic loading
     *  title is used, honouring the merged-copy `loading.default` override. */
    private fun showSpinner(message: String? = null) {
        loadingMessage.value = message ?: text(mergedCopy()?.loading?.default, "Loading")
        loadingView?.visibility = View.VISIBLE
    }

    private fun hideSpinner() {
        loadingView?.visibility = View.GONE
    }

    private fun reportSuccess(result: FlowRunResult) {
        if (finishedReporting) return
        finishedReporting = true
        UseSenseFlows.pendingOptions = null
        UseSenseFlows.pendingCallback = null
        callback.onResult(result)
        finish()
    }

    private fun reportError(error: FlowError) {
        if (finishedReporting) return
        finishedReporting = true
        UseSenseFlows.pendingOptions = null
        UseSenseFlows.pendingCallback = null
        callback.onError(error)
        finish()
    }

    companion object {
        private val TERMINAL_STATES = setOf(
            FlowRunState.COMPLETED, FlowRunState.ERRORED, FlowRunState.CANCELLED, FlowRunState.ABANDONED,
        )
    }
}

private fun humanise(s: String): String =
    s.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
