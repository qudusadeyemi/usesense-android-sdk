package com.usesense.sdk.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.usesense.sdk.internal.CameraLiveGate
import com.usesense.sdk.R
import com.usesense.sdk.*
import com.usesense.sdk.api.ApiException
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.*
import com.usesense.sdk.capture.FrameCaptureManager
import com.usesense.sdk.capture.ImageQualityAnalyzer
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.Bounded
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.CaptureWatchdog
import com.usesense.sdk.internal.SessionState
import com.usesense.sdk.internal.WatchdogPhase
import com.usesense.sdk.internal.boundedCall
import com.usesense.sdk.finalization.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * Hosted Page Activity — unified UI for all verification flows (Sections 10-12).
 *
 * Handles:
 *   - Direct SDK flow: intro/review -> permission -> capture -> upload -> result
 *   - Remote Enrollment: data fetch -> introduction -> permission -> capture -> finalize -> result
 *   - Remote Verification: data fetch -> action-review -> permission -> capture -> finalize -> result
 *
 * The capture engine (camera, challenges, upload) runs inline within this Activity,
 * keeping the branded header/footer consistent throughout the entire flow.
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

    // Capture engine
    private var session: UseSenseSession? = null
    private var challengePresenter: ChallengePresenter? = null
    private var frameCaptureManager: FrameCaptureManager? = null
    private val qualityAnalyzer = ImageQualityAnalyzer()
    private var lastQualityAnalysisMs = 0L

    /** The capture clock starts on the camera's first frame, not at bind. */
    private val cameraLive = CameraLiveGate()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // Header views
    private lateinit var headerLogo: ImageView
    private lateinit var headerText: TextView

    // Screen views
    private lateinit var loadingScreen: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var errorScreen: ScrollView
    private lateinit var errorIconCircle: FrameLayout
    private lateinit var errorIcon: ImageView
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorCode: TextView
    private lateinit var errorPrimaryButton: MaterialButton
    private lateinit var errorSecondaryButton: MaterialButton
    private lateinit var errorExitButton: MaterialButton
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

    // Permission views
    private lateinit var permissionScreen: LinearLayout
    private lateinit var permissionIcon: ImageView
    private lateinit var permissionTitle: TextView
    private lateinit var permissionMessage: TextView
    private lateinit var permissionButton: MaterialButton

    // Capture views
    private lateinit var captureScreen: LinearLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var cameraPreview: PreviewView
    private lateinit var challengeOverlay: FrameLayout
    private lateinit var baselineOvalView: View
    private lateinit var dotView: View
    private lateinit var directionCircle: FrameLayout
    private lateinit var directionArrow: TextView
    private lateinit var phaseBadge: TextView
    private lateinit var qualityIndicator: QualityIndicatorView
    private lateinit var faceGuideOverlay: FrameLayout
    private lateinit var faceGuideReadyButton: MaterialButton
    private lateinit var countdownOverlay: FrameLayout
    private lateinit var countdownCircle: FrameLayout
    private lateinit var countdownNumber: TextView
    private lateinit var instructionsOverlay: FrameLayout
    private lateinit var instructionsIcon: TextView
    private lateinit var instructionsTitle: TextView
    private lateinit var instructionsBody: TextView
    private lateinit var instructionsCta: TextView
    private lateinit var qualityBanner: QualityWarningBanner
    private lateinit var captureTitle: TextView
    private lateinit var captureSubtitle: TextView
    private lateinit var directionText: TextView
    private lateinit var speakPhraseLayout: LinearLayout
    private lateinit var phraseText: TextView
    private lateinit var recordingIndicator: TextView
    private lateinit var challengeProgress: ProgressBar
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var finalizingSpinner: ProgressBar

    // Upload/completing overlay (Section 5.8/5.9 — dark overlay on camera)
    private lateinit var uploadOverlay: FrameLayout
    private lateinit var uploadOverlayTitle: TextView
    private lateinit var uploadOverlaySubtitle: TextView

    // Capture footer (Section 11.8)
    private lateinit var captureFooterText: TextView

    // Header bar reference for hide/show
    private lateinit var headerBar: LinearLayout
    private lateinit var headerDivider: View

    // Finalizing & result views
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
    private lateinit var footerDivider: View

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val eventEmitter get() = UseSense.eventEmitter

    enum class FlowType { ENROLLMENT, VERIFICATION }

    /** True while a server step-up round (round 2) is being captured. */
    private var stepUpRoundActive = false

    /**
     * Each challenge phase is one capture round. Its completion callbacks carry
     * the round they belong to, and only the latest round completes, once.
     * A second completion (a double-tapped start button, a duplicate timer)
     * used to see an empty buffer and show NO_FRAMES_CAPTURED over an upload
     * that was already running, and the real result then replaced the error.
     */
    private var captureRound = 0
    private var completedRound = -1

    /**
     * Backstop for a capture that stops making progress: moves the screen to a
     * terminal error with a stable code instead of an endless spinner.
     */
    private lateinit var watchdog: CaptureWatchdog

    /** The finalization (upload + complete) coroutine of the current attempt. */
    private var finalizationJob: Job? = null

    /** Delivers exactly one host callback per activity. */
    private val callbackGate = FinalizationCallbackGate<UseSenseResult, UseSenseError>(
        { pendingDirectCallback?.onSuccess(it) },
        { pendingDirectCallback?.onError(it) },
        { pendingDirectCallback?.onCancelled() },
    )

    /** The error on screen right now, delivered if the subject leaves from it. */
    private var pendingTerminalError: UseSenseError? = null

    enum class PageStep {
        LOADING, ERROR, INTRODUCTION, ACTION_REVIEW,
        PERMISSION, CAPTURE, FINALIZING, RESULT
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            eventEmitter.emit(EventType.PERMISSIONS_GRANTED, mapOf("camera" to true))
            onPermissionsGranted()
        } else {
            eventEmitter.emit(EventType.PERMISSIONS_DENIED, mapOf("camera" to false))
            showCameraError(getString(R.string.usesense_camera_denied_message))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usesense_hosted)
        bindViews()

        watchdog = CaptureWatchdog(mainScope) { _, error -> onWatchdogTimeout(error) }
        // Leaving the capture must never end silently: abandon the attempt and
        // let onDestroy deliver onCancelled (or the error on screen).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                abortAttempt()
                finish()
            }
        })

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
            when (flowType) {
                FlowType.ENROLLMENT -> showIntroduction()
                FlowType.VERIFICATION -> showActionReview()
            }
        } else {
            mainScope.launch { loadData() }
        }
    }

    private fun bindViews() {
        headerBar = findViewById(R.id.headerBar)
        headerDivider = findViewById(R.id.headerDivider)
        headerLogo = findViewById(R.id.headerLogo)
        headerText = findViewById(R.id.headerText)
        loadingScreen = findViewById(R.id.hostedLoadingScreen)
        loadingText = findViewById(R.id.hostedLoadingText)
        errorScreen = findViewById(R.id.hostedErrorScreen)
        errorIconCircle = findViewById(R.id.errorIconCircle)
        errorIcon = findViewById(R.id.errorIcon)
        errorTitle = findViewById(R.id.hostedErrorTitle)
        errorMessage = findViewById(R.id.hostedErrorMessage)
        errorCode = findViewById(R.id.hostedErrorCode)
        errorPrimaryButton = findViewById(R.id.errorPrimaryButton)
        errorSecondaryButton = findViewById(R.id.errorSecondaryButton)
        errorExitButton = findViewById(R.id.errorExitButton)
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

        // Permission
        permissionScreen = findViewById(R.id.hostedPermissionScreen)
        permissionIcon = findViewById(R.id.hostedPermissionIcon)
        permissionTitle = findViewById(R.id.hostedPermissionTitle)
        permissionMessage = findViewById(R.id.hostedPermissionMessage)
        permissionButton = findViewById(R.id.hostedPermissionButton)

        // Capture
        captureScreen = findViewById(R.id.hostedCaptureScreen)
        videoContainer = findViewById(R.id.hostedVideoContainer)
        cameraPreview = findViewById(R.id.hostedCameraPreview)
        challengeOverlay = findViewById(R.id.hostedChallengeOverlay)
        baselineOvalView = findViewById(R.id.hostedBaselineOvalView)
        dotView = findViewById(R.id.hostedDotView)
        directionCircle = findViewById(R.id.hostedDirectionCircle)
        directionArrow = findViewById(R.id.hostedDirectionArrow)
        phaseBadge = findViewById(R.id.hostedPhaseBadge)
        qualityIndicator = findViewById(R.id.hostedQualityIndicator)
        faceGuideOverlay = findViewById(R.id.hostedFaceGuideOverlay)
        faceGuideReadyButton = findViewById(R.id.hostedFaceGuideReadyButton)
        countdownOverlay = findViewById(R.id.hostedCountdownOverlay)
        countdownCircle = findViewById(R.id.hostedCountdownCircle)
        countdownNumber = findViewById(R.id.hostedCountdownNumber)
        instructionsOverlay = findViewById(R.id.hostedInstructionsOverlay)
        instructionsIcon = findViewById(R.id.hostedInstructionsIcon)
        instructionsTitle = findViewById(R.id.hostedInstructionsTitle)
        instructionsBody = findViewById(R.id.hostedInstructionsBody)
        instructionsCta = findViewById(R.id.hostedInstructionsCta)
        qualityBanner = findViewById(R.id.hostedQualityBanner)
        captureTitle = findViewById(R.id.hostedCaptureTitle)
        captureSubtitle = findViewById(R.id.hostedCaptureSubtitle)
        directionText = findViewById(R.id.hostedDirectionText)
        speakPhraseLayout = findViewById(R.id.hostedSpeakPhraseLayout)
        phraseText = findViewById(R.id.hostedPhraseText)
        recordingIndicator = findViewById(R.id.hostedRecordingIndicator)
        challengeProgress = findViewById(R.id.hostedChallengeProgress)
        loadingSpinner = findViewById(R.id.hostedLoadingSpinner)
        finalizingSpinner = findViewById(R.id.finalizingSpinner)

        // Upload overlay (Section 5.8/5.9)
        uploadOverlay = findViewById(R.id.hostedUploadOverlay)
        uploadOverlayTitle = findViewById(R.id.hostedUploadOverlayTitle)
        uploadOverlaySubtitle = findViewById(R.id.hostedUploadOverlaySubtitle)

        // Capture footer
        captureFooterText = findViewById(R.id.captureFooterText)

        // Finalizing & result
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
        footerDivider = findViewById(R.id.footerDivider)
    }

    // ─── Branding ────────────────────────────────────────────────────────

    private fun applyBranding() {
        val branding = effectiveBranding

        // As iOS (HostedPageHeader): the logo alone when there is one; the name
        // shows while it loads and stays if there is no logo or it fails.
        headerText.text = branding.displayName
        headerText.visibility = View.VISIBLE
        headerLogo.visibility = View.GONE
        branding.logoUrl?.let { logoUrl ->
            mainScope.launch(Dispatchers.IO) {
                val bitmap = runCatching {
                    java.net.URL(logoUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        headerLogo.setImageBitmap(bitmap)
                        headerLogo.visibility = View.VISIBLE
                        headerText.visibility = View.GONE
                    }
                }
            }
        }

        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            headerText.setTextColor(primaryColor)
        } catch (_: Exception) {}

        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            val bgColor = Color.argb(31, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            introIconCircle.background = circleDrawable
            actionReviewIconCircle.background = circleDrawable
            // The glyph inside each circle too: it is tinted DeepSense Blue in
            // XML, so a branded intro showed a blue camera on a brand circle.
            val glyphTint = ColorStateList.valueOf(primaryColor)
            listOf(introIconCircle, actionReviewIconCircle).forEach { circle ->
                ((circle as? android.view.ViewGroup)?.getChildAt(0) as? ImageView)?.imageTintList = glyphTint
            }
        } catch (_: Exception) {}

        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            introGetStartedButton.setBackgroundColor(primaryColor)
            actionReviewVerifyButton.setBackgroundColor(primaryColor)
        } catch (_: Exception) {}

        applyBrandToCaptureScreens()
    }

    /** The brand colour, or null when it is unparseable. */
    private fun brandColor(): Int? = runCatching { Color.parseColor(effectiveBranding.primaryColor) }.getOrNull()

    /** True when the org or integrator set a colour (not the built-in DeepSense Blue). */
    private val isBranded: Boolean
        get() = !effectiveBranding.primaryColor.equals(BrandingConfig.DEFAULT_PRIMARY_COLOR, ignoreCase = true)

    /** Text on a brand-filled surface: the appearance's primaryForeground, else white. */
    private fun brandForeground(): Int =
        pendingConfig?.branding?.appearance?.colors?.primaryForeground
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            ?: Color.WHITE

    /**
     * Theme the loading, permission, instructions, challenge and finalizing
     * screens from the brand colour. These read `@color/usesense_primary` from
     * XML, so inside a branded Flow they stayed DeepSense Blue while the
     * header and intro took the org colour. Unbranded sessions keep the XML
     * palette untouched.
     */
    private fun applyBrandToCaptureScreens() {
        if (!isBranded) return
        val primary = brandColor() ?: return
        val tint = ColorStateList.valueOf(primary)
        val density = resources.displayMetrics.density

        loadingSpinner.indeterminateTintList = tint
        finalizingSpinner.indeterminateTintList = tint
        challengeProgress.progressTintList = tint
        permissionIcon.imageTintList = tint
        permissionButton.backgroundTintList = tint
        permissionButton.setTextColor(brandForeground())
        faceGuideReadyButton.backgroundTintList = tint
        faceGuideReadyButton.setTextColor(brandForeground())

        instructionsIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(31, Color.red(primary), Color.green(primary), Color.blue(primary)))
        }
        instructionsCta.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * density
            setColor(primary)
        }
        instructionsCta.setTextColor(brandForeground())
    }

    /** Head-turn direction circle: the brand colour into a darker shade, else the built-in blue to purple. */
    private fun directionCircleColors(isCenter: Boolean): IntArray {
        val primary = brandColor()
        if (!isBranded || primary == null) {
            return if (isCenter) {
                intArrayOf(Color.parseColor("#4F7CFF"), Color.parseColor("#7C5CFC"))
            } else {
                intArrayOf(Color.parseColor("#3D63DB"), Color.parseColor("#4F7CFF"))
            }
        }
        val darker = ColorUtils.blendARGB(primary, Color.BLACK, 0.2f)
        return if (isCenter) intArrayOf(primary, darker) else intArrayOf(darker, primary)
    }

    // ─── Remote Data Loading ─────────────────────────────────────────────

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
            showError(e, "Failed to load session data")
        }
    }

    private suspend fun loadEnrollmentData() {
        val result = apiClient.getRemoteEnrollmentData(remoteId)
        result.onFailure { showError(it, "Failed to load enrollment"); return }

        val data = result.getOrNull() ?: run { showError("Empty response"); return }
        orgBranding = data.branding ?: data.enrollment?.branding
        effectiveBranding = EffectiveBranding.merge(pendingConfig?.branding, orgBranding?.let {
            ServerBranding(it.displayName, it.logoUrl, it.primaryColor, it.redirectUrl)
        })
        applyBranding()
        apiClient.markEnrollmentOpened(remoteId)
        showIntroduction()
    }

    private suspend fun loadVerificationData() {
        val result = apiClient.getRemoteSessionData(remoteId)
        result.onFailure { showError(it, "Failed to load session"); return }

        val data = result.getOrNull() ?: run { showError("Empty response"); return }
        orgBranding = data.branding ?: data.session?.branding
        actionContext = data.actionContext ?: data.session?.actionContext
        effectiveBranding = EffectiveBranding.merge(pendingConfig?.branding, orgBranding?.let {
            ServerBranding(it.displayName, it.logoUrl, it.primaryColor, it.redirectUrl)
        })
        applyBranding()
        apiClient.markSessionOpened(remoteId)
        showActionReview()
    }

    // ─── Introduction & Action Review ────────────────────────────────────

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
            actionReviewHeadline.text = getString(R.string.usesense_action_review_action, orgName)
            actionReviewDescription.visibility = View.GONE
            actionContextCard.visibility = View.VISIBLE
            actionContextText.text = actionContext?.displayText ?: ""

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

            val riskTier = actionContext?.riskTier
            if (riskTier != null) {
                riskTierBadge.visibility = View.VISIBLE
                configureRiskBadge(riskTier)
            }

            actionReviewIconCircle.visibility = View.GONE
            actionReviewVerifyButton.text = getString(R.string.usesense_verify_authorise)
            actionReviewDisputeButton.visibility = View.VISIBLE
            actionReviewDisputeButton.setOnClickListener { showDisputeConfirmation() }
        } else {
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
            "critical" -> Triple(getString(R.string.usesense_risk_critical), Color.parseColor("#FFF0EC"), Color.parseColor("#B73520"))
            "high" -> Triple(getString(R.string.usesense_risk_high), Color.parseColor("#FFF7E8"), Color.parseColor("#B77829"))
            "medium" -> Triple(getString(R.string.usesense_risk_medium), Color.parseColor("#EBF0FF"), Color.parseColor("#2C4AB7"))
            "low" -> Triple(getString(R.string.usesense_risk_low), Color.parseColor("#E6FBF5"), Color.parseColor("#008066"))
            else -> Triple(riskTier.uppercase(), Color.parseColor("#F5F3EF"), Color.parseColor("#6B6760"))
        }
        riskTierBadge.text = text
        riskTierBadge.setTextColor(textColor)
        riskTierBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * resources.displayMetrics.density
            setColor(bgColor)
        }
    }

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

    // ─── Capture Engine ──────────────────────────────────────────────────

    private suspend fun initAndStartCapture() {
        setStep(PageStep.LOADING)
        loadingText.text = getString(R.string.usesense_getting_ready)

        try {
            val config = pendingConfig ?: run { showError("SDK not configured"); return }

            val request = if (isDirectMode) {
                pendingRequest ?: run { showError("No verification request"); return }
            } else {
                // Remote mode: init session via API
                val initResult = when (flowType) {
                    FlowType.ENROLLMENT -> apiClient.initEnrollmentSession(remoteId)
                    FlowType.VERIFICATION -> apiClient.initVerificationSession(remoteId)
                }
                initResult.onFailure {
                    showError(it, "Failed to initialize session")
                    return
                }
                sessionData = initResult.getOrNull()
                sessionData ?: run { showError("Empty session response"); return }

                VerificationRequest(
                    sessionType = if (flowType == FlowType.ENROLLMENT) SessionType.ENROLLMENT else SessionType.AUTHENTICATION,
                )
            }

            // Create session
            val sess = UseSenseSession(this, config, request)
            session = sess
            sess.onStateChanged = { state -> onSessionStateChanged(state) }

            // Flows launches the hosted page with pre-minted credentials from
            // /sdk/flow-runs/:id/init-session; skip createSession in that path.
            val prebuilt = pendingPrebuiltSession
            val refresh = pendingSessionFactory
            if (prebuilt != null) {
                sess.injectHostedSessionData(prebuilt)
                pendingPrebuiltSession = null
            } else if (refresh != null) {
                // "Try again" in the Flows path: the first session was consumed,
                // so mint a fresh one for this step (bounded; the factory makes a
                // blocking network call that cancellation cannot interrupt).
                val fresh = when (val r = boundedCall(mainScope, SESSION_REFRESH_TIMEOUT_MS) { runCatching { refresh() } }) {
                    is Bounded.Done -> r.value
                    is Bounded.TimedOut -> Result.failure(java.io.InterruptedIOException("init-session timed out"))
                }
                val response = fresh.getOrElse { e ->
                    val error = when (e) {
                        is java.io.InterruptedIOException -> UseSenseError.networkTimeout()
                        else -> UseSenseError.networkError(e.message)
                    }
                    showTerminalFailure(error, WatchdogPhase.CAMERA.wire)
                    return
                }
                sess.injectHostedSessionData(response)
            } else {
                val createResult = sess.createSession()
                createResult.onFailure { e ->
                    eventEmitter.emit(EventType.ERROR, mapOf("error" to (e.message ?: "")))
                    val error = (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.networkError(e.message)
                    deliverError(error)
                    return
                }
            }

            eventEmitter.emit(EventType.SESSION_CREATED, mapOf("session_id" to (sess.sessionId ?: "")))
            eventEmitter.emit(EventType.PERMISSIONS_REQUESTED)
            checkAndRequestPermissions()
        } catch (e: Exception) {
            showError("Unexpected error: ${e.message}")
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val sess = session ?: return
        val permsNeeded = mutableListOf(Manifest.permission.CAMERA)
        if (sess.requiresAudio) {
            permsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        val notGranted = permsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            eventEmitter.emit(EventType.PERMISSIONS_GRANTED, mapOf("camera" to true))
            onPermissionsGranted()
        } else {
            showPermissionScreen(notGranted)
        }
    }

    private fun showPermissionScreen(permissions: List<String>) {
        val needsMic = permissions.contains(Manifest.permission.RECORD_AUDIO)
        if (needsMic) {
            permissionIcon.setImageResource(R.drawable.usesense_icon_microphone)
            permissionTitle.text = getString(R.string.usesense_mic_permission_title)
            permissionMessage.text = getString(R.string.usesense_mic_permission_message)
        } else {
            permissionIcon.setImageResource(R.drawable.usesense_icon_camera)
            permissionTitle.text = getString(R.string.usesense_camera_permission_title)
            permissionMessage.text = getString(R.string.usesense_camera_permission_message)
        }

        setStep(PageStep.PERMISSION)
        permissionButton.setOnClickListener {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun showCameraError(message: String) {
        session?.setCapturePhase(CapturePhase.CAMERA_ERROR)
        permissionIcon.setImageResource(R.drawable.usesense_icon_camera)
        permissionTitle.text = getString(R.string.usesense_camera_error_title)
        permissionMessage.text = message
        permissionButton.text = getString(R.string.usesense_retry)

        setStep(PageStep.PERMISSION)
        permissionButton.setOnClickListener {
            session?.setCapturePhase(CapturePhase.CAMERA_REQUEST)
            checkAndRequestPermissions()
        }
    }

    private fun onPermissionsGranted() {
        showInstructions()
    }

    // ─── Instructions ────────────────────────────────────────────────────

    private fun showInstructions() {
        setStep(PageStep.CAPTURE)

        val challengeType = session?.challengeSpec?.type

        instructionsIcon.text = when (challengeType) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> "\uD83D\uDC41"
            ChallengeSpec.TYPE_HEAD_TURN -> "\u21C4"
            ChallengeSpec.TYPE_SPEAK_PHRASE -> "\uD83C\uDFA4"
            else -> "\uD83D\uDC64"
        }
        instructionsTitle.text = when (challengeType) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> getString(R.string.usesense_instructions_title_follow_dot)
            ChallengeSpec.TYPE_HEAD_TURN -> getString(R.string.usesense_instructions_title_head_turn)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> getString(R.string.usesense_instructions_title_speak_phrase)
            else -> getString(R.string.usesense_verifying)
        }
        instructionsBody.text = when (challengeType) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> getString(R.string.usesense_instruction_follow_dot)
            ChallengeSpec.TYPE_HEAD_TURN -> getString(R.string.usesense_instruction_head_turn)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> getString(R.string.usesense_instruction_speak_phrase)
            else -> getString(R.string.usesense_instruction_default)
        }

        instructionsOverlay.visibility = View.VISIBLE
        captureTitle.text = getString(R.string.usesense_getting_ready)
        captureSubtitle.text = ""

        instructionsCta.isEnabled = true
        instructionsCta.setOnClickListener {
            // One tap starts one capture: a queued second tap must not bind the
            // camera again and start a second capture behind the first.
            if (!instructionsCta.isEnabled) return@setOnClickListener
            instructionsCta.isEnabled = false
            instructionsOverlay.visibility = View.GONE
            startCameraAndCapture()
        }
    }

    // ─── Camera ──────────────────────────────────────────────────────────

    private fun startCameraAndCapture() {
        val sess = session ?: return
        frameCaptureManager = sess.initCapture()
        challengePresenter = sess.createChallengePresenter()

        captureTitle.text = getString(R.string.usesense_position_face)
        captureSubtitle.text = getString(R.string.usesense_center_face)

        startCamera()
    }

    private fun startCamera() {
        val sess = session ?: return
        // System-paced from here: the camera must bind and the capture must
        // finish, upload and settle within the watchdog's budget.
        watchdog.arm(WatchdogPhase.CAMERA)
        cameraLive.reset()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (session !== sess || isFinishing) return@addListener
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                watchdog.disarm()
                showCameraError(getString(R.string.usesense_camera_unavailable_message))
                return@addListener
            }

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }

            cameraPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // No manual flip here. CameraX PreviewView already mirrors a
            // front-facing preview, so the `cameraPreview.scaleX = -1f` that
            // used to sit on this line was a second flip that cancelled the
            // first: the subject saw themselves un-mirrored, turned right, and
            // watched their image go left. UseSenseActivity, the other capture
            // screen, never had the manual flip and has always looked correct
            // -- the two disagreeing was the tell.
            //
            // The mirror is worth having rather than merely harmless: the head
            // turn challenge says "Turn your head LEFT/RIGHT", and only in a
            // mirrored preview does turning your own right move your image to
            // the right of the screen. Frames sent to the server are unmirrored
            // either way; this is a display transform only.

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (cameraLive.markFrame()) {
                            runOnUiThread { if (session === sess && !isFinishing) cameraLive.onLive() }
                        }
                        frameCaptureManager?.createAnalyzer()?.analyze(imageProxy)

                        val now = System.currentTimeMillis()
                        val phase = sess.capturePhase
                        val shouldAnalyze = phase in setOf(
                            CapturePhase.FACE_GUIDE, CapturePhase.BASELINE,
                            CapturePhase.COUNTDOWN, CapturePhase.CHALLENGE
                        )

                        if (shouldAnalyze && now - lastQualityAnalysisMs >= ImageQualityAnalyzer.ANALYSIS_INTERVAL_MS) {
                            lastQualityAnalysisMs = now
                            try {
                                @OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val report = qualityAnalyzer.analyze(mediaImage)
                                    runOnUiThread { updateQualityUI(report) }
                                    eventEmitter.emit(EventType.IMAGE_QUALITY_CHECK, mapOf(
                                        "score" to report.overallScore,
                                        "acceptable" to report.isAcceptable,
                                        "blur" to report.blurLevel.name,
                                        "lighting" to report.lightingLevel.name,
                                    ))
                                }
                            } catch (_: Exception) {}
                        }

                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                val resolutionInfo = imageAnalysis.resolutionInfo
                val resolution = if (resolutionInfo != null) {
                    "${resolutionInfo.resolution.width}x${resolutionInfo.resolution.height}"
                } else "640x480"
                sess.setCaptureInfo("front", resolution)

                val requiresStepup = sess.policy?.requiresStepup == true

                if (requiresStepup) {
                    // The face guide waits for the subject's tap: not supervised.
                    watchdog.disarm()
                    showFaceGuide()
                } else {
                    // The camera watchdog keeps running until frames flow.
                    cameraLive.whenLive {
                        watchdog.enter(WatchdogPhase.CAPTURE)
                        sess.startCapture()
                        eventEmitter.emit(EventType.CAPTURE_STARTED)
                        if (sess.requiresAudio) {
                            sess.startAudioRecording()
                            eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
                        }
                        startBaselinePhase()
                    }
                }
            } catch (e: Exception) {
                watchdog.disarm()
                showCameraError(getString(R.string.usesense_camera_unavailable_message))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateQualityUI(report: ImageQualityAnalyzer.ImageQualityReport) {
        qualityIndicator.updateQuality(report)
        qualityBanner.updateQuality(report)
        qualityIndicator.visibility = View.VISIBLE
        qualityBanner.visibility = if (report.isAcceptable) View.GONE else View.VISIBLE

        val bg = videoContainer.background
        if (bg is GradientDrawable) {
            val dp = resources.displayMetrics.density
            val strokeWidth = (3 * dp).toInt()
            val borderColor = when {
                report.overallScore >= 65 -> Color.TRANSPARENT
                report.overallScore >= 40 -> Color.parseColor("#807C5CFC") // LiveSense Purple 50%
                else -> Color.parseColor("#994F7CFF") // DeepSense Blue 60%
            }
            bg.setStroke(if (borderColor == Color.TRANSPARENT) 0 else strokeWidth, borderColor)
        }
    }

    // ─── Capture Phases ──────────────────────────────────────────────────

    private fun showFaceGuide() {
        val sess = session ?: return
        sess.setCapturePhase(CapturePhase.FACE_GUIDE)
        faceGuideOverlay.visibility = View.VISIBLE

        captureTitle.text = getString(R.string.usesense_position_face)
        captureSubtitle.text = getString(R.string.usesense_center_face)

        faceGuideReadyButton.isEnabled = true
        faceGuideReadyButton.setOnClickListener {
            if (!faceGuideReadyButton.isEnabled) return@setOnClickListener
            faceGuideReadyButton.isEnabled = false
            faceGuideOverlay.visibility = View.GONE
            watchdog.arm(WatchdogPhase.CAPTURE)
            cameraLive.whenLive {
                sess.startCapture()
                eventEmitter.emit(EventType.CAPTURE_STARTED)
                if (sess.requiresAudio) {
                    sess.startAudioRecording()
                    eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
                }
                startBaselinePhase()
            }
        }
    }

    private fun startBaselinePhase() {
        val sess = session ?: return
        try {
            sess.setCapturePhase(CapturePhase.BASELINE)
            challengeOverlay.visibility = View.VISIBLE

            captureTitle.text = getString(R.string.usesense_hold_still)
            captureSubtitle.text = getString(R.string.usesense_keep_still)

            val requiresStepup = sess.policy?.requiresStepup == true
            if (requiresStepup) {
                baselineOvalView.visibility = View.VISIBLE
                showPhaseBadge("BASELINE")
            }

            handler.postDelayed({
                try {
                    baselineOvalView.visibility = View.GONE
                    if (requiresStepup) {
                        startCountdownPhase()
                    } else {
                        startChallengePhase()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture flow", e)
                    deliverError(UseSenseError.captureFailed("Unexpected error: ${e.message}"))
                }
            }, BASELINE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error in baseline phase", e)
            deliverError(UseSenseError.captureFailed("Unexpected error: ${e.message}"))
        }
    }

    private fun startCountdownPhase() {
        val sess = session ?: return
        sess.setCapturePhase(CapturePhase.COUNTDOWN)
        countdownOverlay.visibility = View.VISIBLE
        phaseBadge.visibility = View.GONE

        captureTitle.text = getString(R.string.usesense_countdown_label)
        captureSubtitle.text = ""

        showCountdownNumber(3)
        handler.postDelayed({ showCountdownNumber(2) }, 1000)
        handler.postDelayed({ showCountdownNumber(1) }, 2000)

        handler.postDelayed({
            countdownOverlay.visibility = View.GONE
            startChallengePhase()
        }, COUNTDOWN_MS)
    }

    private fun showCountdownNumber(number: Int) {
        countdownNumber.text = number.toString()
        countdownCircle.scaleX = 0.3f
        countdownCircle.scaleY = 0.3f
        countdownCircle.alpha = 0f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(countdownCircle, "scaleX", 0.3f, 1.15f).apply {
                    duration = 360; interpolator = OvershootInterpolator(1.5f)
                },
                ObjectAnimator.ofFloat(countdownCircle, "scaleY", 0.3f, 1.15f).apply {
                    duration = 360; interpolator = OvershootInterpolator(1.5f)
                },
                ObjectAnimator.ofFloat(countdownCircle, "alpha", 0f, 1f).apply { duration = 360 },
                ObjectAnimator.ofFloat(countdownCircle, "scaleX", 1.15f, 1.0f).apply {
                    duration = 540; startDelay = 360
                },
                ObjectAnimator.ofFloat(countdownCircle, "scaleY", 1.15f, 1.0f).apply {
                    duration = 540; startDelay = 360
                },
            )
            start()
        }
    }

    private fun startChallengePhase() {
        val sess = session ?: return
        val round = ++captureRound
        sess.setCapturePhase(CapturePhase.CHALLENGE)
        eventEmitter.emit(EventType.CHALLENGE_STARTED, mapOf("type" to (sess.challengeSpec?.type ?: "")))

        val presenter = challengePresenter
        if (presenter == null) {
            val remaining = (sess.uploadConfig?.captureDurationMs ?: 4500) - BASELINE_MS
            handler.postDelayed({ onCaptureComplete(round) }, remaining.coerceAtLeast(1000))
            return
        }

        showPhaseBadge("CHALLENGE")
        presenter.onChallengeComplete = { onCaptureComplete(round) }

        when (presenter) {
            is FollowDotChallenge -> {
                dotView.visibility = View.VISIBLE
                captureTitle.text = getString(R.string.usesense_follow_dot)
                captureSubtitle.text = ""
                presenter.onDotPositionChanged = { x, y, animate -> moveDot(x, y, animate) }
            }
            is HeadTurnChallenge -> {
                directionText.visibility = View.VISIBLE
                captureTitle.text = ""
                presenter.onDirectionChanged = { dir, _ -> showDirection(dir) }
            }
            is SpeakPhraseChallenge -> {
                speakPhraseLayout.visibility = View.VISIBLE
                captureTitle.text = ""
                presenter.onPhraseDisplay = { phrase, isRecording ->
                    phraseText.text = "\"$phrase\""
                    recordingIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
                }
            }
        }

        val totalDuration = presenter.spec.totalDurationMs
        challengeProgress.visibility = View.VISIBLE
        challengeProgress.max = 1000
        ValueAnimator.ofInt(0, 1000).apply {
            duration = totalDuration.toLong()
            addUpdateListener { challengeProgress.progress = it.animatedValue as Int }
            start()
        }

        presenter.start()
    }

    private fun moveDot(normalizedX: Float, normalizedY: Float, animate: Boolean) {
        val parentWidth = challengeOverlay.width.toFloat()
        val parentHeight = challengeOverlay.height.toFloat()
        val dotSize = dotView.width.toFloat()

        val targetX = normalizedX * parentWidth - dotSize / 2
        val targetY = normalizedY * parentHeight - dotSize / 2

        if (animate) {
            dotView.animate()
                .x(targetX).y(targetY)
                .setDuration(400)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .start()
        } else {
            dotView.x = targetX
            dotView.y = targetY
        }
    }

    private fun showDirection(direction: String) {
        directionText.text = when (direction) {
            "left" -> getString(R.string.usesense_turn_left)
            "right" -> getString(R.string.usesense_turn_right)
            "up" -> getString(R.string.usesense_turn_up)
            "down" -> getString(R.string.usesense_turn_down)
            "center" -> getString(R.string.usesense_turn_center)
            else -> direction
        }

        directionArrow.text = when (direction) {
            "left" -> "\u2190"; "right" -> "\u2192"
            "up" -> "\u2191"; "down" -> "\u2193"
            "center" -> "\u25CB"; else -> ""
        }

        val isCenter = direction == "center"
        directionCircle.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, directionCircleColors(isCenter)
        ).apply { shape = GradientDrawable.OVAL }
        directionCircle.visibility = View.VISIBLE

        // Enter animation
        directionCircle.scaleX = 0.5f; directionCircle.scaleY = 0.5f; directionCircle.alpha = 0f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(directionCircle, "scaleX", 0.5f, 1.1f).apply {
                    duration = 210; interpolator = OvershootInterpolator(1.5f)
                },
                ObjectAnimator.ofFloat(directionCircle, "scaleY", 0.5f, 1.1f).apply {
                    duration = 210; interpolator = OvershootInterpolator(1.5f)
                },
                ObjectAnimator.ofFloat(directionCircle, "alpha", 0f, 1f).apply { duration = 210 },
                ObjectAnimator.ofFloat(directionCircle, "scaleX", 1.1f, 1.0f).apply {
                    duration = 140; startDelay = 210
                },
                ObjectAnimator.ofFloat(directionCircle, "scaleY", 1.1f, 1.0f).apply {
                    duration = 140; startDelay = 210
                },
            )
            start()
        }
    }

    private fun showPhaseBadge(label: String) {
        phaseBadge.text = label
        phaseBadge.visibility = View.VISIBLE
    }

    // ─── Capture Complete → Upload → Result ──────────────────────────────

    private fun onCaptureComplete(round: Int) {
        if (round != captureRound || round == completedRound) {
            Log.w(TAG, "Ignoring duplicate capture completion (round $round, current $captureRound)")
            return
        }
        completedRound = round
        val sess = session ?: return
        if (stepUpRoundActive) {
            stepUpRoundActive = false
            finishStepUpRound(sess)
            return
        }
        try {
            sess.setCapturePhase(CapturePhase.DONE)
            sess.stopCapture()
            eventEmitter.emit(EventType.CAPTURE_COMPLETED)
            eventEmitter.emit(EventType.CHALLENGE_COMPLETED)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        }

        // A camera that delivered nothing is a system failure. Uploading an
        // empty capture would come back as a verdict against the subject.
        if (sess.capturedFrameCount == 0) {
            watchdog.disarm()
            showTerminalFailure(UseSenseError.noFramesCaptured(), WatchdogPhase.CAPTURE.wire)
            return
        }

        startFinalization(sess)
    }

    private fun startFinalization(sess: UseSenseSession, startAt: FinalizationPhase = FinalizationPhase.PREPARING) {
        val operations = object : FinalizationOperations {
            override suspend fun prepare() = Result.success(Unit)
            override suspend fun upload(onProgress: (Long, Long) -> Unit): Result<Unit> {
                sess.setCapturePhase(CapturePhase.UPLOADING)
                sess.onUploadProgress = onProgress
                return sess.uploadSignals().map { Unit }
            }
            override suspend fun complete(): Result<UseSenseResult> {
                sess.setCapturePhase(CapturePhase.COMPLETING)
                return sess.complete()
            }
            override fun takeStepUp(): StepUpInstruction? = sess.takeStepUp()
        }
        watchdog.enter(if (startAt == FinalizationPhase.COMPLETING) WatchdogPhase.COMPLETING else WatchdogPhase.PREPARING)
        finalizationJob?.cancel()
        finalizationJob = mainScope.launch {
            try {
                uploadOverlayTitle.text = if (flowType == FlowType.ENROLLMENT) {
                    getString(R.string.usesense_processing_enrollment)
                } else {
                    getString(R.string.usesense_processing_verification)
                }
                setStep(PageStep.FINALIZING)
                FinalizationCoordinator(operations).run(startAt) { update ->
                    if (watchdog.fired) return@run
                    when (update) {
                        is FinalizationUpdate.Phase -> {
                            watchdog.enter(
                                when (update.phase) {
                                    FinalizationPhase.PREPARING -> WatchdogPhase.PREPARING
                                    FinalizationPhase.UPLOADING -> WatchdogPhase.UPLOADING
                                    FinalizationPhase.COMPLETING -> WatchdogPhase.COMPLETING
                                },
                            )
                            uploadOverlaySubtitle.text = when (update.phase) {
                                FinalizationPhase.PREPARING -> "Preparing capture data"
                                FinalizationPhase.UPLOADING -> "Uploading securely"
                                FinalizationPhase.COMPLETING -> "Waiting for verification result"
                            }
                        }
                        is FinalizationUpdate.Progress -> {
                            watchdog.onProgress()
                            // Progress fires on the OkHttp writer thread.
                            uploadOverlaySubtitle.post {
                                uploadOverlaySubtitle.text =
                                    "${update.bytesSent * 100 / update.bytesTotal.coerceAtLeast(1)}% uploaded"
                            }
                        }
                        is FinalizationUpdate.Result -> {
                            watchdog.disarm()
                            finishHostedFinalization(update.result)
                        }
                        is FinalizationUpdate.StepUpRequired -> {
                            watchdog.enter(WatchdogPhase.CAPTURE)
                            runStepUpRound(sess, update.instruction)
                        }
                        is FinalizationUpdate.Recovery -> {
                            watchdog.disarm()
                            showTechnicalRecovery(sess, update)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                watchdog.disarm()
                Log.e(TAG, "Unhandled error in upload/complete pipeline", e)
                showTechnicalRecovery(
                    sess,
                    FinalizationUpdate.Recovery(
                        FinalizationPhase.COMPLETING,
                        UseSenseError.networkError(e.message),
                        setOf(RecoveryAction.RETRY, RecoveryAction.RESTART, RecoveryAction.EXIT),
                    ),
                )
            }
        }
    }

    /**
     * Round 1 uploaded and a server Step-up rule asked for one more challenge.
     * The camera is still bound behind the upload overlay: drop the overlay,
     * show "one more quick check", then run the requested challenge.
     */
    private fun runStepUpRound(sess: UseSenseSession, instruction: StepUpInstruction) {
        stepUpRoundActive = true
        setStep(PageStep.CAPTURE)
        challengeOverlay.visibility = View.VISIBLE
        captureTitle.text = "One more quick check"
        captureSubtitle.text = "Get ready..."
        handler.postDelayed({
            challengePresenter = sess.beginStepUpRound(instruction)
            startChallengePhase()
        }, STEP_UP_BRIEF_MS)
    }

    /** Upload round 2, then resume finalization at COMPLETING. */
    private fun finishStepUpRound(sess: UseSenseSession) {
        watchdog.enter(WatchdogPhase.UPLOADING)
        finalizationJob?.cancel()
        finalizationJob = mainScope.launch {
            setStep(PageStep.FINALIZING)
            uploadOverlaySubtitle.text = "Uploading securely"
            sess.uploadStepUpRound().fold(
                onSuccess = { startFinalization(sess, FinalizationPhase.COMPLETING) },
                onFailure = { e ->
                    watchdog.disarm()
                    val error = (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.networkError(e.message)
                    // Retry restarts finalization from round 1, which the server
                    // answers with the same step-up, so the round runs again.
                    showTechnicalRecovery(
                        sess,
                        FinalizationUpdate.Recovery(FinalizationPhase.UPLOADING, error, setOf(RecoveryAction.RESTART, RecoveryAction.EXIT)),
                    )
                },
            )
        }
    }

    private fun finishHostedFinalization(result: UseSenseResult) {
        if (!isDirectMode) {
            mainScope.launch {
                runCatching {
                    when (flowType) {
                        FlowType.ENROLLMENT -> apiClient.completeEnrollment(remoteId)
                        FlowType.VERIFICATION -> apiClient.completeRemoteSession(remoteId)
                    }
                }.onFailure { Log.e(TAG, "Hosted complete error (non-fatal)", it) }
            }
        }
        showResult(result.decision, result)
        pendingTerminalError = null
        callbackGate.success(result)
    }

    private fun showTechnicalRecovery(sess: UseSenseSession, recovery: FinalizationUpdate.Recovery) {
        eventEmitter.emit(EventType.ERROR, errorEvent(recovery.phase.name.lowercase(), recovery.error))
        showRecoveryDialog(recovery.error, RecoveryAction.RETRY in recovery.actions) {
            // Resume where it failed. If only /complete failed, the signals are already
            // on the server, and uploading them again resets a finished session.
            startFinalization(sess, recovery.phase)
        }
    }

    /**
     * A terminal failure of this attempt (watchdog, no frames, session refresh):
     * a clear error with its code, "Try again" for a clean attempt, or Exit.
     */
    private fun showTerminalFailure(error: UseSenseError, phase: String) {
        eventEmitter.emit(EventType.ERROR, errorEvent(phase, error))
        showRecoveryDialog(error, canRetryUpload = false, retry = null)
    }

    /**
     * A failure the subject can recover from: the error screen with
     * "Try again" (a clean attempt), "Retry upload" when the captured frames
     * can be re-sent, and "Exit". Replaces the stock AlertDialog, which showed
     * raw server text to end users.
     */
    private fun showRecoveryDialog(error: UseSenseError, canRetryUpload: Boolean, retry: (() -> Unit)?) {
        if (isFinishing || isDestroyed) return
        pendingTerminalError = error
        callbackGate.recovery()
        bindErrorScreen(error)

        val uploadRetry = retry.takeIf { canRetryUpload }
        if (uploadRetry != null) {
            // The frames are already captured: re-sending them is the cheaper fix.
            errorPrimaryButton.text = getString(R.string.usesense_retry_finalize)
            errorPrimaryButton.setOnClickListener { pendingTerminalError = null; uploadRetry() }
            errorSecondaryButton.text = getString(R.string.usesense_start_over)
            errorSecondaryButton.setOnClickListener { restartCapture() }
            errorSecondaryButton.visibility = View.VISIBLE
        } else {
            errorPrimaryButton.text = getString(R.string.usesense_retry)
            errorPrimaryButton.setOnClickListener { restartCapture() }
            errorSecondaryButton.visibility = View.GONE
        }
        errorExitButton.text = getString(R.string.usesense_exit)
        errorExitButton.setOnClickListener {
            callbackGate.exit(error)
            finish()
        }
        errorExitButton.visibility = View.VISIBLE
        setStep(PageStep.ERROR)
    }

    /** Icon, plain-language title and message, and the code as a caption. */
    private fun bindErrorScreen(error: UseSenseError?) {
        errorIcon.setImageResource(R.drawable.usesense_icon_error)
        errorIcon.setColorFilter(Color.parseColor("#DB4E33")) // error-6
        errorIconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FFF0EC")) // error-0
        }
        val (title, body) = errorCopy(error)
        errorTitle.text = getString(title)
        errorMessage.text = getString(body)
        val code = error?.serverCode?.takeIf { it.isNotBlank() } ?: error?.code?.toString()
        if (code != null) {
            errorCode.text = getString(R.string.usesense_error_code, code)
            errorCode.visibility = View.VISIBLE
        } else {
            errorCode.visibility = View.GONE
        }
    }

    private fun errorCopy(error: UseSenseError?): Pair<Int, Int> = when {
        error == null -> R.string.usesense_error_default_title to R.string.usesense_error_default_body
        error.code == UseSenseError.NETWORK_ERROR || error.code == UseSenseError.NETWORK_TIMEOUT ||
            error.code == UseSenseError.UPLOAD_FAILED || error.code == UseSenseError.UPLOAD_TIMEOUT ->
            R.string.usesense_error_connection_title to R.string.usesense_error_connection_body
        error.code == UseSenseError.CAPTURE_TIMEOUT || error.code == UseSenseError.CAPTURE_FAILED ||
            error.code == UseSenseError.ENCODING_FAILED || error.serverCode == UseSenseError.CODE_NO_FRAMES ->
            R.string.usesense_error_capture_title to R.string.usesense_error_capture_body
        error.code == UseSenseError.SESSION_EXPIRED || error.serverCode == "session_expired" ->
            R.string.usesense_error_expired_title to R.string.usesense_error_expired_body
        // Integration problems (bad key, quota, config) and server overload: the
        // subject can't fix these, so no capture advice.
        error.code == UseSenseError.QUOTA_EXCEEDED || error.code == UseSenseError.INVALID_CONFIG ||
            error.code == 401 || error.code == 402 || error.code == 403 ||
            error.code == 429 || error.code == 503 ->
            R.string.usesense_error_unavailable_title to R.string.usesense_error_unavailable_body
        else -> R.string.usesense_error_default_title to R.string.usesense_error_default_body
    }

    private fun errorEvent(phase: String, error: UseSenseError): Map<String, Any> = buildMap {
        put("phase", phase)
        put("error", error.message)
        put("code", error.code)
        error.serverCode?.let { put("error_code", it) }
    }

    /** The watchdog fired: abandon the stuck work without waiting on it. */
    private fun onWatchdogTimeout(error: UseSenseError) {
        if (isFinishing || isDestroyed) return
        Log.w(TAG, "Capture watchdog fired: ${error.serverCode} ${error.details}")
        abortAttempt()
        showTerminalFailure(error, (error.details?.get("phase") as? String) ?: "unknown")
    }

    /**
     * Stop everything the current attempt started: timers, the challenge, the
     * finalization coroutine and frame capture. Cancels without joining, so a
     * wedged native call cannot hold the UI.
     */
    private fun abortAttempt() {
        watchdog.disarm()
        finalizationJob?.cancel()
        finalizationJob = null
        handler.removeCallbacksAndMessages(null)
        challengePresenter?.stop()
        stepUpRoundActive = false
        runCatching { session?.stopCapture() }
    }

    /**
     * "Try again": a clean attempt. The old session is released (its mesh,
     * integrity and sensor jobs cancelled) and a new one is created -- in the
     * Flows path from a fresh init-session, never the consumed credentials.
     */
    private fun restartCapture() {
        abortAttempt()
        pendingTerminalError = null
        challengePresenter = null
        frameCaptureManager = null
        session?.release()
        session = null
        runCatching {
            val future = ProcessCameraProvider.getInstance(this)
            if (future.isDone) future.get().unbindAll()
        }
        mainScope.launch { initAndStartCapture() }
    }

    private fun deliverError(error: UseSenseError) {
        watchdog.disarm()
        callbackGate.exit(error)
        if (!isFinishing) {
            pendingTerminalError = error
            showError(error.message)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onSessionStateChanged(state: SessionState) {
        // Logged via event emitter
    }

    // ─── Result Screen ───────────────────────────────────────────────────

    private fun showResult(decision: String, result: UseSenseResult?) {
        val isEnrollment = flowType == FlowType.ENROLLMENT
        val orgName = effectiveBranding.displayName

        when (decision) {
            UseSenseResult.DECISION_APPROVE -> {
                resultIcon.setImageResource(R.drawable.usesense_icon_success)
                resultIcon.setColorFilter(Color.parseColor("#00AA88")) // MatchSense Green-6
                resultIconCircle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E6FBF5")) // green-0
                }
                resultTitle.text = if (isEnrollment) {
                    getString(R.string.usesense_result_success_enrollment_title)
                } else {
                    getString(R.string.usesense_result_success_title)
                }
                resultBody.text = getString(R.string.usesense_result_success_body)
                if (actionContext != null) {
                    resultActionBox.visibility = View.VISIBLE
                    resultActionText.text = actionContext?.displayText
                }
            }
            UseSenseResult.DECISION_MANUAL_REVIEW -> {
                resultIcon.setImageResource(R.drawable.usesense_icon_review)
                resultIcon.setColorFilter(Color.parseColor("#DB973A")) // warning-6
                resultIconCircle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FFF7E8")) // warning-0
                }
                resultTitle.text = getString(R.string.usesense_result_review_title)
                resultBody.text = getString(R.string.usesense_result_review_body)
            }
            else -> {
                resultIcon.setImageResource(R.drawable.usesense_icon_denied)
                resultIcon.setColorFilter(Color.parseColor("#DB4E33")) // error-6
                resultIconCircle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FFF0EC")) // error-0
                }
                resultTitle.text = if (isEnrollment) {
                    getString(R.string.usesense_result_failed_enrollment_title)
                } else {
                    getString(R.string.usesense_result_failed_title)
                }
                resultBody.text = getString(R.string.usesense_result_failed_body, orgName)
            }
        }

        val redirectUrl = effectiveBranding.redirectUrl
        if (redirectUrl != null) {
            resultButton.text = getString(R.string.usesense_continue)
            resultButton.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl))) } catch (_: Exception) {}
                finish()
            }
        } else {
            resultButton.text = getString(R.string.usesense_close)
            resultButton.setOnClickListener { finish() }
        }

        setStep(PageStep.RESULT)
    }

    /** A terminal failure with a cause: an API error keeps its own code and copy. */
    private fun showError(cause: Throwable, fallback: String) {
        if (cause is ApiException && pendingTerminalError == null) {
            pendingTerminalError = cause.useSenseError
        }
        showError(cause.message ?: fallback)
    }

    /**
     * A terminal error: nothing on this screen can fix it, so it offers Close.
     * The raw message goes to the host through the callback, not to the subject.
     */
    private fun showError(message: String) {
        if (::watchdog.isInitialized) watchdog.disarm()
        // A bare message has no type: show the generic copy and no code, rather
        // than dressing it up as a capture failure (an API-key error used to say
        // "We couldn't see your face clearly").
        val typed = pendingTerminalError
        if (typed == null) {
            pendingTerminalError = UseSenseError.captureFailed(message)
        }
        Log.w(TAG, "Terminal error shown: $message")
        bindErrorScreen(typed)
        errorPrimaryButton.text = getString(R.string.usesense_close)
        errorPrimaryButton.setOnClickListener { finish() }
        errorSecondaryButton.visibility = View.GONE
        errorExitButton.visibility = View.GONE
        setStep(PageStep.ERROR)
    }

    // ─── Screen Management ───────────────────────────────────────────────

    private val allScreens by lazy {
        listOf(loadingScreen, errorScreen, introScreen, actionReviewScreen,
            permissionScreen, captureScreen, finalizingScreen, resultScreen)
    }

    private fun setStep(step: PageStep) {
        // FINALIZING stays on the capture screen with an overlay (Section 5.8/5.9)
        val isCaptureMode = step == PageStep.CAPTURE || step == PageStep.FINALIZING

        if (step != PageStep.FINALIZING) {
            allScreens.forEach { it.visibility = View.GONE }
        } else {
            // Finalizing overlays the capture screen; a retry from the error
            // screen must not leave the error showing on top of it.
            errorScreen.visibility = View.GONE
            resultScreen.visibility = View.GONE
        }

        when (step) {
            PageStep.LOADING -> loadingScreen.visibility = View.VISIBLE
            PageStep.ERROR -> errorScreen.visibility = View.VISIBLE
            PageStep.INTRODUCTION -> introScreen.visibility = View.VISIBLE
            PageStep.ACTION_REVIEW -> actionReviewScreen.visibility = View.VISIBLE
            PageStep.PERMISSION -> permissionScreen.visibility = View.VISIBLE
            PageStep.CAPTURE -> {
                captureScreen.visibility = View.VISIBLE
                uploadOverlay.visibility = View.GONE
            }
            PageStep.FINALIZING -> {
                // Keep capture screen visible — show dark overlay on camera
                captureScreen.visibility = View.VISIBLE
                uploadOverlay.visibility = View.VISIBLE
            }
            PageStep.RESULT -> resultScreen.visibility = View.VISIBLE
        }

        // Section 11.8: Hide branded header/footer during capture, show capture footer
        headerBar.visibility = if (isCaptureMode) View.GONE else View.VISIBLE
        headerDivider.visibility = if (isCaptureMode) View.GONE else View.VISIBLE
        footerText.visibility = if (isCaptureMode) View.GONE else View.VISIBLE
        footerDivider.visibility = if (isCaptureMode) View.GONE else View.VISIBLE

        // Reset capture overlays when leaving capture
        if (!isCaptureMode) {
            hideCaptureOverlays()
        }
    }

    private fun hideCaptureOverlays() {
        challengeOverlay.visibility = View.GONE
        faceGuideOverlay.visibility = View.GONE
        countdownOverlay.visibility = View.GONE
        instructionsOverlay.visibility = View.GONE
        uploadOverlay.visibility = View.GONE
        dotView.visibility = View.GONE
        directionText.visibility = View.GONE
        directionCircle.visibility = View.GONE
        speakPhraseLayout.visibility = View.GONE
        baselineOvalView.visibility = View.GONE
        phaseBadge.visibility = View.GONE
        challengeProgress.visibility = View.GONE
        qualityIndicator.visibility = View.GONE
        qualityBanner.visibility = View.GONE
    }

    override fun onDestroy() {
        // Never end silently: the host hears an error (if one is on screen) or
        // a cancellation. The gate makes this a no-op after a success/exit.
        if (!isChangingConfigurations) {
            pendingTerminalError?.let { callbackGate.exit(it) } ?: callbackGate.cancel()
        }
        if (::watchdog.isInitialized) watchdog.disarm()
        finalizationJob?.cancel()
        super.onDestroy()
        mainScope.cancel()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        session?.release()
        pendingConfig = null
        pendingRequest = null
        pendingDirectCallback = null
        pendingSessionFactory = null
    }

    companion object {
        private const val TAG = "HostedPageActivity"
        private const val EXTRA_FLOW_TYPE = "flow_type"
        private const val EXTRA_REMOTE_ID = "remote_id"
        private const val EXTRA_DIRECT_MODE = "direct_mode"
        private const val BASELINE_MS = 2000L
        /** How long "One more quick check" shows before a server step-up challenge starts. */
        private const val STEP_UP_BRIEF_MS = 1500L
        private const val COUNTDOWN_MS = 3000L

        /** Ceiling on minting a fresh Flows session for "Try again". */
        private const val SESSION_REFRESH_TIMEOUT_MS = 30_000L

        internal var pendingConfig: UseSenseConfig? = null
        internal var pendingRequest: VerificationRequest? = null
        internal var pendingDirectCallback: UseSenseCallback? = null

        // Flows runner path: pre-minted session credentials returned by
        // /sdk/flow-runs/:id/init-session. When set, initAndStartCapture()
        // injects them instead of calling createSession (cleared after use).
        internal var pendingPrebuiltSession: com.usesense.sdk.api.models.CreateSessionResponse? = null

        /** Flows path: mints a fresh session for "Try again" (the first one is consumed). */
        internal var pendingSessionFactory: (suspend () -> com.usesense.sdk.api.models.CreateSessionResponse)? = null

        /**
         * Launch the hosted page UI for a direct SDK verification/enrollment flow.
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

        /**
         * Flows-runner entry point. Launches the same hosted capture UI as
         * startDirect but with pre-minted session credentials, so the activity
         * skips the createSession API call. The callback fires on the same
         * UseSenseCallback contract as direct mode; the Flows runner extracts
         * sessionId + identityId from the success result and advances the run.
         */
        internal fun startWithPrebuiltSession(
            context: Context,
            config: UseSenseConfig,
            response: com.usesense.sdk.api.models.CreateSessionResponse,
            sessionType: SessionType,
            callback: UseSenseCallback,
            refreshSession: (suspend () -> com.usesense.sdk.api.models.CreateSessionResponse)? = null,
        ) {
            pendingConfig = config
            pendingRequest = VerificationRequest(sessionType = sessionType)
            pendingDirectCallback = callback
            pendingPrebuiltSession = response
            pendingSessionFactory = refreshSession
            val flowType = if (sessionType == SessionType.ENROLLMENT) "enrollment" else "verification"
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
