package com.usesense.sdk.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.usesense.sdk.*
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.*
import kotlinx.coroutines.*

/**
 * Hosted Page Activity for remote enrollment and verification flows (Sections 10-12).
 *
 * This Activity handles both:
 *   - Remote Enrollment: /remote-enrollment/{id}/data -> introduction -> capture -> result
 *   - Remote Verification: /remote-session/{id}/data -> action-review -> capture -> result
 *
 * It uses the existing capture engine (via UseSenseActivity) for the camera/challenge phase,
 * and manages the surrounding flow (data fetch, branding, introduction/review, finalizing, result).
 */
class HostedPageActivity : AppCompatActivity() {

    // Flow config
    private var flowType: FlowType = FlowType.VERIFICATION
    private var remoteId: String = ""
    private var isDirectMode: Boolean = false
    private lateinit var apiClient: UseSenseApiClient

    // Server data
    private var orgBranding: OrgBranding? = null
    private var effectiveBranding: EffectiveBranding = EffectiveBranding()
    private var actionContext: ActionContext? = null
    private var sessionData: HostedInitSessionResponse? = null

    // Screen views
    private lateinit var headerLogo: ImageView
    private lateinit var headerText: TextView
    private lateinit var loadingScreen: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var errorScreen: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var introScreen: ScrollView
    private lateinit var introHeadline: TextView
    private lateinit var introDescription: TextView
    private lateinit var introIconCircle: FrameLayout
    private lateinit var introGetStartedButton: MaterialButton
    private lateinit var actionReviewScreen: ScrollView
    private lateinit var actionReviewHeadline: TextView
    private lateinit var actionReviewDescription: TextView
    private lateinit var actionContextCard: LinearLayout
    private lateinit var actionContextText: TextView
    private lateinit var riskTierBadge: TextView
    private lateinit var actionReviewIconCircle: FrameLayout
    private lateinit var actionReviewVerifyButton: MaterialButton
    private lateinit var actionReviewDisputeButton: MaterialButton
    private lateinit var captureContainer: FrameLayout
    private lateinit var finalizingScreen: LinearLayout
    private lateinit var finalizingTitle: TextView
    private lateinit var resultScreen: ScrollView
    private lateinit var resultIconCircle: FrameLayout
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var resultBody: TextView
    private lateinit var resultActionBox: LinearLayout
    private lateinit var resultActionText: TextView
    private lateinit var resultButton: MaterialButton
    private lateinit var footerText: TextView

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    enum class FlowType { ENROLLMENT, VERIFICATION }

    enum class PageStep { LOADING, ERROR, INTRODUCTION, ACTION_REVIEW, CAPTURE, FINALIZING, RESULT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usesense_hosted)
        bindViews()

        val config = pendingConfig ?: run {
            showError("SDK not initialized")
            return
        }

        flowType = if (intent.getStringExtra(EXTRA_FLOW_TYPE) == "enrollment") {
            FlowType.ENROLLMENT
        } else {
            FlowType.VERIFICATION
        }
        isDirectMode = intent.getBooleanExtra(EXTRA_DIRECT_MODE, false)
        remoteId = intent.getStringExtra(EXTRA_REMOTE_ID) ?: ""

        if (!isDirectMode && remoteId.isEmpty()) {
            showError("No remote session ID provided")
            return
        }

        apiClient = UseSenseApiClient(config)

        // Merge SDK branding
        effectiveBranding = EffectiveBranding.merge(config.branding, null)
        applyBranding()

        if (isDirectMode) {
            // Direct SDK flow: skip remote data loading, go straight to intro/review
            when (flowType) {
                FlowType.ENROLLMENT -> showIntroduction()
                FlowType.VERIFICATION -> showActionReview()
            }
        } else {
            mainScope.launch { loadData() }
        }
    }

    private fun bindViews() {
        headerLogo = findViewById(R.id.headerLogo)
        headerText = findViewById(R.id.headerText)
        loadingScreen = findViewById(R.id.hostedLoadingScreen)
        loadingText = findViewById(R.id.hostedLoadingText)
        errorScreen = findViewById(R.id.hostedErrorScreen)
        errorMessage = findViewById(R.id.hostedErrorMessage)
        introScreen = findViewById(R.id.hostedIntroScreen)
        introHeadline = findViewById(R.id.introHeadline)
        introDescription = findViewById(R.id.introDescription)
        introIconCircle = findViewById(R.id.introIconCircle)
        introGetStartedButton = findViewById(R.id.introGetStartedButton)
        actionReviewScreen = findViewById(R.id.hostedActionReviewScreen)
        actionReviewHeadline = findViewById(R.id.actionReviewHeadline)
        actionReviewDescription = findViewById(R.id.actionReviewDescription)
        actionContextCard = findViewById(R.id.actionContextCard)
        actionContextText = findViewById(R.id.actionContextText)
        riskTierBadge = findViewById(R.id.riskTierBadge)
        actionReviewIconCircle = findViewById(R.id.actionReviewIconCircle)
        actionReviewVerifyButton = findViewById(R.id.actionReviewVerifyButton)
        actionReviewDisputeButton = findViewById(R.id.actionReviewDisputeButton)
        captureContainer = findViewById(R.id.hostedCaptureContainer)
        finalizingScreen = findViewById(R.id.hostedFinalizingScreen)
        finalizingTitle = findViewById(R.id.finalizingTitle)
        resultScreen = findViewById(R.id.hostedResultScreen)
        resultIconCircle = findViewById(R.id.resultIconCircle)
        resultIcon = findViewById(R.id.resultIcon)
        resultTitle = findViewById(R.id.resultTitle)
        resultBody = findViewById(R.id.resultBody)
        resultActionBox = findViewById(R.id.resultActionBox)
        resultActionText = findViewById(R.id.resultActionText)
        resultButton = findViewById(R.id.resultButton)
        footerText = findViewById(R.id.footerText)
    }

    private fun applyBranding() {
        val branding = effectiveBranding

        // Header: logo or text
        if (branding.logoUrl != null) {
            headerLogo.visibility = View.VISIBLE
            headerText.visibility = View.GONE
            // Load logo URL (simple image loading)
            // For production, use Coil/Glide. Here use basic approach.
            mainScope.launch(Dispatchers.IO) {
                try {
                    val url = java.net.URL(branding.logoUrl)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(url.openStream())
                    withContext(Dispatchers.Main) { headerLogo.setImageBitmap(bitmap) }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        headerLogo.visibility = View.GONE
                        headerText.visibility = View.VISIBLE
                        headerText.text = branding.displayName
                    }
                }
            }
        } else {
            headerLogo.visibility = View.GONE
            headerText.visibility = View.VISIBLE
            headerText.text = branding.displayName
        }

        // Apply primary color to header text
        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            headerText.setTextColor(primaryColor)
        } catch (_: Exception) {}

        // Apply primary color to icon circles at 12% opacity
        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            val bgColor = Color.argb(31, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            introIconCircle.background = circleDrawable
            actionReviewIconCircle.background = circleDrawable
        } catch (_: Exception) {}

        // Apply primary color to buttons
        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            introGetStartedButton.setBackgroundColor(primaryColor)
            actionReviewVerifyButton.setBackgroundColor(primaryColor)
        } catch (_: Exception) {}
    }

    private suspend fun loadData() {
        setStep(PageStep.LOADING)
        loadingText.text = if (flowType == FlowType.ENROLLMENT) {
            getString(R.string.usesense_loading_enrollment)
        } else {
            getString(R.string.usesense_loading_verification)
        }

        try {
            when (flowType) {
                FlowType.ENROLLMENT -> loadEnrollmentData()
                FlowType.VERIFICATION -> loadVerificationData()
            }
        } catch (e: Exception) {
            showError(e.message ?: "Failed to load session data")
        }
    }

    private suspend fun loadEnrollmentData() {
        val result = apiClient.getRemoteEnrollmentData(remoteId)
        result.onFailure { showError(it.message ?: "Failed to load enrollment"); return }

        val data = result.getOrNull() ?: run { showError("Empty response"); return }
        orgBranding = data.branding ?: data.enrollment?.branding
        effectiveBranding = EffectiveBranding.merge(pendingConfig?.branding, orgBranding?.let {
            ServerBranding(it.displayName, it.logoUrl, it.primaryColor, it.redirectUrl)
        })
        applyBranding()

        // Mark opened
        apiClient.markEnrollmentOpened(remoteId)

        showIntroduction()
    }

    private suspend fun loadVerificationData() {
        val result = apiClient.getRemoteSessionData(remoteId)
        result.onFailure { showError(it.message ?: "Failed to load session"); return }

        val data = result.getOrNull() ?: run { showError("Empty response"); return }
        orgBranding = data.branding ?: data.session?.branding
        actionContext = data.actionContext ?: data.session?.actionContext
        effectiveBranding = EffectiveBranding.merge(pendingConfig?.branding, orgBranding?.let {
            ServerBranding(it.displayName, it.logoUrl, it.primaryColor, it.redirectUrl)
        })
        applyBranding()

        // Mark opened
        apiClient.markSessionOpened(remoteId)

        showActionReview()
    }

    private fun showIntroduction() {
        val orgName = effectiveBranding.displayName
        introHeadline.text = getString(R.string.usesense_intro_title, orgName)
        introDescription.text = getString(R.string.usesense_intro_body)

        introGetStartedButton.setOnClickListener {
            mainScope.launch { initAndStartCapture() }
        }

        setStep(PageStep.INTRODUCTION)
    }

    private fun showActionReview() {
        val orgName = effectiveBranding.displayName
        val hasAction = actionContext != null

        if (hasAction) {
            // Action authorization (Section 11.7)
            actionReviewHeadline.text = getString(R.string.usesense_action_review_action, orgName)
            actionReviewDescription.visibility = View.GONE

            // Show action context card
            actionContextCard.visibility = View.VISIBLE
            actionContextText.text = actionContext?.displayText ?: ""

            // Apply primary color border to card
            try {
                val primaryColor = Color.parseColor(effectiveBranding.primaryColor)
                val cardBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), primaryColor)
                    setColor(Color.WHITE)
                }
                actionContextCard.background = cardBg
            } catch (_: Exception) {}

            // Risk tier badge
            val riskTier = actionContext?.riskTier
            if (riskTier != null) {
                riskTierBadge.visibility = View.VISIBLE
                configureRiskBadge(riskTier)
            }

            actionReviewIconCircle.visibility = View.GONE
            actionReviewVerifyButton.text = getString(R.string.usesense_verify_authorise)

            // Dispute button
            actionReviewDisputeButton.visibility = View.VISIBLE
            actionReviewDisputeButton.setOnClickListener { showDisputeConfirmation() }
        } else {
            // Plain auth (Section 11.6)
            actionReviewHeadline.text = getString(R.string.usesense_action_review_plain, orgName)
            actionReviewDescription.text = getString(R.string.usesense_action_review_plain_body)
            actionReviewDescription.visibility = View.VISIBLE
            actionContextCard.visibility = View.GONE
            riskTierBadge.visibility = View.GONE
            actionReviewIconCircle.visibility = View.VISIBLE
            actionReviewDisputeButton.visibility = View.GONE
            actionReviewVerifyButton.text = getString(R.string.usesense_verify_identity)
        }

        actionReviewVerifyButton.setOnClickListener {
            mainScope.launch { initAndStartCapture() }
        }

        setStep(PageStep.ACTION_REVIEW)
    }

    private fun configureRiskBadge(riskTier: String) {
        val (text, bgColor, textColor) = when (riskTier.lowercase()) {
            "critical" -> Triple(
                getString(R.string.usesense_risk_critical),
                Color.parseColor("#FEE2E2"),
                Color.parseColor("#991B1B")
            )
            "high" -> Triple(
                getString(R.string.usesense_risk_high),
                Color.parseColor("#FEF3C7"),
                Color.parseColor("#92400E")
            )
            "medium" -> Triple(
                getString(R.string.usesense_risk_medium),
                Color.parseColor("#DBEAFE"),
                Color.parseColor("#1E40AF")
            )
            "low" -> Triple(
                getString(R.string.usesense_risk_low),
                Color.parseColor("#DCFCE7"),
                Color.parseColor("#166534")
            )
            else -> Triple(riskTier.uppercase(), Color.parseColor("#F1F5F9"), Color.parseColor("#475569"))
        }

        riskTierBadge.text = text
        riskTierBadge.setTextColor(textColor)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * resources.displayMetrics.density
            setColor(bgColor)
        }
        riskTierBadge.background = bg
    }

    // Section 10.3: Dispute Flow
    private fun showDisputeConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.usesense_dispute_confirm_title))
            .setMessage(getString(R.string.usesense_dispute_confirm_message))
            .setPositiveButton("Confirm") { _, _ ->
                mainScope.launch {
                    try {
                        apiClient.disputeSession(remoteId)
                        AlertDialog.Builder(this@HostedPageActivity)
                            .setMessage(getString(R.string.usesense_dispute_success))
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    } catch (e: Exception) {
                        showError("Failed to submit dispute: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun initAndStartCapture() {
        setStep(PageStep.LOADING)
        loadingText.text = getString(R.string.usesense_getting_ready)

        try {
            val config = pendingConfig ?: run { showError("SDK not configured"); return }

            val request = if (isDirectMode) {
                // Direct mode: use the request passed from the caller
                pendingRequest ?: run { showError("No verification request"); return }
            } else {
                // Remote mode: init session via API, then build request
                val initResult = when (flowType) {
                    FlowType.ENROLLMENT -> apiClient.initEnrollmentSession(remoteId)
                    FlowType.VERIFICATION -> apiClient.initVerificationSession(remoteId)
                }

                initResult.onFailure {
                    showError(it.message ?: "Failed to initialize session")
                    return
                }

                sessionData = initResult.getOrNull()
                sessionData ?: run { showError("Empty session response"); return }

                VerificationRequest(
                    sessionType = if (flowType == FlowType.ENROLLMENT) SessionType.ENROLLMENT else SessionType.AUTHENTICATION,
                )
            }

            // Store capture result handler
            pendingHostedCallback = object : UseSenseCallback {
                override fun onSuccess(result: UseSenseResult) {
                    mainScope.launch { handleCaptureComplete(result) }
                }
                override fun onError(error: UseSenseError) {
                    mainScope.launch { handleCaptureComplete(null, error) }
                }
                override fun onCancelled() {
                    if (isDirectMode) {
                        pendingDirectCallback?.onCancelled()
                    }
                    finish()
                }
            }

            UseSenseActivity.start(this, config, request, pendingHostedCallback!!)
        } catch (e: Exception) {
            showError("Unexpected error: ${e.message}")
        }
    }

    /**
     * Section 13.2: handleCaptureComplete Safety Net.
     * Wraps /complete call in try-catch. ALWAYS shows a result screen.
     */
    private suspend fun handleCaptureComplete(result: UseSenseResult?, error: UseSenseError? = null) {
        if (isDirectMode) {
            // Direct mode: the capture engine already handled upload + /complete.
            // Show result screen and deliver callback.
            if (result != null) {
                showResult(result.decision, result)
                pendingDirectCallback?.onSuccess(result)
            } else if (error != null) {
                pendingDirectCallback?.onError(error)
                showResult(UseSenseResult.DECISION_REJECT, null)
            } else {
                val fallbackError = UseSenseError.captureFailed("Verification failed")
                pendingDirectCallback?.onError(fallbackError)
                showResult(UseSenseResult.DECISION_REJECT, null)
            }
            return
        }

        // Remote mode: call hosted /complete endpoint
        setStep(PageStep.FINALIZING)
        finalizingTitle.text = if (flowType == FlowType.ENROLLMENT) {
            getString(R.string.usesense_processing_enrollment)
        } else {
            getString(R.string.usesense_processing_verification)
        }

        try {
            val completeResult = when (flowType) {
                FlowType.ENROLLMENT -> apiClient.completeEnrollment(remoteId)
                FlowType.VERIFICATION -> apiClient.completeRemoteSession(remoteId)
            }

            completeResult.onSuccess { response ->
                val decision = result?.decision ?: response.decision ?: "REJECT"
                showResult(decision, result)
            }
            completeResult.onFailure { e ->
                Log.e(TAG, "Complete error", e)
                if (result?.decision == UseSenseResult.DECISION_APPROVE) {
                    showResult(UseSenseResult.DECISION_APPROVE, result)
                } else {
                    showResult(UseSenseResult.DECISION_REJECT, result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleCaptureComplete error", e)
            showResult(result?.decision ?: UseSenseResult.DECISION_REJECT, result)
        }
    }

    private fun showResult(decision: String, result: UseSenseResult?) {
        val isEnrollment = flowType == FlowType.ENROLLMENT
        val orgName = effectiveBranding.displayName

        when (decision) {
            UseSenseResult.DECISION_APPROVE -> {
                // Success (Section 12.1)
                resultIcon.setImageResource(R.drawable.usesense_icon_success)
                resultIcon.setColorFilter(Color.parseColor("#16A34A"))
                val circleBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#DCFCE7"))
                }
                resultIconCircle.background = circleBg
                resultTitle.text = if (isEnrollment) {
                    getString(R.string.usesense_result_success_enrollment_title)
                } else {
                    getString(R.string.usesense_result_success_title)
                }
                resultBody.text = getString(R.string.usesense_result_success_body)

                // Show action confirmation for action auth
                if (actionContext != null) {
                    resultActionBox.visibility = View.VISIBLE
                    resultActionText.text = actionContext?.displayText
                }
            }
            UseSenseResult.DECISION_MANUAL_REVIEW -> {
                // Manual review (Section 12.3)
                resultIcon.setImageResource(R.drawable.usesense_icon_review)
                resultIcon.setColorFilter(Color.parseColor("#D97706"))
                val circleBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FEF3C7"))
                }
                resultIconCircle.background = circleBg
                resultTitle.text = getString(R.string.usesense_result_review_title)
                resultBody.text = getString(R.string.usesense_result_review_body)
            }
            else -> {
                // Failed (Section 12.2)
                resultIcon.setImageResource(R.drawable.usesense_icon_denied)
                resultIcon.setColorFilter(Color.parseColor("#DC2626"))
                val circleBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FEE2E2"))
                }
                resultIconCircle.background = circleBg
                resultTitle.text = if (isEnrollment) {
                    getString(R.string.usesense_result_failed_enrollment_title)
                } else {
                    getString(R.string.usesense_result_failed_title)
                }
                resultBody.text = getString(R.string.usesense_result_failed_body, orgName)
            }
        }

        // Close/Continue button logic (Section 12)
        val redirectUrl = effectiveBranding.redirectUrl
        if (redirectUrl != null) {
            resultButton.text = getString(R.string.usesense_continue)
            resultButton.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl)))
                } catch (_: Exception) {}
                finish()
            }
        } else {
            resultButton.text = getString(R.string.usesense_close)
            resultButton.setOnClickListener { finish() }
        }

        setStep(PageStep.RESULT)
    }

    private fun showError(message: String) {
        errorMessage.text = message
        setStep(PageStep.ERROR)
    }

    private val allScreens by lazy {
        listOf(loadingScreen, errorScreen, introScreen, actionReviewScreen,
            captureContainer, finalizingScreen, resultScreen)
    }

    private fun setStep(step: PageStep) {
        allScreens.forEach { it.visibility = View.GONE }
        when (step) {
            PageStep.LOADING -> loadingScreen.visibility = View.VISIBLE
            PageStep.ERROR -> errorScreen.visibility = View.VISIBLE
            PageStep.INTRODUCTION -> introScreen.visibility = View.VISIBLE
            PageStep.ACTION_REVIEW -> actionReviewScreen.visibility = View.VISIBLE
            PageStep.CAPTURE -> captureContainer.visibility = View.VISIBLE
            PageStep.FINALIZING -> finalizingScreen.visibility = View.VISIBLE
            PageStep.RESULT -> resultScreen.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        pendingConfig = null
        pendingRequest = null
        pendingDirectCallback = null
        pendingHostedCallback = null
    }

    companion object {
        private const val TAG = "HostedPageActivity"
        private const val EXTRA_FLOW_TYPE = "flow_type"
        private const val EXTRA_REMOTE_ID = "remote_id"
        private const val EXTRA_DIRECT_MODE = "direct_mode"

        internal var pendingConfig: UseSenseConfig? = null
        internal var pendingRequest: VerificationRequest? = null
        internal var pendingDirectCallback: UseSenseCallback? = null
        internal var pendingHostedCallback: UseSenseCallback? = null

        /**
         * Launch the hosted page UI for a direct SDK verification/enrollment flow.
         * This provides the same UI experience as the hosted page flows (intro screen,
         * branding, result screen) but driven by the app's VerificationRequest.
         */
        fun startDirect(
            context: Context,
            config: UseSenseConfig,
            request: VerificationRequest,
            callback: UseSenseCallback,
        ) {
            pendingConfig = config
            pendingRequest = request
            pendingDirectCallback = callback
            val flowType = if (request.sessionType == SessionType.ENROLLMENT) "enrollment" else "verification"
            val intent = Intent(context, HostedPageActivity::class.java).apply {
                putExtra(EXTRA_FLOW_TYPE, flowType)
                putExtra(EXTRA_DIRECT_MODE, true)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun startEnrollment(context: Context, config: UseSenseConfig, remoteEnrollmentId: String) {
            pendingConfig = config
            val intent = Intent(context, HostedPageActivity::class.java).apply {
                putExtra(EXTRA_FLOW_TYPE, "enrollment")
                putExtra(EXTRA_REMOTE_ID, remoteEnrollmentId)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun startVerification(context: Context, config: UseSenseConfig, remoteSessionId: String) {
            pendingConfig = config
            val intent = Intent(context, HostedPageActivity::class.java).apply {
                putExtra(EXTRA_FLOW_TYPE, "verification")
                putExtra(EXTRA_REMOTE_ID, remoteSessionId)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
