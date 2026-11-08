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
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
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
import com.usesense.sdk.internal.BrandHeader
import com.usesense.sdk.internal.BrandingResolver
import com.usesense.sdk.internal.ResolvedBranding
import com.usesense.sdk.internal.CameraLiveGate
import com.usesense.sdk.R
import com.usesense.sdk.*
import com.usesense.sdk.api.ApiException
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.*
import com.usesense.sdk.capture.FrameCaptureManager
import com.usesense.sdk.capture.ImageQualityAnalyzer
import com.usesense.sdk.capture.scan.GateFrame
import com.usesense.sdk.capture.scan.GateHint
import com.usesense.sdk.capture.scan.GateResult
import com.usesense.sdk.capture.scan.HintScheduler
import com.usesense.sdk.capture.scan.LiveFaceAnalyzer
import com.usesense.sdk.capture.scan.PreviewMapper
import com.usesense.sdk.capture.scan.QualityGate
import com.usesense.sdk.capture.scan.ScanColors
import com.usesense.sdk.capture.scan.ScanController
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.Bounded
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.CaptureWatchdog
import com.usesense.sdk.internal.DebugCaptureHooks
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
    /** Resolved once per session (and again as dashboard branding arrives); see BrandingResolver. */
    private var resolvedBranding: ResolvedBranding = BrandingResolver.resolve(null, null)
    private var brandingTrace: String? = null
    private var headerLogoUrl: String? = null
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
    private lateinit var uploadOverlaySpinner: ProgressBar

    // Scan overlay (LiveSense capture redesign): oval, hint, ring, contours, shutter
    private lateinit var faceGuideCloseButton: TextView
    private lateinit var scanView: ContourScanView
    private lateinit var hintPill: LinearLayout
    private lateinit var hintTitle: TextView
    private lateinit var hintDetail: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var hintGroup: LinearLayout
    private lateinit var uploadProgress: ProgressBar
    private var uploadProgressAnimator: ValueAnimator? = null

    /** Where the capture screen is in the scan flow. */
    private enum class ScanStage { IDLE, ALIGN, PASSIVE, CHALLENGE }
    private var scanStage = ScanStage.IDLE

    /** The quality gate's live face mesh (display and gating only; uploads nothing). */
    private var liveAnalyzer: LiveFaceAnalyzer? = null
    private val qualityGate = QualityGate()
    private val hintScheduler = HintScheduler()
    private var scanController: ScanController? = null
    private var lastGate: GateResult? = null
    private var lastGateAt = 0L
    private var lastGateLogAt = 0L
    private var alignStartedAt = 0L
    /** No mesh gate on this device (MediaPipe missing or too slow to load): shutter only. */
    private var gateUnavailable = false
    /** session.startCapture() runs once per attempt; a dropped window restarts the buffer instead. */
    private var sessionCaptureStarted = false
    private var shutterAnnounced = false
    /** The shutter was offered while the gate was loading; it stays once shown. */
    private var shutterKept = false
    private var scanCopyShown = false
    private var fillLightSince: Long? = null
    private var lightStatusBarDefault: Boolean? = null
    private var systemBars = androidx.core.graphics.Insets.NONE
    private var captureFullBleed = false

    /** Pad the root for the system bars; the capture screen keeps only the bottom (camera under the status bar). */
    private fun applySystemBarPadding(root: View) {
        val b = systemBars
        root.setPadding(b.left, if (captureFullBleed) 0 else b.top, b.right, b.bottom)
        (faceGuideCloseButton.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
            val top = (16 * resources.displayMetrics.density).toInt() + if (captureFullBleed) b.top else 0
            if (lp.topMargin != top) {
                lp.topMargin = top
                faceGuideCloseButton.layoutParams = lp
            }
        }
    }
    private var savedBrightness: Float? = null
    private val pointXs = FloatArray(MAX_LANDMARKS)
    private val pointYs = FloatArray(MAX_LANDMARKS)
    private val pointZs = FloatArray(MAX_LANDMARKS)
    private val mapped = FloatArray(2)
    private val scanTicker = object : Runnable {
        override fun run() {
            onScanTick()
            if (scanStage == ScanStage.ALIGN || scanStage == ScanStage.PASSIVE) {
                handler.postDelayed(this, SCAN_TICK_MS)
            }
        }
    }

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
    private lateinit var resultRetryButton: MaterialButton
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
    /**
     * Launched by the SDK (session mode or the Flows runner) rather than by a
     * remote hosted link. Such sessions follow the iOS capture sequence:
     * straight to the camera, instructions only when the step has a challenge,
     * and the face guide ("I'm Ready") before every capture. The remote hosted
     * page keeps its intro / action review.
     */
    private var usesSdkCaptureSequence = false

    /**
     * Launched by the Flows runner (pre-minted session). The run's outcome is
     * decided on the server when this capture completes, so the result screen
     * offers no "Try Again": retries belong to the Flow's own policy.
     */
    private var isFlowRun = false

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

    /**
     * The decision on the result screen. As on iOS it reaches the host when the
     * subject taps the primary button (or leaves), not before, so "Try Again"
     * on a reject can start a clean attempt instead.
     */
    private var pendingResult: UseSenseResult? = null

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
        // Draw edge to edge and pad by hand: the capture screen runs under the
        // status bar (full-bleed camera), every other screen keeps clear of it.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        val root = (findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        root.fitsSystemWindows = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, wi ->
            systemBars = wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            applySystemBarPadding(v)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
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
        resolveBranding(config.branding, pendingServerBranding ?: pendingPrebuiltSession?.branding?.toServerBranding())
        applyBranding()

        usesSdkCaptureSequence = isDirectMode
        isFlowRun = pendingPrebuiltSession != null
        if (usesSdkCaptureSequence) {
            mainScope.launch { initAndStartCapture() }
        } else if (isDirectMode) {
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
        uploadOverlaySpinner = findViewById(R.id.hostedUploadOverlaySpinner)
        faceGuideCloseButton = findViewById(R.id.hostedFaceGuideCloseButton)
        scanView = findViewById(R.id.hostedScanView)
        hintPill = findViewById(R.id.hostedHintPill)
        hintTitle = findViewById(R.id.hostedHintTitle)
        hintDetail = findViewById(R.id.hostedHintDetail)
        shutterButton = findViewById(R.id.hostedShutterButton)
        hintGroup = findViewById(R.id.hostedHintGroup)
        uploadProgress = findViewById(R.id.hostedUploadProgress)
        // The hint pill's centre sits at 74% of the screen height (shared spec).
        faceGuideOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeHint() }
        hintPill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeHint() }

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
        resultRetryButton = findViewById(R.id.resultRetryButton)
        footerText = findViewById(R.id.footerText)
        footerDivider = findViewById(R.id.footerDivider)
    }

    // ─── Branding ────────────────────────────────────────────────────────

    /** Resolve [code] over [server] (BrandingResolver) and trace the outcome. */
    private fun resolveBranding(code: BrandingConfig?, server: ServerBranding?) {
        resolvedBranding = BrandingResolver.resolve(code, server)
        brandingTrace = BrandingResolver.trace("capture", resolvedBranding, brandingTrace)
    }

    /** [withName] filled with the org's name, or the name-free [generic] when there is none. */
    private fun orgText(withName: Int, generic: Int): String =
        resolvedBranding.displayName.value?.let { getString(withName, it) } ?: getString(generic)

    /** Whether the header bar has anything to show (logo or name). */
    private val hasHeader: Boolean get() = resolvedBranding.header != BrandHeader.None

    private fun applyBranding() {
        val branding = resolvedBranding

        // As iOS (HostedPageHeader): the logo alone when there is one; the name
        // shows while it loads and stays if there is no logo or it fails. With
        // neither, there is no header (no invented "UseSense").
        when (val header = branding.header) {
            is BrandHeader.Logo -> {
                headerText.text = header.fallbackName.orEmpty()
                headerText.visibility = if (header.fallbackName != null) View.VISIBLE else View.GONE
                if (headerLogoUrl != header.url) {
                    headerLogoUrl = header.url
                    headerLogo.visibility = View.GONE
                    mainScope.launch(Dispatchers.IO) {
                        val bitmap = runCatching {
                            java.net.URL(header.url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                        }.getOrNull()
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                if (headerLogoUrl != header.url) return@withContext
                                headerLogo.setImageBitmap(bitmap)
                                headerLogo.visibility = View.VISIBLE
                                headerText.visibility = View.GONE
                            }
                        }
                    }
                }
            }
            is BrandHeader.Name -> {
                headerLogoUrl = null
                headerLogo.visibility = View.GONE
                headerText.text = header.name
                headerText.visibility = View.VISIBLE
            }
            BrandHeader.None -> {
                headerLogoUrl = null
                headerLogo.visibility = View.GONE
                headerText.visibility = View.GONE
            }
        }
        if (!hasHeader) {
            headerBar.visibility = View.GONE
            headerDivider.visibility = View.GONE
        }

        try {
            val primaryColor = Color.parseColor(branding.primaryColor.value)
            headerText.setTextColor(primaryColor)
        } catch (_: Exception) {}

        try {
            val primaryColor = Color.parseColor(branding.primaryColor.value)
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
            val primaryColor = Color.parseColor(branding.primaryColor.value)
            introGetStartedButton.setBackgroundColor(primaryColor)
            actionReviewVerifyButton.setBackgroundColor(primaryColor)
        } catch (_: Exception) {}

        applyBrandToCaptureScreens()
    }

    /** The brand colour, or null when it is unparseable. */
    private fun brandColor(): Int? = runCatching { Color.parseColor(resolvedBranding.primaryColor.value) }.getOrNull()

    /** True when the org or integrator set a colour (not the built-in DeepSense Blue). */
    private val isBranded: Boolean
        get() = resolvedBranding.isBrandColorSet

    /** Text on a brand-filled surface: the appearance's primaryForeground, else white. */
    private fun brandForeground(): Int =
        resolvedBranding.appearance?.colors?.primaryForeground
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
        uploadOverlaySpinner.indeterminateTintList = tint
        challengeProgress.progressTintList = tint
        permissionIcon.imageTintList = tint
        permissionButton.backgroundTintList = tint
        permissionButton.setTextColor(brandForeground())

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
        resolveBranding(pendingConfig?.branding, orgBranding?.let {
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
        resolveBranding(pendingConfig?.branding, orgBranding?.let {
            ServerBranding(it.displayName, it.logoUrl, it.primaryColor, it.redirectUrl)
        })
        applyBranding()
        apiClient.markSessionOpened(remoteId)
        showActionReview()
    }

    // ─── Introduction & Action Review ────────────────────────────────────

    private fun showIntroduction() {
        introHeadline.text = orgText(R.string.usesense_intro_title, R.string.usesense_intro_title_generic)
        introDescription.text = getString(R.string.usesense_intro_body)

        introGetStartedButton.setOnClickListener {
            mainScope.launch { initAndStartCapture() }
        }

        setStep(PageStep.INTRODUCTION)
    }

    private fun showActionReview() {
        val hasAction = actionContext != null

        if (hasAction) {
            actionReviewHeadline.text = orgText(R.string.usesense_action_review_action, R.string.usesense_action_review_action_generic)
            actionReviewDescription.visibility = View.GONE
            actionContextCard.visibility = View.VISIBLE
            actionContextText.text = actionContext?.displayText ?: ""

            try {
                val primaryColor = Color.parseColor(resolvedBranding.primaryColor.value)
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
            actionReviewHeadline.text = orgText(R.string.usesense_action_review_plain, R.string.usesense_action_review_plain_generic)
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
                // Session mode: the dashboard layer arrives with the session.
                sess.serverBranding?.let {
                    resolveBranding(config.branding, it)
                    applyBranding()
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

    private fun hasChallengeToExplain(): Boolean {
        val type = session?.challengeSpec?.type
        return !type.isNullOrBlank() && !type.equals("none", ignoreCase = true)
    }

    private fun onPermissionsGranted() {
        // As iOS: instructions only for a step with a challenge to explain.
        if (usesSdkCaptureSequence && !hasChallengeToExplain()) {
            // showInstructions() is what switches to the capture screen; without
            // it the camera started behind the "Getting ready…" loading screen.
            setStep(PageStep.CAPTURE)
            startCameraAndCapture()
            return
        }
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
        showCaptureText(true)
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

        // A fresh attempt: fresh scan clock, gate history and hints.
        scanController = null
        sessionCaptureStarted = false
        gateUnavailable = false
        shutterKept = false
        shutterAnnounced = false
        qualityGate.reset()
        hintScheduler.reset()
        if (pendingConfig?.guidedCaptureEnabled != false) {
            ensureLiveAnalyzer()
        } else {
            // Guided capture off: manual start from the shutter, no gate.
            gateUnavailable = true
        }

        // The scan overlay carries its own copy; no second line under the camera.
        showCaptureText(false)

        startCamera()
    }

    private fun startCamera() {
        val sess = session ?: return
        // System-paced from here: the camera must bind and the capture must
        // finish, upload and settle within the watchdog's budget.
        watchdog.arm(WatchdogPhase.CAMERA)
        cameraLive.reset()
        if (startFakeCamera(sess)) return
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
                        // The quality gate takes a small copy when it is free.
                        liveAnalyzer?.offer(imageProxy)
                        // Quality first: the frame-capture analyzer closes the
                        // ImageProxy, after which its image is gone. Run after it,
                        // the quality check never saw a frame (no indicator, no
                        // guidance banner on any hosted capture).
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
                                    // Whole-frame report for the public event only; the
                                    // on-screen guidance comes from the face-area gate.
                                    val report = qualityAnalyzer.analyze(mediaImage)
                                    eventEmitter.emit(EventType.IMAGE_QUALITY_CHECK, mapOf(
                                        "score" to report.overallScore,
                                        "acceptable" to report.isAcceptable,
                                        "blur" to report.blurLevel.name,
                                        "lighting" to report.lightingLevel.name,
                                    ))
                                }
                            } catch (_: Exception) {}
                        }

                        // Closes the frame (every path).
                        frameCaptureManager?.createAnalyzer()?.analyze(imageProxy)
                            ?: imageProxy.close()
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

                // Every session aligns first: the oval guide and the quality
                // gate, then capture starts by itself once every check passes
                // (or from the shutter). Aligning waits on the subject, so it
                // is not supervised by the watchdog.
                watchdog.disarm()
                enterAlign(sess)
            } catch (e: Exception) {
                watchdog.disarm()
                showCameraError(getString(R.string.usesense_camera_unavailable_message))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─── Debug test rig (debug builds only; see DebugCaptureHooks) ───────

    private val debugHooks: DebugCaptureHooks? by lazy { DebugCaptureHooks.load(this) }
    private var fakePreview: ImageView? = null

    /** Feed the pipeline from the debug fake camera instead of CameraX. False when not in use. */
    private fun startFakeCamera(sess: UseSenseSession): Boolean {
        val hooks = debugHooks ?: return false
        val preview = fakePreview ?: ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            scaleX = -1f // mirrored, as the front-camera preview
            videoContainer.addView(this, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }.also { fakePreview = it }
        preview.visibility = View.VISIBLE
        scanView.frozen = hooks.frozen
        sess.setCaptureInfo("front", "fake")
        hooks.start { frame ->
            if (cameraLive.markFrame()) runOnUiThread { if (session === sess && !isFinishing) cameraLive.onLive() }
            liveAnalyzer?.offerBitmap(frame)
            frameCaptureManager?.submitBitmap(frame)
            runOnUiThread { if (!isFinishing) preview.setImageBitmap(frame) }
        }
        watchdog.disarm()
        enterAlign(sess)
        return true
    }

    // ─── Scan: align, quality gate, passive scan ─────────────────────────

    private fun ensureLiveAnalyzer() {
        val analyzer = liveAnalyzer ?: LiveFaceAnalyzer(applicationContext) { frame ->
            runOnUiThread { if (!isFinishing && !isDestroyed) onGateFrame(frame) }
        }.also { liveAnalyzer = it }
        analyzer.initialize { ok -> if (!ok) runOnUiThread { gateUnavailable = true } }
    }

    /** About 3 s of banked capture (SPEC), or the server's shorter duration if it asks for less. */
    private fun scanTotalMs(sess: UseSenseSession): Long =
        (sess.uploadConfig?.captureDurationMs ?: DEFAULT_SCAN_MS).toLong()
            .coerceAtMost(TARGET_SCAN_MS).coerceAtLeast(MIN_SCAN_MS)

    /** The oval guide: hints until every check passes, then the scan starts by itself. */
    private fun enterAlign(sess: UseSenseSession) {
        sess.setCapturePhase(CapturePhase.FACE_GUIDE)
        scanStage = ScanStage.ALIGN
        val now = SystemClock.elapsedRealtime()
        alignStartedAt = now
        val controller = scanController ?: ScanController(scanTotalMs(sess)).also { scanController = it }
        controller.beginAlign(now)

        challengeOverlay.visibility = View.GONE
        faceGuideOverlay.visibility = View.VISIBLE
        showCaptureText(false)
        scanView.colors = ScanColors.resolve(resolvedBranding.appearance)
        scanView.tone = ContourScanView.Tone.NEUTRAL
        scanView.scanning = false
        scanView.paused = false
        scanView.resetProgress()
        hintScheduler.reset()
        scanCopyShown = false
        renderHint(null)
        shutterButton.visibility = View.GONE

        faceGuideCloseButton.setOnClickListener {
            // As iOS: closing the capture cancels it.
            abortAttempt()
            callbackGate.cancel()
            finish()
        }
        shutterButton.setOnClickListener { startScan(sess, manual = true) }

        liveAnalyzer?.active = true
        handler.removeCallbacks(scanTicker)
        handler.post(scanTicker)
    }

    /** A gate frame from the live mesh (main thread). */
    private fun onGateFrame(frame: GateFrame) {
        if (scanStage != ScanStage.ALIGN && scanStage != ScanStage.PASSIVE) return
        val sess = session ?: return
        val oval = scanView.ovalSpec() ?: return
        val now = SystemClock.elapsedRealtime()
        val mapper = PreviewMapper(frame.imageWidth, frame.imageHeight, scanView.width.toFloat(), scanView.height.toFloat())
        val result = qualityGate.evaluate(frame, mapper, oval, fillLightSince?.let { now - it })
        lastGate = result
        lastGateAt = now
        if (now - lastGateLogAt >= 1000 && Log.isLoggable(GATE_TAG, Log.DEBUG)) {
            // adb shell setprop log.tag.UseSenseGate DEBUG
            lastGateLogAt = now
            Log.d(GATE_TAG, "faces=${frame.faceCount} failing=${result.failing} ${result.metrics ?: ""}")
        }
        if (result.faceTooDark) turnOnFillLight()

        val pts = frame.points
        val n = minOf(frame.landmarkCount, MAX_LANDMARKS)
        if (pts != null && n > 0) {
            for (i in 0 until n) {
                mapper.map(pts[i * 3], pts[i * 3 + 1], mapped)
                pointXs[i] = mapped[0]
                pointYs[i] = mapped[1]
                pointZs[i] = pts[i * 3 + 2]
            }
            scanView.updateFace(pointXs, pointYs, pointZs, n)
        } else {
            scanView.clearFace()
        }

        val forced = debugHooks?.forceState
        if (forced == "pass") {
            // Test rig: the whole flow with every check treated as passing.
            if (scanStage == ScanStage.ALIGN && pts != null) startScan(sess, manual = false)
            return
        }
        if (scanStage == ScanStage.ALIGN && forced != null) {
            // Test rig: go to the forced state on the first face, gate or not.
            if (forced != "align" && pts != null) startScan(sess, manual = true)
            return
        }
        if (scanStage == ScanStage.ALIGN && result.allPass) startScan(sess, manual = false)
    }

    /** The scan clock: hints, shutter, ring, pause/resume/drop. Runs every [SCAN_TICK_MS]. */
    private fun onScanTick() {
        val sess = session ?: return
        val controller = scanController ?: return
        val now = SystemClock.elapsedRealtime()
        val analyzer = liveAnalyzer
        // A gate still loading after GATE_INIT_TIMEOUT_MS offers the shutter;
        // if it loads later, the shutter stays as a fallback but auto start
        // and hints come back.
        val slowGate = analyzer != null && analyzer.initState == LiveFaceAnalyzer.InitState.PENDING &&
            now - alignStartedAt > GATE_INIT_TIMEOUT_MS
        if (analyzer?.initState == LiveFaceAnalyzer.InitState.READY && gateUnavailable && pendingConfig?.guidedCaptureEnabled != false) {
            gateUnavailable = false
            shutterKept = true
        }
        // A gate that stopped answering must not hold the scan forever: the
        // server, not this gate, decides liveness.
        val fresh = lastGate?.takeIf { now - lastGateAt < GATE_STALE_MS }

        when (scanStage) {
            ScanStage.ALIGN -> {
                val rigHoldsAlign = debugHooks?.forceState == "align"
                if (!rigHoldsAlign && (gateUnavailable || slowGate || shutterKept || controller.shutterAvailable(now))) {
                    // The shutter copy replaces gate hints; a working gate still
                    // starts the scan by itself (onGateFrame).
                    showShutter(sess)
                    return
                }
                debugHooks?.forceHint?.let { name ->
                    // Test rig: hold one instruction for the golden screenshot.
                    renderHint(runCatching { GateHint.valueOf(name) }.getOrNull())
                    scanView.tone = ContourScanView.Tone.WARNING
                    return
                }
                // The fill light works silently: BRIGHTENING has no text.
                val failing = (fresh?.failing ?: listOf(GateHint.SHOW_FACE)).filter { it != GateHint.BRIGHTENING }
                if (hintScheduler.update(now, failing)) announce(hintScheduler.current)
                renderHint(hintScheduler.current)
                // The stroke turns the warning colour while the instruction asks for an action.
                scanView.tone = if (hintScheduler.current != null) ContourScanView.Tone.WARNING else ContourScanView.Tone.NEUTRAL
            }
            ScanStage.PASSIVE -> {
                debugHooks?.forceProgress?.takeIf { debugHooks?.forceState.let { it != null && it != "pass" } }?.let { held ->
                    // Test rig: hold the ring for the golden screenshot.
                    scanView.paused = false
                    scanView.tone = ContourScanView.Tone.READY
                    scanView.progress = held
                    renderScanCopy(controller)
                    return
                }
                val pass = gateUnavailable || debugHooks?.forceState == "pass" || fresh?.allPass ?: true
                when (controller.tick(now, pass)) {
                    ScanController.Event.PAUSED -> {
                        sess.pauseCapture()
                        scanView.paused = true
                    }
                    ScanController.Event.RESUMED -> {
                        sess.resumeCapture()
                        scanView.paused = false
                    }
                    ScanController.Event.DROPPED -> {
                        // Paused too long: start the window over from align.
                        sess.restartCaptureWindow()
                        watchdog.disarm()
                        enterAlign(sess)
                        return
                    }
                    ScanController.Event.COMPLETED -> {
                        scanView.progress = 1f
                        finishPassiveScan()
                        return
                    }
                    null -> Unit
                }
                if (controller.bankedMs >= BASELINE_MS && sess.capturePhase == CapturePhase.BASELINE) {
                    sess.setCapturePhase(CapturePhase.CHALLENGE)
                }
                scanView.progress = controller.progress
                if (controller.state == ScanController.State.PAUSED) {
                    val failing = fresh?.failing.orEmpty().filter { it != GateHint.BRIGHTENING }
                    if (hintScheduler.update(now, failing)) announce(hintScheduler.current)
                    scanView.tone = ContourScanView.Tone.WARNING
                    hintScheduler.current?.let { renderHint(it); scanCopyShown = false } ?: renderScanCopy(controller)
                } else {
                    hintScheduler.update(now, emptyList())
                    scanView.tone = ContourScanView.Tone.READY
                    renderScanCopy(controller)
                }
            }
            else -> Unit
        }
    }

    /**
     * Start the scan: by itself when every check passes, or from the shutter.
     * A server challenge runs as before; a passive session scans with the ring.
     */
    private fun startScan(sess: UseSenseSession, manual: Boolean) {
        if (scanStage != ScanStage.ALIGN) return
        val controller = scanController ?: return
        val now = SystemClock.elapsedRealtime()
        if (!manual) haptic()
        shutterButton.visibility = View.GONE
        hintScheduler.reset()

        val challengeFlow = challengePresenter != null || sess.policy?.requiresStepup == true
        if (challengeFlow) {
            scanStage = ScanStage.CHALLENGE
            stopScanLoop()
            faceGuideOverlay.visibility = View.GONE
            watchdog.arm(WatchdogPhase.CAPTURE)
            cameraLive.whenLive {
                sess.startCapture()
                sessionCaptureStarted = true
                eventEmitter.emit(EventType.CAPTURE_STARTED)
                if (sess.requiresAudio) {
                    sess.startAudioRecording()
                    eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
                }
                startBaselinePhase()
            }
            return
        }

        scanStage = ScanStage.PASSIVE
        watchdog.arm(WatchdogPhase.CAPTURE)
        if (!sessionCaptureStarted) {
            sess.startCapture()
            sessionCaptureStarted = true
            eventEmitter.emit(EventType.CAPTURE_STARTED)
            if (sess.requiresAudio) {
                sess.startAudioRecording()
                eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
            }
        } else {
            sess.resumeCapture()
        }
        sess.setCapturePhase(CapturePhase.BASELINE)
        ++captureRound
        controller.start(now, manual)
        scanView.resetProgress()
        scanView.scanning = true
        scanView.paused = false
        scanView.tone = ContourScanView.Tone.READY
        scanCopyShown = false
        renderScanCopy(controller)
        if (debugHooks?.forceState == "upload") {
            // Test rig: the upload sheet over the blurred camera, no network.
            stopScanLoop()
            setProcessingCopy(FinalizationPhase.UPLOADING)
            setStep(PageStep.FINALIZING)
            uploadProgress.progress = ((debugHooks?.forceProgress ?: 0.6f) * 1000).toInt()
        }
    }

    private fun finishPassiveScan() {
        haptic()
        stopScanLoop()
        restoreFillLight()
        hintPill.announceForAccessibility(getString(R.string.usesense_scan_complete))
        onCaptureComplete(captureRound)
    }

    private fun stopScanLoop() {
        handler.removeCallbacks(scanTicker)
        liveAnalyzer?.active = false
        lastGate = null
        if (scanStage == ScanStage.ALIGN || scanStage == ScanStage.PASSIVE) scanStage = ScanStage.IDLE
    }

    private fun showShutter(sess: UseSenseSession) {
        if (shutterButton.visibility != View.VISIBLE) {
            Log.i(TAG, "Shutter offered: gateUnavailable=$gateUnavailable gate=${liveAnalyzer?.initState} " +
                "dropped=${scanController?.droppedWindows} aligningMs=${SystemClock.elapsedRealtime() - alignStartedAt}")
        }
        shutterButton.visibility = View.VISIBLE
        showGuidance(getString(R.string.usesense_shutter_title), null)
        scanView.tone = ContourScanView.Tone.NEUTRAL
        if (!shutterAnnounced) {
            shutterAnnounced = true
            hintPill.announceForAccessibility(hintTitle.text)
        }
    }

    /** During the scan the guidance is just "Hold still" (no counters or stage names, SPEC v1.1). */
    @Suppress("UNUSED_PARAMETER")
    private fun renderScanCopy(controller: ScanController) {
        showGuidance(getString(R.string.usesense_hint_hold_still), null)
        if (!scanCopyShown) {
            scanCopyShown = true
            hintPill.announceForAccessibility(hintTitle.text)
        }
    }

    /** Instruction and optional second line. Null for [GateHint.BRIGHTENING]: the fill light works silently. */
    private fun hintText(hint: GateHint): Pair<String, String?>? = when (hint) {
        GateHint.SHOW_FACE -> getString(R.string.usesense_hint_show_face) to null
        GateHint.ONE_PERSON -> getString(R.string.usesense_hint_one_person) to null
        GateHint.CENTRE -> getString(R.string.usesense_hint_centre) to null
        GateHint.MOVE_CLOSER -> getString(R.string.usesense_hint_move_closer) to null
        GateHint.MOVE_BACK, GateHint.TOO_BIG -> getString(R.string.usesense_hint_move_back) to null
        GateHint.LOOK_STRAIGHT -> getString(R.string.usesense_hint_look_straight) to null
        GateHint.BRIGHTENING -> null
        GateHint.FACE_LIGHT -> getString(R.string.usesense_hint_face_light) to
            getString(R.string.usesense_hint_face_light_detail)
        GateHint.TOO_BRIGHT -> getString(R.string.usesense_hint_too_bright) to
            getString(R.string.usesense_hint_too_bright_detail)
        GateHint.HOLD_STEADY -> getString(R.string.usesense_hint_hold_steady) to null
        GateHint.EYES_OPEN -> getString(R.string.usesense_hint_eyes_open) to null
        GateHint.HOLD_STILL -> getString(R.string.usesense_hint_hold_still) to null
    }

    /** The align instruction: the hint, or "Hold still" when nothing needs doing. */
    private fun renderHint(hint: GateHint?) {
        val text = hint?.let(::hintText)
        if (text == null) {
            showGuidance(getString(R.string.usesense_hint_hold_still), null)
        } else {
            showGuidance(text.first, text.second)
        }
    }

    /** Plain guidance text under the oval; a 200 ms crossfade when it changes. */
    private fun showGuidance(title: CharSequence, detail: CharSequence?) {
        hintPill.visibility = View.VISIBLE
        val same = hintTitle.text.toString() == title.toString() &&
            hintDetail.text.toString() == (detail?.toString() ?: "")
        if (same) return
        val apply = {
            hintTitle.text = title
            hintDetail.text = detail ?: ""
            hintDetail.visibility = if (detail != null) View.VISIBLE else View.GONE
        }
        if (hintTitle.text.isNullOrEmpty()) {
            apply()
            return
        }
        hintPill.animate().cancel()
        hintPill.animate().alpha(0f).setDuration(GUIDANCE_FADE_MS / 2).withEndAction {
            apply()
            hintPill.animate().alpha(1f).setDuration(GUIDANCE_FADE_MS / 2).start()
        }.start()
    }

    /** Put the guidance's top edge 28 dp below the oval's bottom (SPEC v1.1). */
    private fun placeHint() {
        val h = faceGuideOverlay.height
        if (h == 0) return
        val ovalBottom = h * (ContourScanView.OVAL_CY + ContourScanView.OVAL_RY_OF_H)
        hintGroup.y = ovalBottom + GUIDANCE_GAP_DP * resources.displayMetrics.density - hintPill.top
    }

    /** Spoken hint (TalkBack). Called only when the scheduler shows a new one, so it shares its 1.2 s limit. */
    private fun announce(hint: GateHint?) {
        val (title, detail) = hint?.let(::hintText) ?: return
        hintPill.announceForAccessibility(listOfNotNull(title, detail).joinToString(". "))
    }

    private fun haptic() {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        scanView.performHapticFeedback(effect)
    }

    /** Too dark: the screen becomes the fill light (full brightness, warm white surround). */
    private fun turnOnFillLight() {
        if (fillLightSince != null) return
        fillLightSince = SystemClock.elapsedRealtime()
        val lp = window.attributes
        savedBrightness = lp.screenBrightness
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = lp
        scanView.fillLight = true
    }

    private fun restoreFillLight() {
        if (fillLightSince == null) return
        fillLightSince = null
        val lp = window.attributes
        lp.screenBrightness = savedBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        savedBrightness = null
        scanView.fillLight = false
    }

    /** The face blurs out behind the processing sheet (Android 12+; older versions keep the opaque sheet). */
    private fun setPreviewBlurred(blurred: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // No blur before Android 12: a near-opaque sheet hides the face instead.
            uploadOverlay.setBackgroundColor(Color.parseColor(if (blurred) "#E60D0F14" else "#400D0F14"))
            return
        }
        if (blurred) {
            val r = 14f * resources.displayMetrics.density
            val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            cameraPreview.setRenderEffect(blur)
            fakePreview?.setRenderEffect(blur)
        } else {
            cameraPreview.setRenderEffect(null)
            fakePreview?.setRenderEffect(null)
        }
    }

    private fun startBaselinePhase() {
        val sess = session ?: return
        try {
            sess.setCapturePhase(CapturePhase.BASELINE)
            challengeOverlay.visibility = View.VISIBLE

            showCaptureText(true)
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
        stopScanLoop()
        restoreFillLight()
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

    /**
     * The upload sheet: one title, one bar for upload and checking together.
     * Upload fills the first [UPLOAD_SHARE] of the bar; checking then eases
     * toward the end without reaching it until the result arrives.
     */
    private fun setProcessingCopy(phase: FinalizationPhase) {
        uploadOverlayTitle.text = getString(R.string.usesense_upload_title)
        uploadOverlaySubtitle.text = getString(R.string.usesense_upload_detail)
        uploadProgress.progressTintList = ColorStateList.valueOf(ScanColors.resolve(resolvedBranding.appearance).ring)
        when (phase) {
            FinalizationPhase.PREPARING -> setUploadProgress(0.05f)
            FinalizationPhase.UPLOADING -> Unit
            FinalizationPhase.COMPLETING -> setUploadProgress(0.97f, durationMs = 4_000L)
        }
    }

    private fun setUploadProgress(fraction: Float, durationMs: Long = 250L) {
        uploadProgressAnimator?.cancel()
        val target = (fraction.coerceIn(0f, 1f) * 1000).toInt()
        if (target <= uploadProgress.progress) return
        uploadProgressAnimator = ValueAnimator.ofInt(uploadProgress.progress, target).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { uploadProgress.progress = it.animatedValue as Int }
            start()
        }
    }

    /** The status lines under the camera; hidden where a screen carries its own copy. */
    private fun showCaptureText(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        captureTitle.visibility = v
        captureSubtitle.visibility = v
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
                // As iOS: "Verifying your presence" while preparing/uploading,
                // "Almost done" while the result is computed. No percentage.
                setProcessingCopy(FinalizationPhase.PREPARING)
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
                            setProcessingCopy(update.phase)
                        }
                        is FinalizationUpdate.Progress -> {
                            watchdog.onProgress()
                            // Progress arrives on the network thread.
                            val sent = update.bytesSent.toFloat() / update.bytesTotal.coerceAtLeast(1)
                            uploadProgress.post { setUploadProgress(0.05f + sent * (UPLOAD_SHARE - 0.05f)) }
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
        showCaptureText(true)
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
            setProcessingCopy(FinalizationPhase.UPLOADING)
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
        pendingTerminalError = null
        pendingResult = result
        showResult(result.decision, result)
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
        stopScanLoop()
        scanStage = ScanStage.IDLE
        restoreFillLight()
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
        when (decision) {
            UseSenseResult.DECISION_APPROVE -> {
                resultIcon.setImageResource(R.drawable.usesense_icon_success)
                resultIcon.setColorFilter(Color.parseColor("#00AA88")) // MatchSense Green-6
                resultIconCircle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E6FBF5")) // green-0
                }
                resultTitle.text = getString(R.string.usesense_result_success_title)
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
                resultTitle.text = getString(R.string.usesense_result_failed_title)
                resultBody.text = getString(R.string.usesense_result_failed_body_unsuccessful)
            }
        }

        // As iOS ResultView: one primary button in the outcome colour
        // (Continue / Done / Got It) that hands the decision to the host, and
        // "Try Again" on a reject. No redirect: the host decides what's next.
        val isReject = decision != UseSenseResult.DECISION_APPROVE &&
            decision != UseSenseResult.DECISION_MANUAL_REVIEW
        val (buttonText, buttonColor) = when (decision) {
            UseSenseResult.DECISION_APPROVE -> R.string.usesense_result_button_approve to "#00AA88"
            UseSenseResult.DECISION_MANUAL_REVIEW -> R.string.usesense_result_button_review to "#DB973A"
            else -> R.string.usesense_result_button_reject to "#DB4E33"
        }
        resultButton.text = getString(buttonText)
        resultButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor(buttonColor))
        resultButton.setOnClickListener {
            pendingResult?.let { callbackGate.success(it) }
            pendingResult = null
            finish()
        }
        // Session mode only. Inside a Flow the server has already moved the
        // run on from this decision; a hosted remote link is completed too.
        val canRetry = isReject && isDirectMode && !isFlowRun
        resultRetryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        resultRetryButton.setOnClickListener {
            pendingResult = null
            restartCapture()
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
                setPreviewBlurred(false)
                uploadProgressAnimator?.cancel()
                uploadProgress.progress = 0
            }
            PageStep.FINALIZING -> {
                // Keep capture screen visible — show dark overlay on camera
                captureScreen.visibility = View.VISIBLE
                uploadOverlay.visibility = View.VISIBLE
                // The face blurs out for privacy behind the sheet.
                faceGuideOverlay.visibility = View.GONE
                setPreviewBlurred(true)
                // Nothing from the capture shows through under the overlay
                // ("Hold still" used to sit below it); the footer stays.
                showCaptureText(false)
                qualityIndicator.visibility = View.GONE
                challengeProgress.visibility = View.GONE
                directionText.visibility = View.GONE
                speakPhraseLayout.visibility = View.GONE
                qualityBanner.visibility = View.GONE
            }
            PageStep.RESULT -> resultScreen.visibility = View.VISIBLE
        }

        val insets = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        // Capture runs under the status bar (light icons over the camera); other screens are padded.
        captureFullBleed = isCaptureMode
        (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)?.let { root ->
            root.setBackgroundColor(if (isCaptureMode) Color.BLACK else Color.WHITE)
            applySystemBarPadding(root)
        }
        if (lightStatusBarDefault == null) lightStatusBarDefault = insets.isAppearanceLightStatusBars
        insets.isAppearanceLightStatusBars = if (isCaptureMode) false else (lightStatusBarDefault ?: true)

        // Section 11.8: Hide branded header/footer during capture, show capture footer
        headerBar.visibility = if (isCaptureMode || !hasHeader) View.GONE else View.VISIBLE
        headerDivider.visibility = if (isCaptureMode || !hasHeader) View.GONE else View.VISIBLE
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
        hintPill.visibility = View.INVISIBLE
        shutterButton.visibility = View.GONE
        setPreviewBlurred(false)
    }

    override fun onDestroy() {
        // Never end silently: the host hears an error (if one is on screen) or
        // a cancellation. The gate makes this a no-op after a success/exit.
        if (!isChangingConfigurations) {
            // Leaving from the result screen still delivers its decision.
            pendingResult?.let { callbackGate.success(it) }
                ?: pendingTerminalError?.let { callbackGate.exit(it) }
                ?: callbackGate.cancel()
        }
        if (::watchdog.isInitialized) watchdog.disarm()
        finalizationJob?.cancel()
        super.onDestroy()
        mainScope.cancel()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        liveAnalyzer?.release()
        liveAnalyzer = null
        debugHooks?.stop()
        session?.release()
        pendingConfig = null
        pendingRequest = null
        pendingDirectCallback = null
        pendingSessionFactory = null
        pendingServerBranding = null
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

        private const val GUIDANCE_FADE_MS = 200L
        private const val GUIDANCE_GAP_DP = 28f
        private const val GATE_TAG = "UseSenseGate"

        /** Share of the upload sheet's bar that the upload itself fills. */
        private const val UPLOAD_SHARE = 0.7f

        /** Face mesh points (468 + 10 iris). */
        private const val MAX_LANDMARKS = 478
        /** The scan clock's period (~15 Hz); the view itself animates per frame. */
        private const val SCAN_TICK_MS = 66L
        /** Scan length when the server sends none (it used to be the passive capture's length too). */
        private const val DEFAULT_SCAN_MS = 4500
        private const val MIN_SCAN_MS = 1500L
        /** The passive scan's target length (shared spec: "about 3 s", as on web). */
        private const val TARGET_SCAN_MS = 3_000L
        /** Past this, a gate still loading counts as unavailable: offer the shutter. */
        private const val GATE_INIT_TIMEOUT_MS = 8_000L
        /** A gate result older than this is ignored (the gate thread stalled). */
        private const val GATE_STALE_MS = 1_000L

        /** Ceiling on minting a fresh Flows session for "Try again". */
        private const val SESSION_REFRESH_TIMEOUT_MS = 30_000L

        internal var pendingConfig: UseSenseConfig? = null
        internal var pendingRequest: VerificationRequest? = null
        internal var pendingDirectCallback: UseSenseCallback? = null

        // Flows runner path: pre-minted session credentials returned by
        // /sdk/flow-runs/:id/init-session. When set, initAndStartCapture()
        // injects them instead of calling createSession (cleared after use).
        internal var pendingPrebuiltSession: com.usesense.sdk.api.models.CreateSessionResponse? = null

        /** The Flows run's dashboard branding, handed over with a pre-minted session. */
        internal var pendingServerBranding: ServerBranding? = null

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
            serverBranding: ServerBranding? = null,
            callback: UseSenseCallback,
            refreshSession: (suspend () -> com.usesense.sdk.api.models.CreateSessionResponse)? = null,
        ) {
            pendingConfig = config
            pendingRequest = VerificationRequest(sessionType = sessionType)
            pendingDirectCallback = callback
            pendingPrebuiltSession = response
            pendingSessionFactory = refreshSession
            pendingServerBranding = serverBranding
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
