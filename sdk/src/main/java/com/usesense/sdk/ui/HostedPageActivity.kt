package com.usesense.sdk.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.usesense.sdk.R
import com.usesense.sdk.*
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.*
import com.usesense.sdk.capture.FrameCaptureManager
import com.usesense.sdk.capture.ImageQualityAnalyzer
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.SessionState
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
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // Header views
    private lateinit var headerLogo: ImageView
    private lateinit var headerText: TextView

    // Screen views
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

        if (branding.logoUrl != null) {
            headerLogo.visibility = View.VISIBLE
            headerText.visibility = View.GONE
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
        } catch (_: Exception) {}

        try {
            val primaryColor = Color.parseColor(branding.primaryColor)
            introGetStartedButton.setBackgroundColor(primaryColor)
            actionReviewVerifyButton.setBackgroundColor(primaryColor)
        } catch (_: Exception) {}
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
                    showError(it.message ?: "Failed to initialize session")
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

            val createResult = sess.createSession()
            createResult.onFailure { e ->
                eventEmitter.emit(EventType.ERROR, mapOf("error" to (e.message ?: "")))
                val error = (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                    ?: UseSenseError.networkError(e.message)
                deliverError(error)
                return
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

        instructionsCta.setOnClickListener {
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }

            cameraPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Mirror front camera preview for natural appearance (spec Section 5.2)
            // Frames sent to server are NOT mirrored — only the preview is.
            cameraPreview.scaleX = -1f

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
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
                    showFaceGuide()
                } else {
                    sess.startCapture()
                    eventEmitter.emit(EventType.CAPTURE_STARTED)
                    if (sess.requiresAudio) {
                        sess.startAudioRecording()
                        eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
                    }
                    startBaselinePhase()
                }
            } catch (e: Exception) {
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

        faceGuideReadyButton.setOnClickListener {
            faceGuideOverlay.visibility = View.GONE
            sess.startCapture()
            eventEmitter.emit(EventType.CAPTURE_STARTED)
            if (sess.requiresAudio) {
                sess.startAudioRecording()
                eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
            }
            startBaselinePhase()
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
        sess.setCapturePhase(CapturePhase.CHALLENGE)
        eventEmitter.emit(EventType.CHALLENGE_STARTED, mapOf("type" to (sess.challengeSpec?.type ?: "")))

        val presenter = challengePresenter
        if (presenter == null) {
            val remaining = (sess.uploadConfig?.captureDurationMs ?: 4500) - BASELINE_MS
            handler.postDelayed({ onCaptureComplete() }, remaining.coerceAtLeast(1000))
            return
        }

        showPhaseBadge("CHALLENGE")
        presenter.onChallengeComplete = { onCaptureComplete() }

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
        val startColor = if (isCenter) Color.parseColor("#4F7CFF") else Color.parseColor("#3D63DB")
        val endColor = if (isCenter) Color.parseColor("#7C5CFC") else Color.parseColor("#4F7CFF")
        directionCircle.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(startColor, endColor)
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

    private fun onCaptureComplete() {
        val sess = session ?: return
        try {
            sess.setCapturePhase(CapturePhase.DONE)
            sess.stopCapture()
            eventEmitter.emit(EventType.CAPTURE_COMPLETED)
            eventEmitter.emit(EventType.CHALLENGE_COMPLETED)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        }

        mainScope.launch {
            try {
                // Section 5.8/5.9: Show dark overlay on camera during upload/completing
                uploadOverlayTitle.text = if (flowType == FlowType.ENROLLMENT) {
                    getString(R.string.usesense_processing_enrollment)
                } else {
                    getString(R.string.usesense_processing_verification)
                }
                setStep(PageStep.FINALIZING)

                sess.setCapturePhase(CapturePhase.UPLOADING)
                eventEmitter.emit(EventType.UPLOAD_STARTED)

                val uploadResult = sess.uploadSignals()
                uploadResult.onFailure { e ->
                    eventEmitter.emit(EventType.ERROR, mapOf("phase" to "upload", "error" to (e.message ?: "")))
                    deliverError(
                        (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                            ?: UseSenseError.uploadFailed()
                    )
                    return@launch
                }
                eventEmitter.emit(EventType.UPLOAD_COMPLETED)

                sess.setCapturePhase(CapturePhase.COMPLETING)
                eventEmitter.emit(EventType.COMPLETE_STARTED)
                val verdictResult = sess.complete()
                verdictResult.onSuccess { result ->
                    eventEmitter.emit(EventType.DECISION_RECEIVED, mapOf(
                        "decision" to result.decision,
                        "session_id" to result.sessionId,
                    ))

                    // For remote mode, also call the hosted complete endpoint
                    if (!isDirectMode) {
                        try {
                            when (flowType) {
                                FlowType.ENROLLMENT -> apiClient.completeEnrollment(remoteId)
                                FlowType.VERIFICATION -> apiClient.completeRemoteSession(remoteId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Hosted complete error (non-fatal)", e)
                        }
                    }

                    showResult(result.decision, result)
                    pendingDirectCallback?.onSuccess(result)
                }
                verdictResult.onFailure { e ->
                    eventEmitter.emit(EventType.ERROR, mapOf("phase" to "complete", "error" to (e.message ?: "")))
                    val error = (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.networkError(e.message)
                    deliverError(error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error in upload/complete pipeline", e)
                deliverError(UseSenseError.captureFailed("Unexpected error: ${e.message}"))
            }
        }
    }

    private fun deliverError(error: UseSenseError) {
        pendingDirectCallback?.onError(error)
        if (!isFinishing) {
            showResult(UseSenseResult.DECISION_REJECT, null)
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

    private fun showError(message: String) {
        errorMessage.text = message
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
        super.onDestroy()
        mainScope.cancel()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        session?.release()
        pendingConfig = null
        pendingRequest = null
        pendingDirectCallback = null
    }

    companion object {
        private const val TAG = "HostedPageActivity"
        private const val EXTRA_FLOW_TYPE = "flow_type"
        private const val EXTRA_REMOTE_ID = "remote_id"
        private const val EXTRA_DIRECT_MODE = "direct_mode"
        private const val BASELINE_MS = 2000L
        private const val COUNTDOWN_MS = 3000L

        internal var pendingConfig: UseSenseConfig? = null
        internal var pendingRequest: VerificationRequest? = null
        internal var pendingDirectCallback: UseSenseCallback? = null

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
