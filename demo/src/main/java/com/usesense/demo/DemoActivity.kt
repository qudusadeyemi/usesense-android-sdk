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
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultText: TextView

    private var mockServer: MockUseSenseServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        bindViews()
        setupListeners()
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
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
    }

    private fun setupListeners() {
        // Toggle mock vs sandbox controls
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isMock = checkedId == R.id.modeMock
            apiKeyLayout.visibility = if (isMock) View.GONE else View.VISIBLE
            challengeCard.visibility = if (isMock) View.VISIBLE else View.GONE
            verdictCard.visibility = if (isMock) View.VISIBLE else View.GONE
        }

        // Show identity ID field for authentication
        sessionTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            identityIdLayout.visibility =
                if (checkedId == R.id.typeAuthentication) View.VISIBLE else View.GONE
        }

        startButton.setOnClickListener { startVerification() }
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

    private fun showResult(result: UseSenseResult) {
        resultCard.visibility = View.VISIBLE
        resultText.text = buildString {
            appendLine("Decision:  ${result.decision}")
            appendLine("Session:   ${result.sessionId}")
            appendLine("Identity:  ${result.identityId ?: "N/A"}")
            appendLine("Type:      ${result.sessionType}")
            appendLine("---")
            appendLine("Channel Trust: ${result.channelTrustScore}")
            appendLine("Liveness:      ${result.livenessScore}")
            appendLine("Dedupe Risk:   ${result.dedupeRiskScore}")
            appendLine("---")
            appendLine("Reasons:")
            result.reasons.forEach { appendLine("  - $it") }
            appendLine("---")
            appendLine("Signature: ${result.signature.take(40)}...")
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

    override fun onDestroy() {
        super.onDestroy()
        mockServer?.stop()
        UseSense.reset()
    }
}
