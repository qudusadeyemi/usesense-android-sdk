package com.usesense.demo

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.usesense.sdk.*
import com.usesense.sdk.flows.FlowError
import com.usesense.sdk.flows.FlowRunResult
import com.usesense.sdk.flows.FlowsCallback
import com.usesense.sdk.flows.UseSenseFlows
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DemoActivity : AppCompatActivity() {

    private lateinit var modeGroup: RadioGroup
    private lateinit var apiKeyLayout: TextInputLayout
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var sessionTypeGroup: RadioGroup
    private lateinit var identityIdLayout: TextInputLayout
    private lateinit var identityIdInput: TextInputEditText
    private lateinit var challengeCard: MaterialCardView
    private lateinit var challengeGroup: RadioGroup
    private lateinit var verdictCard: MaterialCardView
    private lateinit var verdictGroup: RadioGroup
    private lateinit var startButton: MaterialButton
    private lateinit var flowIdLayout: TextInputLayout
    private lateinit var flowIdInput: TextInputEditText
    private lateinit var runFlowButton: MaterialButton
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultText: TextView

    private var mockServer: MockUseSenseServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        bindViews()
        setupListeners()
        // The API key and flow ID are typed at runtime and remembered here.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString(PREF_API_KEY, ""))
        flowIdInput.setText(prefs.getString(PREF_FLOW_ID, ""))
    }

    private fun bindViews() {
        modeGroup = findViewById(R.id.modeGroup)
        apiKeyLayout = findViewById(R.id.apiKeyLayout)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        sessionTypeGroup = findViewById(R.id.sessionTypeGroup)
        identityIdLayout = findViewById(R.id.identityIdLayout)
        identityIdInput = findViewById(R.id.identityIdInput)
        challengeCard = findViewById(R.id.challengeCard)
        challengeGroup = findViewById(R.id.challengeGroup)
        verdictCard = findViewById(R.id.verdictCard)
        verdictGroup = findViewById(R.id.verdictGroup)
        startButton = findViewById(R.id.startButton)
        flowIdLayout = findViewById(R.id.flowIdLayout)
        flowIdInput = findViewById(R.id.flowIdInput)
        runFlowButton = findViewById(R.id.runFlowButton)
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
    }

    private fun setupListeners() {
        // Toggle mock vs sandbox controls
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isMock = checkedId == R.id.modeMock
            apiKeyLayout.visibility = if (isMock) View.GONE else View.VISIBLE
            flowIdLayout.visibility = if (isMock) View.GONE else View.VISIBLE
            runFlowButton.visibility = if (isMock) View.GONE else View.VISIBLE
            challengeCard.visibility = if (isMock) View.VISIBLE else View.GONE
            verdictCard.visibility = if (isMock) View.VISIBLE else View.GONE
        }

        // Show identity ID field for authentication
        sessionTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            identityIdLayout.visibility =
                if (checkedId == R.id.typeAuthentication) View.VISIBLE else View.GONE
        }

        startButton.setOnClickListener { startVerification() }
        runFlowButton.setOnClickListener { runFlow() }
    }

    private fun startVerification() {
        val isMockMode = modeGroup.checkedRadioButtonId == R.id.modeMock

        val baseUrl: String
        val apiKey: String

        if (isMockMode) {
            // Start mock server
            mockServer?.stop()
            mockServer = MockUseSenseServer().apply {
                challengeType = when (challengeGroup.checkedRadioButtonId) {
                    R.id.challengeFollowDot -> "follow_dot"
                    R.id.challengeHeadTurn -> "head_turn"
                    R.id.challengeSpeakPhrase -> "speak_phrase"
                    R.id.challengePassive -> "none"
                    else -> "follow_dot"
                }
                mockDecision = when (verdictGroup.checkedRadioButtonId) {
                    R.id.verdictApprove -> "APPROVE"
                    R.id.verdictReject -> "REJECT"
                    R.id.verdictManualReview -> "MANUAL_REVIEW"
                    else -> "APPROVE"
                }
                requireAudio = challengeType == "speak_phrase"
                start()
            }
            baseUrl = mockServer!!.baseUrl
            apiKey = "sk_test_demo_mock_key"
        } else {
            val key = apiKeyInput.text?.toString()?.trim()
            if (key.isNullOrBlank()) {
                Toast.makeText(this, "Enter an API key for sandbox mode", Toast.LENGTH_SHORT).show()
                return
            }
            baseUrl = UseSenseConfig.DEFAULT_BASE_URL
            apiKey = key
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_API_KEY, key).apply()
        }

        // Initialize SDK
        UseSense.initialize(
            context = applicationContext,
            config = UseSenseConfig(
                apiKey = apiKey,
                environment = UseSenseEnvironment.SANDBOX,
                baseUrl = baseUrl,
            ),
        )

        val sessionType = if (sessionTypeGroup.checkedRadioButtonId == R.id.typeAuthentication) {
            SessionType.AUTHENTICATION
        } else {
            SessionType.ENROLLMENT
        }

        val identityId = if (sessionType == SessionType.AUTHENTICATION) {
            identityIdInput.text?.toString()?.trim()?.ifBlank { null }
                ?: run {
                    Toast.makeText(this, "Enter an identity ID for authentication", Toast.LENGTH_SHORT).show()
                    return
                }
        } else null

        // Subscribe to SDK events for debug logging
        UseSense.onEvent { event ->
            runOnUiThread {
                val logLine = "[${event.type}] ${event.data ?: ""}"
                val current = resultText.text?.toString() ?: ""
                if (resultCard.visibility == View.VISIBLE && current.startsWith("[")) {
                    resultText.text = "$current\n$logLine"
                }
            }
        }

        // Start verification
        UseSense.startVerification(
            activity = this,
            request = VerificationRequest(
                sessionType = sessionType,
                externalUserId = "demo_user_001",
                identityId = identityId,
                metadata = mapOf("source" to "demo_app"),
            ),
            callback = object : UseSenseCallback {
                override fun onSuccess(result: UseSenseResult) {
                    runOnUiThread { showResult(result) }
                }

                override fun onError(error: UseSenseError) {
                    runOnUiThread { showError(error) }
                }

                override fun onCancelled() {
                    runOnUiThread {
                        Toast.makeText(this@DemoActivity, "Verification cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    /**
     * Run an operator-authored flow. A host backend would create the run; the
     * demo does it inline with the API key (POST /v1/flow-runs, minting an SDK
     * token) and hands the run to the Flows runner, as the Flutter example does.
     */
    private fun runFlow() {
        val key = apiKeyInput.text?.toString()?.trim().orEmpty()
        val flowId = flowIdInput.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) { toast("Enter your API key to run a flow"); return }
        if (flowId.isEmpty()) { toast("Enter a flow ID (flw_...)"); return }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_API_KEY, key).putString(PREF_FLOW_ID, flowId).apply()
        runFlowButton.isEnabled = false
        Thread {
            val created = runCatching {
                val body = JSONObject()
                    .put("flowId", flowId)
                    .put("subject", JSONObject().put("externalRef", "android-demo-user"))
                    .put("mint_sdk_token", true)
                    .put("sdk_version", "android-demo")
                    .toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${UseSenseConfig.DEFAULT_BASE_URL.trimEnd('/')}/v1/flow-runs")
                    .header("x-api-key", key)
                    .header("x-environment", "sandbox")
                    .post(body)
                    .build()
                OkHttpClient().newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "Could not create flow run (HTTP ${resp.code}): ${text.take(300)}" }
                    val json = JSONObject(text)
                    val runId = json.getJSONObject("flowRun").getString("id")
                    val token = json.optString("sdkToken").takeIf { it.isNotEmpty() }
                        ?: error("No sdkToken returned (mint_sdk_token)")
                    runId to token
                }
            }
            runOnUiThread {
                runFlowButton.isEnabled = true
                created.fold(
                    onSuccess = { (runId, token) -> launchFlow(runId, token) },
                    onFailure = { showFlowText("FLOW ERROR\n${it.message}") },
                )
            }
        }.start()
    }

    private fun launchFlow(runId: String, token: String) {
        UseSenseFlows.run(
            activity = this,
            flowRunId = runId,
            sdkToken = token,
            callback = object : FlowsCallback {
                override fun onResult(result: FlowRunResult) = runOnUiThread {
                    showFlowText("Flow:    ${result.flowRunId}\nState:   ${result.state}\nOutcome: ${result.outcome ?: "N/A"}")
                }

                override fun onError(error: FlowError) = runOnUiThread {
                    showFlowText("FLOW ERROR\nRun:     $runId\nCode:    ${error.code}\nServer:  ${error.serverCode ?: "N/A"}\nMessage: ${error.message}")
                }
            },
        )
    }

    private fun showFlowText(text: String) {
        resultCard.visibility = View.VISIBLE
        resultText.text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun showResult(result: UseSenseResult) {
        resultCard.visibility = View.VISIBLE
        resultText.text = buildString {
            appendLine("Decision:  ${result.decision}")
            appendLine("Session:   ${result.sessionId}")
            appendLine("Identity:  ${result.identityId ?: "N/A"}")
            appendLine("Type:      ${result.sessionType ?: "N/A"}")
            appendLine("Timestamp: ${result.timestamp}")
        }
    }

    private fun showError(error: UseSenseError) {
        resultCard.visibility = View.VISIBLE
        resultText.text = buildString {
            appendLine("ERROR")
            appendLine("Code:    ${error.code}")
            appendLine("Server:  ${error.serverCode ?: "N/A"}")
            appendLine("Message: ${error.message}")
            appendLine("Retry:   ${error.isRetryable}")
        }
    }

    private companion object {
        const val PREFS = "demo"
        const val PREF_API_KEY = "api_key"
        const val PREF_FLOW_ID = "flow_id"
    }

    override fun onDestroy() {
        super.onDestroy()
        mockServer?.stop()
        UseSense.reset()
    }
}
