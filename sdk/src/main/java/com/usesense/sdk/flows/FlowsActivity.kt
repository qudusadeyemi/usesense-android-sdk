package com.usesense.sdk.flows

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.usesense.sdk.flows.FlowError.Code
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
    private lateinit var spinner: ProgressBar
    private lateinit var content: LinearLayout
    private var finishedReporting = false

    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            handlePickedDocument(result.data!!)
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
            render()
        } catch (e: FlowError) {
            reportError(e)
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
            is PendingAction.CaptureFace -> reportError(FlowError(
                Code.UNSUPPORTED_ACTION,
                "Face capture in Flows lands in slice 5b-2; until then use Sessions (UseSense.startVerification) for face capture."
            ))
            is PendingAction.CaptureDocument -> launchDocumentPicker()
            is PendingAction.CaptureForm -> installFormSurface(action.fields)
            is PendingAction.RedirectToConsent -> launchConsent(action.consentUrl)
        }
    }

    // ── Surfaces ──────────────────────────────────────────────────────────────

    private fun installFormSurface(fields: List<String>) {
        content.removeAllViews()
        val title = TextView(this).apply {
            text = "A few details"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        content.addView(title)
        val inputs = mutableMapOf<String, EditText>()
        for (field in fields) {
            val label = TextView(this).apply {
                text = humanise(field)
                textSize = 14f
                setPadding(0, 12, 0, 4)
            }
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = humanise(field)
            }
            inputs[field] = edit
            content.addView(label)
            content.addView(edit)
        }
        val submit = Button(this).apply {
            text = "Continue"
            setOnClickListener {
                val values = JSONObject()
                inputs.forEach { (k, e) -> values.put(k, e.text.toString()) }
                lifecycleScope.launch { advance(values) }
            }
            setPadding(24, 16, 24, 16)
        }
        content.addView(submit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 32 })
    }

    private fun launchDocumentPicker() {
        // ACTION_GET_CONTENT covers gallery + file picker; many devices route
        // it to the camera too. A future enhancement is a camera-first
        // intent with capture-quality framing; for v1 this is enough.
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickDocument.launch(Intent.createChooser(intent, "Choose a document"))
    }

    private fun handlePickedDocument(data: Intent) {
        val uri = data.data ?: return
        showSpinner()
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
                        "Verification is temporarily unavailable."
                    else
                        "We couldn't read that document. Please retake it."
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
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        val spinnerWrap = FrameLayout(this).apply {
            addView(spinner, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(spinnerWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
    }

    private fun showSpinner() {
        spinner.visibility = View.VISIBLE
    }

    private fun hideSpinner() {
        spinner.visibility = View.GONE
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
