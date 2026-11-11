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
import com.usesense.sdk.capture.scan.CoveredCheckStats
import com.usesense.sdk.capture.scan.FaceRegions
import com.usesense.sdk.capture.scan.FillLightLatch
import com.usesense.sdk.capture.scan.GateCheck
import com.usesense.sdk.capture.scan.GateFrame
import com.usesense.sdk.capture.scan.GateHint
import com.usesense.sdk.capture.scan.GateResult
import com.usesense.sdk.capture.scan.HintScheduler
import com.usesense.sdk.capture.scan.LiveFaceAnalyzer
import com.usesense.sdk.capture.scan.PreviewMapper
import com.usesense.sdk.capture.scan.QualityGate
import com.usesense.sdk.capture.scan.Relief
import com.usesense.sdk.capture.scan.ReliefBuilder
import com.usesense.sdk.capture.scan.UploadBar
import com.usesense.sdk.capture.scan.ScanController
import com.usesense.sdk.capture.extracheck.ExtraCheckCopy
import com.usesense.sdk.capture.extracheck.ExtraCheckEvent
import com.usesense.sdk.capture.extracheck.ExtraCheckFace
import com.usesense.sdk.capture.extracheck.ExtraCheckInput
import com.usesense.sdk.capture.extracheck.ExtraCheckKind
import com.usesense.sdk.capture.extracheck.ExtraCheckRunner
import com.usesense.sdk.capture.extracheck.ExtraCheckStep
import com.usesense.sdk.capture.extracheck.ExtraCheckTiming
import com.usesense.sdk.capture.extracheck.Guidance
import com.usesense.sdk.capture.extracheck.MicroActionDetector
import com.usesense.sdk.capture.extracheck.ExtraCheckMotion
import com.usesense.sdk.liveness.HeadPose
import com.usesense.sdk.liveness.StepUpOrchestrator
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.Bounded
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.CaptureWatchdog
import com.usesense.sdk.internal.DebugCaptureHooks
import com.usesense.sdk.internal.SessionState
import com.usesense.sdk.internal.WatchdogPhase
import com.usesense.sdk.internal.boundedCall
import com.usesense.sdk.finalization.*
import com.usesense.sdk.flows.AppearanceLogo
import com.usesense.sdk.flows.FlowAppearance
import com.usesense.sdk.ui.theme.CaptureFixed
import com.usesense.sdk.ui.theme.CaptureImages
import com.usesense.sdk.ui.theme.CaptureLoaderView
import com.usesense.sdk.ui.theme.CaptureStrings
import com.usesense.sdk.ui.theme.CaptureStyler
import com.usesense.sdk.ui.theme.CaptureTheme
import com.usesense.sdk.ui.theme.CoverDrawable
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
    /**
     * The one capture theme (BRAND-v1 4) every screen reads, resolved with the
     * branding. [styler] applies it; [strings] is `copy.capture` over strings.xml.
     */
    private var captureTheme: CaptureTheme = CaptureTheme.resolve(null, systemDark = false)
    private lateinit var styler: CaptureStyler
    private var strings: CaptureStrings? = null
    private var pageLogoUrl: String? = null
    private var pageBackgroundUrl: String? = null
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
    private lateinit var loadingSpinner: CaptureLoaderView
    private lateinit var finalizingSpinner: CaptureLoaderView
    private lateinit var loadingImage: ImageView
    private lateinit var finalizingImage: ImageView

    // Upload/completing overlay (Section 5.8/5.9 — dark overlay on camera)
    private lateinit var uploadOverlay: FrameLayout
    private lateinit var uploadOverlayTitle: TextView
    private lateinit var uploadOverlaySubtitle: TextView
    private lateinit var uploadOverlaySpinner: ProgressBar

    // Scan overlay (LiveSense capture redesign): oval, hint, ring, contours, shutter
    private lateinit var faceGuideCloseButton: View
    private lateinit var captureTopBar: FrameLayout
    private lateinit var helpButton: View
    private lateinit var scanView: ContourScanView
    private lateinit var hintPill: LinearLayout
    private lateinit var hintTitle: TextView
    private lateinit var hintDetail: TextView
    /** The guidance last asked for (title, detail), shown or still fading in. */
    private var guidanceTarget: Pair<String, String>? = null
    private lateinit var shutterButton: ImageButton
    private lateinit var hintGroup: LinearLayout
    private lateinit var uploadProgress: ProgressBar
    /** Time-driven upload bar (iOS parity); [uploadBarTicker] redraws it at ~30 fps. */
    private val uploadBar = UploadBar()
    private val uploadBarTicker = object : Runnable {
        override fun run() {
            renderUploadBar()
            if (uploadBar.stage != UploadBar.Stage.DONE && uploadOverlay.visibility == View.VISIBLE) {
                handler.postDelayed(this, UPLOAD_BAR_FRAME_MS)
            }
        }
    }

    /** Where the capture screen is in the scan flow. */
    /** INLINE: on-device attack detection's prompts after the scan (inline step-up). */
    /** CLOSER: EVIDENCE-v1 step 5, the oval grows and the face follows it (after the scan). */
    private enum class ScanStage { IDLE, ALIGN, PASSIVE, CLOSER, CHALLENGE, INLINE }
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
    private var helpOpen = false
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
        val top = (16 * resources.displayMetrics.density).toInt() + if (captureFullBleed) b.top else 0
        if (captureTopBar.paddingTop != top) {
            captureTopBar.setPadding(captureTopBar.paddingLeft, top, captureTopBar.paddingRight, 0)
        }
    }
    private var savedBrightness: Float? = null
    private val scanTicker = object : Runnable {
        override fun run() {
            onScanTick()
            if (scanStage == ScanStage.ALIGN || scanStage == ScanStage.PASSIVE || scanStage == ScanStage.CLOSER ||
                scanStage == ScanStage.CHALLENGE
            ) {
                handler.postDelayed(this, SCAN_TICK_MS)
            }
        }
    }

    // Capture footer (Section 11.8)
    private lateinit var captureFooterText: TextView

    // Extra check (storyboard flows 4 and 5), sheets, dark pages, leave dialog
    private lateinit var extraCheckView: ExtraCheckOverlayView
    private lateinit var uploadSheet: LinearLayout
    private lateinit var actionSheet: LinearLayout
    private lateinit var actionSheetIcon: StrokeIconView
    private lateinit var actionSheetTitle: TextView
    private lateinit var actionSheetBody: TextView
    private lateinit var actionSheetPrimary: TextView
    private lateinit var pageView: ScrollView
    private lateinit var pageIcon: StrokeIconView
    private lateinit var pageTitle: TextView
    private lateinit var pageBody: TextView
    private lateinit var pageTips: LinearLayout
    private lateinit var pagePrimary: TextView
    private lateinit var pageSecondary: TextView
    private lateinit var leaveDialog: FrameLayout

    /** The extra check running on the scan screen, if any. */
    private var extraCheck: ExtraCheckRunner? = null
    private var extraCheckTiming = ExtraCheckTiming.BEFORE_SCAN
    private var extraCheckPresenter: ChallengePresenter? = null
    /** The latest gate frame's reading for the extra check, and when it came. */
    private var extraCheckFace = ExtraCheckInput(gatePasses = true)
    private var extraCheckFaceAt = 0L
    /** The gate checks holding the movement on the latest frame. */
    private var extraCheckBlocking: List<GateHint> = emptyList()
    /** Inline step-up: its coroutine, the colour layer for Flash Reflection, the RMAS action being watched. */
    private var inlineJob: Job? = null
    private var flashView: View? = null

    // ─── EVIDENCE-v1: colour sequence and closer step ────────────────────
    /** The bound front camera, for the white balance lock. */
    private var boundCamera: androidx.camera.core.Camera? = null
    /** The server's colour sequence for this attempt (round 1, guided scan). */
    private var colourPlayer: com.usesense.sdk.evidence.ColourSequencePlayer? = null
    /** Banked scan time when the current play started; -1 when not playing. */
    private var colourFromBankedMs = -1L
    private var colourView: View? = null
    private var colourTintShowing = false
    private var colourSavedBrightness: Float? = null
    private var colourHoldsBrightness = false
    private val colourFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val player = colourPlayer ?: return
            player.advance(frameTimeNanos)
            if (player.isPlaying) android.view.Choreographer.getInstance().postFrameCallback(this)
            session?.colourEvidence = player.record
        }
    }
    /** Screen eye distance (px) of the scan's last passing gate frames: the far phase. */
    private val farEyePx = ArrayDeque<Float>()
    private var closerStep: com.usesense.sdk.evidence.CloserStepController? = null
    /** The closer step already ran (or was skipped) for this scan. */
    private var closerHandled = false
    @Volatile private var closerEyePx: Float? = null
    @Volatile private var closerGatePasses = false

    private val colourHost = object : com.usesense.sdk.evidence.ColourTintHost {
        override fun showTint(colour: String?, opacity: Double) {
            val v = colourView ?: View(this@HostedPageActivity).also { v ->
                v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // Over the camera, under the oval, ring and guidance.
                videoContainer.addView(
                    v,
                    videoContainer.indexOfChild(faceGuideOverlay).coerceAtLeast(0),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
                colourView = v
            }
            if (colour == null) {
                colourTintShowing = false
                v.visibility = View.GONE
                scanView.fillLight = fillLightSince != null
                return
            }
            colourTintShowing = true
            v.setBackgroundColor(Color.parseColor(colour))
            v.alpha = opacity.toFloat()
            v.visibility = View.VISIBLE
            // The colour replaces the fill light veil while it shows, so it stays pure.
            scanView.fillLight = false
        }

        override fun holdFullBrightness(): Double? {
            val lp = window.attributes
            if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL) {
                colourSavedBrightness = lp.screenBrightness
                colourHoldsBrightness = true
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                window.attributes = lp
            }
            return window.attributes.screenBrightness.toDouble().takeIf { it in 0.0..1.0 }
        }

        override fun releaseFullBrightness() {
            if (!colourHoldsBrightness) return
            colourHoldsBrightness = false
            // The fill light, if it came on meanwhile, keeps the brightness.
            if (fillLightSince != null) return
            val lp = window.attributes
            lp.screenBrightness = colourSavedBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            colourSavedBrightness = null
        }

        @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
        override fun lockWhiteBalance(locked: Boolean): Boolean {
            val camera = boundCamera ?: return false
            return runCatching {
                val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
                val available = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE,
                ) == true
                if (!available) return@runCatching false
                val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, locked)
                    .build()
                androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(options)
                locked
            }.getOrElse {
                Log.w(TAG, "White balance lock failed", it)
                false
            }
        }
    }
    private var microAction: MicroActionDetector? = null
    private var microActionSeen: CompletableDeferred<Unit>? = null

    /** The step before the current one, and when the current one started (pose grace on a change of side). */
    private var previousStep: ExtraCheckStep? = null
    private var stepStartedAt = 0L
    /** The page on screen, if any. */
    private var pageShown: PageKind? = null
    /** "Try again" taps used after a rejection (UseSenseConfig.maxRetries). */
    private var retriesUsed = 0
    private var primerShown = false
    /** This attempt already swapped an expired session for a fresh one. */
    private var expiryRefreshed = false
    /** The mic page was left for Settings: check the permission again on return. */
    private var awaitingMicSettings = false

    // Header bar reference for hide/show
    private lateinit var headerBar: LinearLayout
    private lateinit var headerDivider: View

    // Page header, illustration and the help dialog (BRAND-v1)
    private lateinit var pageContent: LinearLayout
    private lateinit var pageHeader: LinearLayout
    private lateinit var pageLogo: ImageView
    private lateinit var pageBrandName: TextView
    private lateinit var pageIllustration: ImageView
    private lateinit var helpDialog: FrameLayout

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
        PERMISSION, CAPTURE, FINALIZING, RESULT,
        /** A dark storyboard page (primer, result, error) over everything. */
        PAGE,
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // A permission granted earlier isn't in the result map: check the system.
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
        if (cameraGranted) {
            eventEmitter.emit(EventType.PERMISSIONS_GRANTED, mapOf("camera" to true))
            onCameraGranted()
        } else {
            eventEmitter.emit(EventType.PERMISSIONS_DENIED, mapOf("camera" to false))
            if (usesSdkCaptureSequence) {
                showPage(
                    PageKind.CAMERA_DENIED,
                    onPrimary = { awaitingCameraSettings = true; openAppSettings() },
                    onSecondary = { checkAndRequestPermissions() },
                )
            } else {
                showCameraError(getString(R.string.usesense_camera_denied_message))
            }
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
        resolveBranding(null, null)
        applyBranding()

        watchdog = CaptureWatchdog(mainScope) { _, error -> onWatchdogTimeout(error) }
        // Leaving the capture must never end silently: abandon the attempt and
        // let onDestroy deliver onCancelled (or the error on screen).
        // Mid-flow (camera, extra check, upload) it asks first: leaving ends
        // this verification, with no resume.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (helpDialog.visibility == View.VISIBLE) { hideHelpDialog(); return }
                if (leaveDialog.visibility == View.VISIBLE) { hideLeaveDialog(); return }
                if (isMidFlow()) { showLeaveDialog(); return }
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

        usesSdkCaptureSequence = isDirectMode
        isFlowRun = pendingPrebuiltSession != null
        // Resolved once here, and again as dashboard branding arrives (session
        // create, remote data, a Flow's fresh init-session on "Try again").
        resolveBranding(config.branding, flowServerBranding(pendingPrebuiltSession))
        applyBranding()
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
        loadingImage = findViewById(R.id.hostedLoadingImage)
        finalizingImage = findViewById(R.id.finalizingImage)

        // Upload overlay (Section 5.8/5.9)
        uploadOverlay = findViewById(R.id.hostedUploadOverlay)
        uploadOverlayTitle = findViewById(R.id.hostedUploadOverlayTitle)
        uploadOverlaySubtitle = findViewById(R.id.hostedUploadOverlaySubtitle)
        uploadOverlaySpinner = findViewById(R.id.hostedUploadOverlaySpinner)
        faceGuideCloseButton = findViewById(R.id.hostedFaceGuideCloseButton)
        captureTopBar = findViewById(R.id.hostedCaptureTopBar)
        helpButton = findViewById(R.id.hostedHelpButton)
        faceGuideCloseButton.setOnClickListener {
            // Back leaves the capture and cancels it, after "Leave verification?".
            if (isMidFlow()) showLeaveDialog() else leaveVerification()
        }
        helpButton.setOnClickListener {
            // As iOS: the tips on align and scan; nothing while the upload runs.
            if (uploadOverlay.visibility == View.VISIBLE) return@setOnClickListener
            showHelpDialog()
        }
        helpDialog = findViewById(R.id.hostedHelpDialog)
        helpDialog.setOnClickListener { hideHelpDialog() }
        findViewById<View>(R.id.hostedHelpOk).setOnClickListener { hideHelpDialog() }
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

        extraCheckView = findViewById(R.id.hostedExtraCheckView)
        uploadSheet = findViewById(R.id.hostedUploadSheet)
        actionSheet = findViewById(R.id.hostedActionSheet)
        actionSheetIcon = findViewById(R.id.hostedActionSheetIcon)
        actionSheetTitle = findViewById(R.id.hostedActionSheetTitle)
        actionSheetBody = findViewById(R.id.hostedActionSheetBody)
        actionSheetPrimary = findViewById(R.id.hostedActionSheetPrimary)
        pageView = findViewById(R.id.hostedPage)
        pageIcon = findViewById(R.id.hostedPageIcon)
        pageTitle = findViewById(R.id.hostedPageTitle)
        pageBody = findViewById(R.id.hostedPageBody)
        pageTips = findViewById(R.id.hostedPageTips)
        pagePrimary = findViewById(R.id.hostedPagePrimary)
        pageSecondary = findViewById(R.id.hostedPageSecondary)
        leaveDialog = findViewById(R.id.hostedLeaveDialog)
        pageContent = findViewById(R.id.hostedPageContent)
        pageHeader = findViewById(R.id.hostedPageHeader)
        pageLogo = findViewById(R.id.hostedPageLogo)
        pageBrandName = findViewById(R.id.hostedPageBrandName)
        pageIllustration = findViewById(R.id.hostedPageIllustration)
        pageIcon.iconScale = 0.5f
        actionSheetIcon.iconScale = 0.5f
        findViewById<View>(R.id.hostedKeepGoingButton).setOnClickListener { hideLeaveDialog() }
        findViewById<View>(R.id.hostedLeaveButton).setOnClickListener { leaveVerification() }

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

    /**
     * Resolve [code] over [server] (BrandingResolver), then the capture theme
     * and copy from the result, and trace each field's source layer.
     */
    private fun resolveBranding(code: BrandingConfig?, server: ServerBranding?) {
        resolvedBranding = BrandingResolver.resolve(code, server)
        brandingTrace = BrandingResolver.trace("capture", resolvedBranding, brandingTrace)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Unset mode: the SDK's capture pages stay dark (today's look); a remote
        // hosted link's intro / result / error screens stay light (theirs).
        val unsetMode = if (usesSdkCaptureSequence) FlowAppearance.Mode.DARK else FlowAppearance.Mode.LIGHT
        captureTheme = CaptureTheme.resolve(resolvedBranding.appearance, systemDark, unsetMode)
        styler = CaptureStyler(this, captureTheme)
        strings = CaptureStrings(
            copy = resolvedBranding.copy?.capture,
            app = appName(),
            resourceName = { id -> runCatching { resources.getResourceEntryName(id) }.getOrNull() },
            shipped = { id, app -> getString(id, app) },
        )
    }

    /** A capture string: the `copy.capture` override, else strings.xml (`%s` / `{app}` = [appName]). */
    private fun cs(id: Int, key: String? = null): String = strings?.text(id, key) ?: getString(id, appName())

    /** [withName] filled with the org's name, or the name-free [generic] when there is none. */
    private fun orgText(withName: Int, generic: Int): String =
        resolvedBranding.displayName.value?.let { getString(withName, it) } ?: getString(generic)

    /** Whether the header has anything to show (logo or name) and is not placed `none`. */
    private val hasHeader: Boolean
        get() = resolvedBranding.header != BrandHeader.None && captureTheme.logo.placement != AppearanceLogo.Placement.NONE

    /** Apply the resolved theme to every capture screen (BRAND-v1 4). */
    private fun applyBranding() {
        val t = captureTheme
        val root = (findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        styler.styleTree(root)

        // Header bar (remote link and loading screens) and the page header.
        val gravity = if (t.logo.placement == AppearanceLogo.Placement.CENTER) android.view.Gravity.CENTER
        else android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        headerBar.gravity = gravity
        pageHeader.gravity = gravity
        val logoHeight = styler.dp(t.logo.heightDp).toInt()
        headerLogo.layoutParams = headerLogo.layoutParams.apply { height = logoHeight }
        pageLogo.layoutParams = pageLogo.layoutParams.apply { height = logoHeight }
        when (val header = resolvedBranding.header.takeIf { hasHeader } ?: BrandHeader.None) {
            is BrandHeader.Logo -> {
                showBrandName(header.fallbackName)
                if (headerLogoUrl != header.url) {
                    headerLogoUrl = header.url
                    headerLogo.visibility = View.GONE
                    pageLogo.visibility = View.GONE
                    mainScope.launch {
                        val bitmap = CaptureImages.load(header.url) ?: return@launch
                        if (headerLogoUrl != header.url) return@launch
                        headerLogo.setImageBitmap(bitmap)
                        pageLogo.setImageBitmap(bitmap)
                        headerLogo.visibility = View.VISIBLE
                        pageLogo.visibility = View.VISIBLE
                        showBrandName(null)
                    }
                }
            }
            is BrandHeader.Name -> {
                headerLogoUrl = null
                headerLogo.visibility = View.GONE
                pageLogo.visibility = View.GONE
                showBrandName(header.name)
            }
            BrandHeader.None -> {
                headerLogoUrl = null
                headerLogo.visibility = View.GONE
                pageLogo.visibility = View.GONE
                showBrandName(null)
            }
        }
        pageHeader.visibility = if (hasHeader) View.VISIBLE else View.GONE
        // The header takes the place of the page's top space.
        pageContent.setPadding(
            pageContent.paddingLeft, styler.dp(if (hasHeader) PAGE_TOP_WITH_HEADER_DP else PAGE_TOP_DP).toInt(),
            pageContent.paddingRight, pageContent.paddingBottom,
        )
        if (!hasHeader) {
            headerBar.visibility = View.GONE
            headerDivider.visibility = View.GONE
        }

        // Page background image (cover), behind every page-surface screen.
        val bg = t.backgroundImageUrl
        if (bg != pageBackgroundUrl) {
            pageBackgroundUrl = bg
            if (bg != null) mainScope.launch {
                val bitmap = CaptureImages.load(bg) ?: return@launch
                if (pageBackgroundUrl != bg) return@launch
                pageView.background = CoverDrawable(bitmap, t.page.background)
                pageBackgroundBitmap = bitmap
                applyRootBackground()
            }
        }

        // Icon tiles on the intro / review screens and the white error and result screens.
        listOf(introIconCircle, actionReviewIconCircle).forEach {
            it.background = styler.rounded(t.toneTile(CaptureTheme.Tone.PRIMARY), styler.dp(t.shape.tileRadiusDp))
        }
        actionContextCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = styler.dp(t.shape.tileRadiusDp)
            setStroke(styler.dp(2).toInt(), t.page.primary)
            setColor(t.page.surface)
        }
        resultActionBox.background = styler.rounded(t.page.background, styler.dp(t.shape.tileRadiusDp))

        // Loaders: `loader.style` in primary, or `loader.imageUrl`.
        listOf(loadingSpinner, finalizingSpinner).forEach { loader ->
            loader.style = t.loader.style
            loader.color = t.page.primary
        }
        t.loader.imageUrl?.let { url ->
            mainScope.launch {
                val bitmap = CaptureImages.load(url) ?: return@launch
                listOf(loadingSpinner to loadingImage, finalizingSpinner to finalizingImage).forEach { (loader, image) ->
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    loader.visibility = View.GONE
                }
            }
        }

        // Camera accents (contrast-gated).
        scanView.colors = t.camera
        extraCheckView.colors = t.camera
        challengeProgress.progressTintList = ColorStateList.valueOf(t.camera.ring)

        // Copy that the layout carries.
        findViewById<TextView>(R.id.hostedLeaveTitle).text = cs(R.string.usesense_leave_title)
        findViewById<TextView>(R.id.hostedLeaveBody).text = cs(R.string.usesense_leave_body)
        findViewById<TextView>(R.id.hostedLeaveButton).text = cs(R.string.usesense_leave)
        findViewById<TextView>(R.id.hostedKeepGoingButton).text = cs(R.string.usesense_keep_going)
        findViewById<TextView>(R.id.hostedHelpTitle).text = cs(R.string.usesense_capture_help_title)
        findViewById<TextView>(R.id.hostedHelpBody).text = cs(R.string.usesense_capture_help_body)
        findViewById<TextView>(R.id.hostedHelpOk).text = cs(R.string.usesense_ok)
        uploadOverlayTitle.text = cs(R.string.usesense_upload_title)
        uploadOverlaySubtitle.text = cs(R.string.usesense_upload_detail)

        applyRootBackground()
    }

    private fun showBrandName(name: String?) {
        headerText.text = name.orEmpty()
        pageBrandName.text = name.orEmpty()
        val v = if (name != null) View.VISIBLE else View.GONE
        headerText.visibility = v
        pageBrandName.visibility = v
    }

    /** Set by the latest background-image load; drawn behind the page-surface screens. */
    private var pageBackgroundBitmap: android.graphics.Bitmap? = null
    private var currentStep: PageStep? = null

    /** The camera's fixed background, or the page background (and its image) everywhere else. */
    private fun applyRootBackground() {
        val step = currentStep ?: return
        val root = (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0) ?: return
        val camera = step == PageStep.CAPTURE || step == PageStep.FINALIZING
        root.background = when {
            camera -> android.graphics.drawable.ColorDrawable(CaptureFixed.CAMERA_BACKGROUND)
            else -> pageBackgroundBitmap?.let { CoverDrawable(it, captureTheme.page.background) }
                ?: android.graphics.drawable.ColorDrawable(captureTheme.page.background)
        }
    }

    /** "Quick face scan" (the camera's "?"), themed like the leave dialog. Freezes a running movement. */
    private fun showHelpDialog() {
        helpOpen = true
        helpDialog.visibility = View.VISIBLE
        helpDialog.announceForAccessibility("${cs(R.string.usesense_capture_help_title)}. ${cs(R.string.usesense_capture_help_body)}")
    }

    private fun hideHelpDialog() {
        if (!::helpDialog.isInitialized) return
        helpDialog.visibility = View.GONE
        helpOpen = false
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
        // The whole dashboard layer, appearance and copy included (BRAND-v1 6).
        resolveBranding(pendingConfig?.branding, orgBranding?.toServerBranding())
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
        resolveBranding(pendingConfig?.branding, orgBranding?.toServerBranding())
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
        // The tier's colour at 16% behind foreground text (BRAND-v1 4.1 tokens).
        val (text, tone) = when (riskTier.lowercase()) {
            "critical" -> getString(R.string.usesense_risk_critical) to CaptureTheme.Tone.ERROR
            "high" -> getString(R.string.usesense_risk_high) to CaptureTheme.Tone.WARNING
            "medium" -> getString(R.string.usesense_risk_medium) to CaptureTheme.Tone.PRIMARY
            "low" -> getString(R.string.usesense_risk_low) to CaptureTheme.Tone.SUCCESS
            else -> riskTier.uppercase() to null
        }
        riskTierBadge.text = text
        riskTierBadge.setTextColor(captureTheme.page.foreground)
        riskTierBadge.typeface = styler.bodyMedium
        riskTierBadge.background = styler.rounded(
            tone?.let(captureTheme::toneTile) ?: captureTheme.page.surface,
            6f * resources.displayMetrics.density,
        )
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
        loadingText.text = cs(R.string.usesense_getting_ready)

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
                // A Flow's "Try again" re-resolves branding (BRAND-v1 6): the run
                // view's branding over the fresh init-session's.
                resolveBranding(config.branding, flowServerBranding(response))
                applyBranding()
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
            afterSessionReady()
        } catch (e: Exception) {
            showError("Unexpected error: ${e.message}")
        }
    }

    // ─── Primer ──────────────────────────────────────────────────────────

    /**
     * Session ready: the primer once per verification (not on "Try again"),
     * then the permissions. "Two quick steps" when the policy already has a
     * movement, so nobody is surprised by the second part. A Flow shows its
     * own face primer, and a remote hosted link its intro.
     */
    private fun afterSessionReady() {
        debugHooks?.forceScreen?.let { name ->
            OutcomePages.byStoryboardName(name)?.let { kind -> showPage(kind, onPrimary = {}, onSecondary = {}); return }
        }
        if (usesSdkCaptureSequence && !isFlowRun && !primerShown) {
            primerShown = true
            showPage(if (hasChallengeToExplain()) PageKind.PRIMER_TWO else PageKind.PRIMER, onPrimary = { requestCapturePermissions() })
            return
        }
        requestCapturePermissions()
    }

    private fun requestCapturePermissions() {
        // A session that ran out while the person sat on the primer (15 min)
        // would only fail at upload, after the whole scan. Get a fresh one now,
        // quietly; once per attempt, so a wrong device clock can't loop.
        val sess = session
        if (sess != null && !expiryRefreshed && sess.endsSoon() && (!isFlowRun || pendingSessionFactory != null)) {
            expiryRefreshed = true
            Log.i(TAG, "Session ${sess.sessionId} expires at ${sess.expiresAt}; starting a fresh one")
            restartCapture()
            return
        }
        expiryRefreshed = false
        eventEmitter.emit(EventType.PERMISSIONS_REQUESTED)
        checkAndRequestPermissions()
    }

    // ─── Permissions ─────────────────────────────────────────────────────

    /** Speak Digits needs the microphone; an older audio challenge records only if it may. */
    private fun needsMicrophone(sess: UseSenseSession) = sess.requiresAudio || sess.challengeNeedsMicrophone

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        val sess = session ?: return
        val permsNeeded = mutableListOf(Manifest.permission.CAMERA)
        if (needsMicrophone(sess)) {
            permsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        val notGranted = permsNeeded.filter { !granted(it) }

        if (notGranted.isEmpty()) {
            eventEmitter.emit(EventType.PERMISSIONS_GRANTED, mapOf("camera" to true))
            onPermissionsGranted()
        } else if (usesSdkCaptureSequence) {
            // Storyboard: the primer explains, then the system prompt straight away.
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            showPermissionScreen(notGranted)
        }
    }

    /** Camera granted: carry on, unless Speak Digits can't have the microphone. */
    private fun onCameraGranted() {
        val sess = session ?: return
        if (sess.challengeNeedsMicrophone && !granted(Manifest.permission.RECORD_AUDIO)) {
            eventEmitter.emit(EventType.PERMISSIONS_DENIED, mapOf("microphone" to false))
            showMicNeeded(duringCheck = false)
            return
        }
        onPermissionsGranted()
    }

    /** The app's page in system Settings (camera or microphone turned off there). */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** True when the mic page shows over a running capture (the recorder failed to start). */
    private var micNeededDuringCheck = false
    private var awaitingCameraSettings = false

    /**
     * "Microphone needed": Open Settings, or a head movement instead. The
     * second asks the server for a Follow Dot in place of Speak Digits (same
     * strength) and carries on with it, so nobody is stuck.
     */
    private fun showMicNeeded(duringCheck: Boolean) {
        micNeededDuringCheck = duringCheck
        showPage(
            PageKind.MIC_NEEDED,
            onPrimary = { awaitingMicSettings = true; openAppSettings() },
            onSecondary = { applyMicFallback() },
        )
    }

    private fun applyMicFallback() {
        val sess = session ?: return
        pagePrimary.isEnabled = false
        pageSecondary.isEnabled = false
        awaitingMicSettings = false
        mainScope.launch {
            val result = sess.applyMicrophoneFallback()
            pagePrimary.isEnabled = true
            pageSecondary.isEnabled = true
            result.onSuccess {
                Log.i(TAG, "Microphone unavailable: the server swapped Speak Digits for ${it.challengeType}")
                challengePresenter = sess.createChallengePresenter()
                if (micNeededDuringCheck) resumeCheckAfterMicPage() else onPermissionsGranted()
            }.onFailure { e ->
                val error = (e as? ApiException)?.useSenseError ?: UseSenseError.networkError(e.message)
                Log.w(TAG, "challenge-fallback failed: ${error.code} ${error.serverCode}")
                eventEmitter.emit(EventType.ERROR, errorEvent("challenge_fallback", error))
                showErrorPage(error, beforeUpload = true)
            }
        }
    }

    /** Back from the mic page to the running capture: the movement starts again on the same scan. */
    private fun resumeCheckAfterMicPage() {
        micNeededDuringCheck = false
        val presenter = challengePresenter ?: run { onCaptureComplete(captureRound); return }
        setStep(PageStep.CAPTURE)
        beginExtraCheck(ExtraCheckTiming.BEFORE_SCAN, presenter)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingMicSettings && pageShown == PageKind.MIC_NEEDED && granted(Manifest.permission.RECORD_AUDIO)) {
            awaitingMicSettings = false
            if (micNeededDuringCheck) resumeCheckAfterMicPage() else onPermissionsGranted()
        } else if (awaitingCameraSettings && pageShown == PageKind.CAMERA_DENIED && granted(Manifest.permission.CAMERA)) {
            awaitingCameraSettings = false
            checkAndRequestPermissions()
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
        if (usesSdkCaptureSequence) {
            // "The camera stopped": another app may have it.
            showPage(PageKind.CAMERA_ERROR, onPrimary = {
                session?.setCapturePhase(CapturePhase.CAMERA_REQUEST)
                checkAndRequestPermissions()
            })
            return
        }
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
        // Straight to the camera: the primer explained, and a movement runs on
        // the scan screen itself (no instructions overlay any more).
        setStep(PageStep.CAPTURE)
        startCameraAndCapture()
    }



    // ─── Camera ──────────────────────────────────────────────────────────

    private fun startCameraAndCapture() {
        val sess = session ?: return
        frameCaptureManager = sess.initCapture()
        challengePresenter = sess.createChallengePresenter()

        // A fresh attempt: fresh scan clock, gate history and hints.
        sess.guidedScan = pendingConfig?.guidedCaptureEnabled != false
        cancelColour()
        colourPlayer = null
        sess.colourEvidence = null
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
                boundCamera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

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
        if (!hooks.usesFakeCamera) return false
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
            // Gate thread: the contour relief is built here, the view only eases toward it.
            val relief = buildRelief(frame)
            runOnUiThread { if (!isFinishing && !isDestroyed) onGateFrame(frame, relief) }
        }.also { liveAnalyzer = it }
        qualityGate.coverStats = analyzer.coverStats
        analyzer.initialize { ok -> if (!ok) runOnUiThread { gateUnavailable = true } }
    }

    /** About 3 s of banked capture (SPEC), or the server's shorter duration if it asks for less. */
    private fun scanTotalMs(sess: UseSenseSession): Long =
        (sess.uploadConfig?.captureDurationMs ?: DEFAULT_SCAN_MS).toLong()
            .coerceAtMost(TARGET_SCAN_MS)
            // A movement follows in the same capture: the scan is the old 2 s
            // baseline, so the frame budget the server sized still fits both.
            .let { if (challengePresenter != null) it.coerceAtMost(BEFORE_CHECK_SCAN_MS) else it }
            .coerceAtLeast(MIN_SCAN_MS)

    /** The oval guide: hints until every check passes, then the scan starts by itself. */
    private fun enterAlign(sess: UseSenseSession) {
        // Back to align (first time, or a dropped scan): the scan is over.
        fillLightLatch.scanEnded()
        // A dropped window takes its frames, so its colour segments go too.
        colourPlayer?.resetForNewWindow()
        colourFromBankedMs = -1L
        sess.colourEvidence = null
        endCloserVisuals()
        farEyePx.clear()
        // The covered-face numbers ride in metadata.covered_check (COVER-v1 section 3).
        sess.coveredCheck = { liveAnalyzer?.takeIf { it.coverCheckOn || it.coverStatsTouched }?.coverStats?.toJson() }
        finalCoverSince = 0L
        handler.removeCallbacks(scanBlurOn)
        setScanBlur(false)
        sess.setCapturePhase(CapturePhase.FACE_GUIDE)
        scanStage = ScanStage.ALIGN
        val now = SystemClock.elapsedRealtime()
        alignStartedAt = now
        val controller = scanController ?: ScanController(scanTotalMs(sess)).also { scanController = it }
        controller.beginAlign(now)

        challengeOverlay.visibility = View.GONE
        faceGuideOverlay.visibility = View.VISIBLE
        showCaptureText(false)
        scanView.colors = captureTheme.camera
        scanView.tone = ContourScanView.Tone.NEUTRAL
        scanView.showLines = true
        scanView.ringColorOverride = null
        extraCheckView.reset()
        scanView.scanning = false
        scanView.paused = false
        scanView.resetProgress()
        hintScheduler.reset()
        scanCopyShown = false
        renderHint(null)
        shutterButton.visibility = View.GONE

        captureTopBar.visibility = View.VISIBLE
        shutterButton.setOnClickListener { startScan(sess, manual = true) }

        liveAnalyzer?.active = true
        handler.removeCallbacks(scanTicker)
        handler.post(scanTicker)
        debugHooks?.forceScreen?.takeIf { OutcomePages.byStoryboardName(it) == null }?.let { name ->
            handler.postDelayed({ if (session === sess && !isFinishing) holdDebugScreen(sess, name) }, DEBUG_SCREEN_DELAY_MS)
        }
    }

    /**
     * Test rig: hold one storyboard screen for its screenshot. The extra-check
     * states come from the real runner, driven to that state with made-up input.
     */
    private fun holdDebugScreen(sess: UseSenseSession, name: String) {
        stopScanLoop()
        scanStage = ScanStage.IDLE
        val turn = listOf(ExtraCheckStep(2000, direction = "left"), ExtraCheckStep(2000, direction = "right"))
        val dots = listOf(ExtraCheckStep(1500, x = 0.77f, y = 0.25f), ExtraCheckStep(1500, x = 0.24f, y = 0.56f),
            ExtraCheckStep(1500, x = 0.7f, y = 0.6f))
        val digits = listOf(ExtraCheckStep(4000))
        val ok = ExtraCheckInput(gatePasses = true, turn = -0.4f, micLevel = 0.6f)
        fun drive(kind: ExtraCheckKind, steps: List<ExtraCheckStep>, vararg inputs: Pair<Long, ExtraCheckInput>): ExtraCheckRunner {
            val r = ExtraCheckRunner(kind, steps)
            r.start(0L)
            var t = 0L
            for ((until, input) in inputs) { while (t < until) { t += 50; r.tick(t, input) } }
            return r
        }
        val runner: ExtraCheckRunner? = when (name) {
            "head_left" -> drive(ExtraCheckKind.HEAD_TURN, turn, 600L to ok)
            "head_right" -> drive(ExtraCheckKind.HEAD_TURN, turn, 2850L to ok)
            "head_paused" -> drive(ExtraCheckKind.HEAD_TURN, turn, 1600L to ok, 2000L to ok.copy(gatePasses = false))
            "head_covered" -> drive(ExtraCheckKind.HEAD_TURN, turn, 1600L to ok, 2000L to ok.copy(gatePasses = false))
                .also { extraCheckBlocking = listOf(GateHint.FACE_COVERED) }
            "head_again" -> drive(ExtraCheckKind.HEAD_TURN, turn, 2050L to ok.copy(turn = -0.05f))
            "dot_1" -> drive(ExtraCheckKind.FOLLOW_DOT, dots, 1000L to ok)
            "dot_2" -> drive(ExtraCheckKind.FOLLOW_DOT, dots, 2900L to ok)
            "dot_lost" -> drive(ExtraCheckKind.FOLLOW_DOT, dots, 2000L to ok, 2500L to ok.copy(gatePasses = false))
            "digits" -> drive(ExtraCheckKind.SPEAK_DIGITS, digits, 1800L to ok)
            "digits_quiet" -> drive(ExtraCheckKind.SPEAK_DIGITS, digits, 2400L to ok.copy(micLevel = 0f))
            "xc_done" -> drive(ExtraCheckKind.HEAD_TURN, turn, 2000L to ok, 4200L to ok.copy(turn = 0.4f))
            else -> null
        }
        when {
            name == "xc_head" -> showExtraCheckSheet(ExtraCheckKind.HEAD_TURN, turn) {}
            name == "xc_dot" -> showExtraCheckSheet(ExtraCheckKind.FOLLOW_DOT, dots) {}
            name == "xc_digits" -> showExtraCheckSheet(ExtraCheckKind.SPEAK_DIGITS, digits) {}
            name == "upload_failed" -> showActionSheet(
                StrokeIconView.Icons.UPLOAD, CaptureTheme.Tone.WARNING,
                cs(R.string.usesense_upload_failed_title), cs(R.string.usesense_upload_failed_body),
                cs(R.string.usesense_page_try_again, com.usesense.sdk.ui.theme.CaptureCopyKeys.UPLOAD_RETRY),
            ) {}
            name == "leave" -> { showGuidance(cs(R.string.usesense_hint_hold_still), null); showLeaveDialog() }
            name == "inline_flash" || name == "inline_rmas" -> {
                faceGuideOverlay.visibility = View.VISIBLE
                captureTopBar.visibility = View.VISIBLE
                handler.removeCallbacks(scanBlurOn)
                setScanBlur(false)
                scanView.scanning = true
                scanView.showLines = false
                scanView.ringColorOverride = null
                if (name == "inline_flash") {
                    ensureFlashView().setBackgroundColor(CaptureFixed.FLASH_PREVIEW)
                    showGuidance(cs(R.string.usesense_hint_hold_still), cs(R.string.usesense_inline_flash_detail))
                } else {
                    showMicroAction(MicroActionDetector.SMILE, 2)
                }
            }
            runner != null -> {
                faceGuideOverlay.visibility = View.VISIBLE
                captureTopBar.visibility = View.VISIBLE
                handler.removeCallbacks(scanBlurOn)
                setScanBlur(false)
                scanView.scanning = true
                scanView.showLines = false
                scanView.ringColorOverride = captureTheme.camera.challenge
                extraCheckView.reset()
                extraCheckView.frozen = true
                extraCheckView.topInset = captureTopBar.bottom.toFloat()
                extraCheck = runner
                when (runner.kind) {
                    ExtraCheckKind.FOLLOW_DOT -> {
                        extraCheckView.dotVisible = true
                        val visited = dots.take(runner.stepIndex + 1)
                        extraCheckView.setTrail(listOf(0.5f to 0.4f) + visited.map { it.x to it.y })
                        val at = runner.currentStep ?: dots.last()
                        extraCheckView.moveDot(at.x, at.y)
                    }
                    ExtraCheckKind.SPEAK_DIGITS -> {
                        extraCheckView.digits = "4 7 2 9"
                        extraCheckView.setLevels(floatArrayOf(10f, 18f, 30f, 22f, 34f, 16f, 26f, 12f, 8f).map { it / 34f }.toFloatArray())
                    }
                    else -> Unit
                }
                if (runner.completed) {
                    finishExtraCheckVisuals(runner)
                } else {
                    renderExtraCheck(runner)
                }
                extraCheck = null
            }
            else -> Log.w(TAG, "Unknown forceScreen $name")
        }
    }

    /**
     * The contour relief for [frame] at the view's size (gate thread). Landmarks
     * are mapped to screen px as the preview shows them (mirrored aspect-fill).
     */
    /** When the scan completed and is waiting for its final covered-face check (0 = not waiting). */
    private var finalCoverSince = 0L
    /** Freezes the fill light while a scan runs. */
    private val fillLightLatch = FillLightLatch()
    /** Last align tick, for the time "Keep your face uncovered" was shown. */
    private var lastAlignTickAt = 0L
    @Volatile private var reliefLogAt = 0L
    private var reliefFrames = 0
    private var reliefMs = 0L

    private fun buildRelief(frame: GateFrame): Relief? {
        val pts = frame.points ?: return null
        val w = scanView.reliefWidth
        val h = scanView.reliefHeight
        if (w <= 0f || h <= 0f) return null
        val n = minOf(frame.landmarkCount, MAX_LANDMARKS)
        val xs = FloatArray(n); val ys = FloatArray(n); val zs = FloatArray(n)
        val out = FloatArray(2)
        val mapper = PreviewMapper(frame.imageWidth, frame.imageHeight, w, h)
        for (i in 0 until n) {
            mapper.map(pts[i * 3], pts[i * 3 + 1], out)
            xs[i] = out[0]; ys[i] = out[1]; zs[i] = pts[i * 3 + 2]
        }
        val t0 = SystemClock.elapsedRealtime()
        val relief = runCatching { ReliefBuilder.build(xs, ys, zs, n, w, h) }.getOrNull()
        if (Log.isLoggable(GATE_TAG, Log.DEBUG)) {
            reliefFrames++
            reliefMs += SystemClock.elapsedRealtime() - t0
            val now = SystemClock.elapsedRealtime()
            if (now - reliefLogAt >= 2000) {
                Log.d(GATE_TAG, "gate %.1f fps, relief %.1f ms".format(java.util.Locale.US,
                    reliefFrames * 1000f / (now - reliefLogAt), reliefMs.toFloat() / reliefFrames.coerceAtLeast(1)))
                reliefLogAt = now; reliefFrames = 0; reliefMs = 0
            }
        }
        return relief
    }

    /** A gate frame from the live mesh (main thread), with its relief built on the gate thread. */
    private fun onGateFrame(frame: GateFrame, relief: Relief? = null) {
        if (scanStage == ScanStage.CHALLENGE) {
            // The same quality gate as the scan (face, one person, covered,
            // distance with the scan's margin, in frame, sharp, eyes open);
            // only pose and stillness give way to the movement asked for.
            val oval = scanView.ovalSpec() ?: return
            val mapper = PreviewMapper(frame.imageWidth, frame.imageHeight, scanView.width.toFloat(), scanView.height.toFloat())
            val (_, turn) = ExtraCheckFace.read(frame.points, frame.faceCount, mapper, oval)
            val now = SystemClock.elapsedRealtime()
            val runner = extraCheck
            val motion = runner?.let { ExtraCheckMotion.forStep(it, turn, previousStep, now - stepStartedAt) }
            val result = qualityGate.evaluate(frame, mapper, oval, null, scanning = true, motion = motion)
            lastGate = result
            lastGateAt = now
            if (now - lastGateLogAt >= 1000 && Log.isLoggable(GATE_TAG, Log.DEBUG)) {
                lastGateLogAt = now
                Log.d(GATE_TAG, "challenge failing=${result.failing} motion=$motion turn=$turn ${result.metrics ?: ""}")
            }
            extraCheckFace = ExtraCheckInput(gatePasses = result.allPass, turn = turn)
            extraCheckBlocking = result.blocking
            extraCheckFaceAt = now
            return
        }
        if (scanStage == ScanStage.CLOSER) {
            // EVIDENCE-v1 near phase: the gate's pose, stillness and sharpness
            // (with a face, one person, eyes open). Distance and in-frame are
            // left out (coming closer is the point, and a near face overflows
            // the oval), as are light and covered: the change watch compares
            // regions with the scan's baseline, which all change as the face
            // grows (iOS device run), and the final cover check already ran.
            val oval = scanView.ovalSpec() ?: return
            val mapper = PreviewMapper(frame.imageWidth, frame.imageHeight, scanView.width.toFloat(), scanView.height.toFloat())
            val result = qualityGate.evaluate(frame, mapper, oval, null, scanning = true)
            val now = SystemClock.elapsedRealtime()
            lastGate = result
            lastGateAt = now
            closerGatePasses = result.failing.all { it.check in CLOSER_IGNORED_CHECKS }
            closerEyePx = result.metrics?.eyeDistancePx?.takeIf { it > 0f }
            if (relief != null) scanView.updateRelief(relief) else if (frame.points == null) scanView.clearFace()
            return
        }
        if (scanStage == ScanStage.INLINE) {
            // Inline step-up: watch for the prompted micro-action.
            val oval = scanView.ovalSpec() ?: return
            val mapper = PreviewMapper(frame.imageWidth, frame.imageHeight, scanView.width.toFloat(), scanView.height.toFloat())
            val (_, turn) = ExtraCheckFace.read(frame.points, frame.faceCount, mapper, oval)
            if (microAction?.feed(frame.points, frame.eyeBlinkLeft, frame.eyeBlinkRight, turn) == true) {
                microActionSeen?.complete(Unit)
            }
            return
        }
        if (scanStage != ScanStage.ALIGN && scanStage != ScanStage.PASSIVE) return
        val sess = session ?: return
        // On-device attack detection watches the live face from align through
        // the scan (pose tremor, smoothness, brightness, sharpness patterns).
        frame.pose?.let { p ->
            sess.suspicionEngine.analyzeFrame(
                HeadPose(p.yaw, p.pitch, p.roll),
                frame.faceRegion?.meanLuma ?: 0.0,
                frame.faceRegion?.sharpness ?: 0.0,
                frame.timestampMs,
            )
        }
        val oval = scanView.ovalSpec() ?: return
        val now = SystemClock.elapsedRealtime()
        val mapper = PreviewMapper(frame.imageWidth, frame.imageHeight, scanView.width.toFloat(), scanView.height.toFloat())
        val result = qualityGate.evaluate(frame, mapper, oval, fillLightSince?.let { now - it }, scanning = scanStage == ScanStage.PASSIVE)
        lastGate = result
        lastGateAt = now
        if (now - lastGateLogAt >= 1000 && Log.isLoggable(GATE_TAG, Log.DEBUG)) {
            // adb shell setprop log.tag.UseSenseGate DEBUG
            lastGateLogAt = now
            Log.d(GATE_TAG, "faces=${frame.faceCount} failing=${result.failing} ${result.metrics ?: ""}")
        }
        result.metrics?.takeIf { it.cover >= 1f && it.coverRegion >= 0 && Log.isLoggable(GATE_TAG, Log.DEBUG) }?.let {
            Log.d(GATE_TAG, "change watch exceeded: %.2f@%s covered=%s".format(java.util.Locale.US,
                it.cover, FaceRegions.NAMES[it.coverRegion], GateHint.FACE_COVERED in result.failing))
        }
        if (result.faceTooDark) turnOnFillLight()

        val pts = frame.points
        if (relief != null) scanView.updateRelief(relief) else if (pts == null) scanView.clearFace()

        if (scanStage == ScanStage.PASSIVE && finalCoverSince == 0L) {
            frame.cover?.let { liveAnalyzer?.coverStats?.scanFrame(it) }
            // The closer step's far phase: the last passing scan frames' eye distance.
            result.metrics?.eyeDistancePx?.takeIf { it > 0f && result.allPass }?.let {
                farEyePx.addLast(it)
                while (farEyePx.size > FAR_EYE_SAMPLES) farEyePx.removeFirst()
            }
        }

        // The scan's final covered-face check: a result from after the scan.
        if (scanStage == ScanStage.PASSIVE && finalCoverSince > 0L && frame.coverAt >= finalCoverSince) {
            val covered = frame.cover?.covered == true
            Log.i(TAG, "Final cover check: covered=$covered hand=${frame.cover?.bodySkin}")
            liveAnalyzer?.coverStats?.let { stats ->
                stats.finalCheck(frame.cover, now - finalCoverSince, if (covered) "covered" else "clear")
                if (covered) stats.scanDropForCover()
            }
            finalCoverSince = 0L
            if (covered) {
                sess.restartCaptureWindow()
                watchdog.disarm()
                enterAlign(sess)
            } else {
                finishPassiveScan()
            }
            return
        }

        val forced = debugHooks?.forceState
        if (forced == "pass" || forced == "pass_scan") {
            // Test rig: the whole flow with every check treated as passing.
            // A real person spends a moment aligning; so does the rig, so the
            // suspicion engine has its frames before the scan.
            if (scanStage == ScanStage.ALIGN && pts != null && now - alignStartedAt >= DEBUG_PASS_ALIGN_MS) startScan(sess, manual = false)
            return
        }
        if (scanStage == ScanStage.ALIGN && forced != null) {
            // Test rig: go to the forced state on the first face, gate or not.
            if (forced != "align" && pts != null) startScan(sess, manual = true)
            return
        }
        // No auto start while the help is open: the subject is reading, not posing.
        if (scanStage == ScanStage.ALIGN && result.allPass && !helpOpen) startScan(sess, manual = false)
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
            ScanStage.CHALLENGE -> {
                onExtraCheckTick(sess, now)
                return
            }
            ScanStage.CLOSER -> {
                onCloserTick(sess, now, fresh != null)
                return
            }
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
                if (hintScheduler.current == GateHint.FACE_COVERED && lastAlignTickAt > 0L) {
                    liveAnalyzer?.coverStats?.alignCovered((now - lastAlignTickAt).coerceIn(0L, 250L))
                }
                lastAlignTickAt = now
                // The stroke turns the warning colour while the instruction asks for an action.
                scanView.tone = if (hintScheduler.current != null) ContourScanView.Tone.WARNING else ContourScanView.Tone.NEUTRAL
            }
            ScanStage.PASSIVE -> {
                if (finalCoverSince > 0L) {
                    // Waiting for the final covered-face check; don't hang on it.
                    if (now - finalCoverSince > FINAL_COVER_WAIT_MS) {
                        Log.i(TAG, "Final cover check timed out; finishing")
                        liveAnalyzer?.coverStats?.finalCheck(null, now - finalCoverSince, "timeout")
                        finalCoverSince = 0L
                        finishPassiveScan()
                    }
                    return
                }
                debugHooks?.forceProgress?.takeIf { debugHooks?.forceState.let { it != null && it != "pass" && it != "pass_scan" } }?.let { held ->
                    // Test rig: hold the ring for the golden screenshot.
                    scanView.paused = false
                    scanView.tone = ContourScanView.Tone.READY
                    scanView.progress = held
                    renderScanCopy(controller)
                    return
                }
                val pass = gateUnavailable || debugHooks?.forceState.let { it == "pass" || it == "pass_scan" } || fresh?.allPass ?: true
                driveColour(controller, pass)
                val event = controller.tick(now, pass)
                if (event != null) {
                    Log.i(TAG, "Scan $event: banked=${controller.bankedMs} ms blocking=${fresh?.blocking.orEmpty()} " +
                        "dropped=${controller.droppedWindows}")
                }
                when (event) {
                    ScanController.Event.PAUSED -> {
                        sess.pauseCapture()
                        scanView.paused = true
                    }
                    ScanController.Event.RESUMED -> {
                        sess.resumeCapture()
                        scanView.paused = false
                    }
                    ScanController.Event.DROPPED -> {
                        if (fresh?.coverDrop == true) {
                            liveAnalyzer?.coverStats?.scanDropForCover()
                        }
                        // Paused too long: start the window over from align.
                        sess.restartCaptureWindow()
                        watchdog.disarm()
                        enterAlign(sess)
                        return
                    }
                    ScanController.Event.COMPLETED -> {
                        scanView.progress = 1f
                        colourPlayer?.let { p ->
                            p.interrupt(com.usesense.sdk.evidence.ColourSequencePlayer.SCAN_ENDED)
                            sess.colourEvidence = p.record
                            Log.i(TAG, "Colour: completed=${p.record.completed} segments=${p.record.segments.size} " +
                                "awb=${p.record.awbLocked} runs=${p.runs}")
                        }
                        val plan = CoveredCheckStats.finalPlan(
                            coverCheckOn = liveAnalyzer?.coverCheckOn == true,
                            rigForcesPass = debugHooks?.forceState.let { it == "pass" || it == "pass_scan" },
                        )
                        if (plan == CoveredCheckStats.FinalPlan.WAIT) {
                            // Covered-face segmentation lags ~1 s on slow phones:
                            // finish only once a result from after the last banked
                            // frame says the face is clear (onGateFrame).
                            finalCoverSince = now
                            return
                        }
                        // "skipped" only for a missing model; the debug rig says so itself.
                        liveAnalyzer?.coverStats?.finalCheck(null, null, CoveredCheckStats.skipOutcome(plan))
                        finishPassiveScan()
                        return
                    }
                    null -> Unit
                }
                if (controller.bankedMs >= BASELINE_MS && sess.capturePhase == CapturePhase.BASELINE) {
                    sess.setCapturePhase(CapturePhase.CHALLENGE)
                }
                // Monotonic: a colour replay extends the scan, the ring never runs back.
                scanView.progress = maxOf(scanView.progress, controller.progress)
                if (controller.state == ScanController.State.PAUSED) {
                    // Paused for a blocking check: say that one, not the light advice.
                    val failing = fresh?.blocking.orEmpty()
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

        // Every session scans first. A movement the policy already asks for
        // follows straight on from the scan (finishPassiveScan), on this screen.
        scanStage = ScanStage.PASSIVE
        fillLightLatch.scanStarted()
        liveAnalyzer?.let { a ->
            val (cover, age) = a.latestCover(now)
            a.coverStats.scanStarted(cover, age)
        }
        watchdog.arm(WatchdogPhase.CAPTURE)
        if (!sessionCaptureStarted) {
            sess.startCapture()
            sessionCaptureStarted = true
            eventEmitter.emit(EventType.CAPTURE_STARTED)
            // An older separate audio challenge records through the capture;
            // Speak Digits starts the microphone when its numbers show.
            if (sess.requiresAudio && !sess.challengeNeedsMicrophone) {
                sess.startAudioRecording()
                eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
            }
        } else {
            sess.resumeCapture()
        }
        sess.setCapturePhase(CapturePhase.BASELINE)
        ++captureRound
        closerHandled = false
        farEyePx.clear()
        prepareColourPlayer(sess)
        controller.start(now, manual)
        scanView.resetProgress()
        scanView.scanning = true
        scanView.paused = false
        scanView.tone = ContourScanView.Tone.READY
        scanCopyShown = false
        renderScanCopy(controller)
        // v3 prototype: as Revolut, the camera blurs shortly into the scan so the
        // lines carry the shape on their own.
        handler.removeCallbacks(scanBlurOn)
        handler.postDelayed(scanBlurOn, SCAN_BLUR_DELAY_MS)
        if (debugHooks?.forceState == "upload") {
            // Test rig: the upload sheet over the blurred camera, no network.
            stopScanLoop()
            setProcessingCopy(FinalizationPhase.UPLOADING)
            setStep(PageStep.FINALIZING)
            // Held still for the golden screenshot.
            handler.removeCallbacks(uploadBarTicker)
            showUploadBar(debugHooks?.forceProgress ?: DEBUG_UPLOAD_PROGRESS)
        }
    }

    private fun finishPassiveScan() {
        val sess = session ?: return
        // EVIDENCE-v1 step 5 first, on the scan screen, with the gate still running.
        if (startCloserStepIfNeeded(sess)) return
        completePassiveScan()
    }

    private fun completePassiveScan() {
        haptic()
        stopScanLoop()
        restoreFillLight()
        hintPill.announceForAccessibility(cs(R.string.usesense_scan_complete))
        val sess = session ?: return
        // On-device attack detection first (if the policy has it on and the
        // scan looked off), then the movement or the upload. A round 2 the
        // server asks for comes after the upload, so after this too.
        runInlineStepUpIfNeeded(sess) {
            val presenter = challengePresenter
            if (presenter != null && !stepUpRoundActive) {
                // Storyboard flow 5: the movement follows straight on, no sheet.
                beginExtraCheck(ExtraCheckTiming.BEFORE_SCAN, presenter)
            } else {
                onCaptureComplete(captureRound)
            }
        }
    }

    // ─── EVIDENCE-v1: colour sequence (section 6) and closer step (section 7) ──

    /** The colour player for this session's sequence, made once (round 1 only; one sequence id per session). */
    private fun prepareColourPlayer(sess: UseSenseSession) {
        if (stepUpRoundActive || colourPlayer != null) return
        val sequence = sess.evidenceConfig?.colourSequence ?: return
        colourPlayer = com.usesense.sdk.evidence.ColourSequencePlayer(sequence) { frameNanos ->
            // Choreographer time is System.nanoTime; the capture clock is elapsedRealtime.
            val offsetMs = (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1_000_000L
            sess.captureClockMs(frameNanos / 1_000_000L + offsetMs)
        }.also { it.host = colourHost }
    }

    /**
     * The colours play inside the scan, after the lead-in, while the gate
     * passes. A pause stops them (the partly shown colour is dropped) and the
     * replay starts once the scan resumes; while a play runs the scan holds
     * until it has finished, so frames land in every segment.
     */
    private fun driveColour(controller: ScanController, pass: Boolean) {
        val player = colourPlayer ?: return
        val choreographer = android.view.Choreographer.getInstance()
        if (!pass && player.isPlaying) {
            player.interrupt(com.usesense.sdk.evidence.ColourSequencePlayer.GATE_PAUSE)
            choreographer.removeFrameCallback(colourFrameCallback)
            colourFromBankedMs = -1L
            session?.colourEvidence = player.record
            Log.i(TAG, "Colour: paused by the gate at ${controller.bankedMs} ms, resumes at colour ${player.nextIndex}")
        } else if (pass && controller.state == ScanController.State.SCANNING && !player.isPlaying && !player.finished &&
            player.runs < com.usesense.sdk.evidence.ColourSequencePlayer.MAX_RUNS
        ) {
            player.play(System.nanoTime())
            colourFromBankedMs = controller.bankedMs
            choreographer.removeFrameCallback(colourFrameCallback)
            choreographer.postFrameCallback(colourFrameCallback)
            Log.i(TAG, "Colour: play ${player.runs + 1} at ${controller.bankedMs} ms")
        }
        val playing = player.isPlaying && colourFromBankedMs >= 0
        controller.require(if (playing) colourFromBankedMs + player.sequence.totalMs + COLOUR_TAIL_MS else 0L)
    }

    /** Stop the colours for good (attempt aborted, screen gone): tint off, brightness and white balance back. */
    private fun cancelColour() {
        android.view.Choreographer.getInstance().removeFrameCallback(colourFrameCallback)
        colourPlayer?.interrupt(com.usesense.sdk.evidence.ColourSequencePlayer.CANCELLED)
        colourFromBankedMs = -1L
    }

    /**
     * Step 5 of the scan: the oval grows by the server's `target_scale` and the
     * face follows it. Far frames are the scan's last banked frames; near
     * frames bank once the face is `min_scale` times closer, holds still and
     * the gate passes. Returns false when it doesn't run (recorded as skipped).
     */
    private fun startCloserStepIfNeeded(sess: UseSenseSession): Boolean {
        if (closerHandled || stepUpRoundActive) return false
        val evidence = sess.evidenceConfig ?: return false
        if (!sess.guidedScan) return false
        closerHandled = true
        val step = evidence.closerStep
        if (step == null || !step.enabled) {
            sess.skipCloserStep(com.usesense.sdk.evidence.CloserEvidence.DISABLED)
            return false
        }
        val far = com.usesense.sdk.evidence.Evidence.median(farEyePx.map { it.toDouble() })
        if (gateUnavailable || far == null || farEyePx.size < com.usesense.sdk.evidence.CloserStepController.PHASE_FRAMES) {
            // No live mesh to guide the face: carry on, the server records the step as missing.
            Log.i(TAG, "Closer: not run (gate=${!gateUnavailable}, far samples=${farEyePx.size})")
            sess.skipCloserStep(com.usesense.sdk.evidence.CloserEvidence.TIMEOUT)
            return false
        }
        val now = SystemClock.elapsedRealtime()
        closerStep = com.usesense.sdk.evidence.CloserStepController(far, step.minScale, now)
        closerEyePx = null
        closerGatePasses = false
        sess.pauseCapture()
        sess.beginCloserStep()
        scanStage = ScanStage.CLOSER
        scanView.paused = false
        scanView.tone = ContourScanView.Tone.READY
        scanView.ovalScale = step.targetScale.toFloat()
        placeHint()
        showGuidance(cs(R.string.usesense_hint_move_closer), null)
        hintPill.announceForAccessibility(cs(R.string.usesense_hint_move_closer))
        Log.i(TAG, "Closer: started, far eye %.1f px, need %.1f px".format(java.util.Locale.US, far, far * step.minScale))
        return true
    }

    private fun onCloserTick(sess: UseSenseSession, now: Long, fresh: Boolean) {
        val step = closerStep ?: return
        val tick = step.tick(
            nowMs = now,
            eye = if (fresh) closerEyePx?.toDouble() else null,
            gatePasses = fresh && closerGatePasses,
            nearFrames = sess.closerNearFrames(),
            budgetLeft = sess.closerBudgetLeft,
            bankedNear = sess.closerBankedFrames,
        )
        if (tick.bank) sess.resumeCapture() else sess.pauseCapture()
        showGuidance(cs(if (tick.needsCloser) R.string.usesense_hint_move_closer else R.string.usesense_hint_hold_still), null)
        if (tick.done) {
            sess.pauseCapture()
            sess.endCloserStep()
            Log.i(TAG, "Closer: ended, near frames=${sess.closerNearFrames()} banked=${sess.bankedFrameCount}")
            endCloserVisuals()
            scanStage = ScanStage.PASSIVE
            completePassiveScan()
        }
    }

    private fun endCloserVisuals() {
        closerStep = null
        if (!::scanView.isInitialized) return
        scanView.ovalScale = 1f
        placeHint()
    }

    // ─── On-device attack detection (inline step-up) on the scan screen ──

    /**
     * Policy `inline_step_up.enabled` (the dashboard's Sensei check "Unusual
     * movement during the scan"): when the suspicion engine triggered during
     * the scan, run Flash Reflection and/or RMAS here, in the guided style
     * (plain guidance under the oval, amber ring while a prompt waits), and
     * attach their evidence to round 1's upload. Bounded at 15 s by the
     * orchestrator. Frames aren't banked meanwhile.
     */
    private fun runInlineStepUpIfNeeded(sess: UseSenseSession, then: () -> Unit) {
        val cfg = sess.inlineStepUpConfig
        if (cfg?.enabled != true || !sess.suspicionEngine.triggered || sess.stepUpEvidence != null) {
            val snap = sess.suspicionEngine.getSnapshot()
            Log.i(TAG, "Inline step-up not run: enabled=${cfg?.enabled} triggered=${sess.suspicionEngine.triggered} " +
                "score=${snap?.score} frames=${snap?.framesAnalyzed}")
            then()
            return
        }
        val score = sess.suspicionEngine.currentScore
        Log.i(TAG, "Inline step-up: suspicion $score >= ${cfg.suspicionThreshold}, preferred=${cfg.preferredChallenge}")
        scanStage = ScanStage.INLINE
        sess.pauseCapture()
        watchdog.disarm()
        faceGuideOverlay.visibility = View.VISIBLE
        captureTopBar.visibility = View.VISIBLE
        handler.removeCallbacks(scanBlurOn)
        setScanBlur(false)
        scanView.scanning = true
        scanView.showLines = false
        scanView.ringColorOverride = null
        scanView.paused = false
        scanView.resetProgress()
        extraCheckView.reset()
        liveAnalyzer?.active = true
        val overlay = ensureFlashView()
        eventEmitter.emit(EventType.CHALLENGE_STARTED, mapOf("type" to "inline_step_up", "suspicion" to score))
        inlineJob?.cancel()
        inlineJob = mainScope.launch {
            val orchestrator = StepUpOrchestrator(cfg.suspicionThreshold, cfg.preferredChallenge)
            orchestrator.execute(
                suspicionScore = score,
                overlayView = overlay,
                sampleFrame = { previewBitmap() },
                showAction = { type, _, step -> showMicroAction(type, step) },
                detectAction = { type, windowMs -> watchMicroAction(type, windowMs) },
                getReactionTimeMs = { 0L },
                onShowIntro = {
                    showGuidance(cs(R.string.usesense_inline_intro), cs(R.string.usesense_inline_intro_detail))
                    hintPill.announceForAccessibility(cs(R.string.usesense_inline_intro))
                    // Flash Reflection runs right after the intro: say the screen will change.
                    handler.postDelayed({
                        if (scanStage == ScanStage.INLINE && microAction == null) {
                            showGuidance(cs(R.string.usesense_hint_hold_still), cs(R.string.usesense_inline_flash_detail))
                        }
                    }, INLINE_INTRO_MS)
                },
                onShowComplete = {
                    overlay.setBackgroundColor(Color.TRANSPARENT)
                    scanView.paused = false
                    scanView.ringColorOverride = captureTheme.camera.success
                    scanView.progress = 1f
                    extraCheckView.tick = true
                    showGuidance(cs(R.string.usesense_xc_done), null)
                },
            )
            sess.stepUpEvidence = orchestrator.toJson()
            Log.i(TAG, "Inline step-up done: ${sess.stepUpEvidence}")
            overlay.setBackgroundColor(Color.TRANSPARENT)
            microAction = null
            liveAnalyzer?.active = false
            scanStage = ScanStage.IDLE
            extraCheckView.reset()
            scanView.ringColorOverride = null
            then()
        }
    }

    /** A full-screen colour layer over the camera, under the guide (Flash Reflection). */
    private fun ensureFlashView(): View =
        flashView ?: View(this).also { v ->
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            videoContainer.addView(
                v,
                videoContainer.indexOfChild(faceGuideOverlay).coerceAtLeast(0),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            flashView = v
        }

    /** The camera image alone (no overlays), for Flash Reflection's face sample. */
    private fun previewBitmap(): android.graphics.Bitmap? {
        fakePreview?.takeIf { it.visibility == View.VISIBLE }?.let { iv ->
            val bmp = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
            return runCatching { bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        return runCatching { cameraPreview.bitmap }.getOrNull()
    }

    /** An RMAS prompt: plain guidance under the oval, the ring amber while it waits. */
    private fun showMicroAction(type: String, step: Int) {
        showGuidance(cs(MicroActionDetector.prompt(type)), null)
        hintPill.announceForAccessibility(cs(MicroActionDetector.prompt(type)))
        scanView.paused = true
        scanView.progress = ((step - 1) / RMAS_STEPS).coerceIn(0f, 1f).let { if (it == 0f) 0.004f else it }
    }

    /** Wait up to [windowMs] for the gate's mesh to show [type]. */
    private suspend fun watchMicroAction(type: String, windowMs: Long): Boolean {
        val seen = CompletableDeferred<Unit>()
        microActionSeen = seen
        microAction = MicroActionDetector(type)
        val ok = withTimeoutOrNull(windowMs) { seen.await() } != null
        microAction = null
        if (ok) haptic()
        return ok
    }

    // ─── Extra check on the scan screen (storyboard flows 4 and 5) ───────

    /**
     * Run [presenter]'s challenge on the scan screen: full-bleed camera, the
     * oval ring in purple, the movement drawn over it. Before the scan it
     * continues the same capture; after the scan it fills the step-up round's
     * fresh buffer. The runner owns the timing; the presenter tags frames.
     */
    private fun beginExtraCheck(timing: ExtraCheckTiming, presenter: ChallengePresenter) {
        val sess = session ?: return
        val spec = presenter.spec
        val kind = ExtraCheckKind.of(spec.type) ?: run {
            Log.w(TAG, "No extra-check screen for ${spec.type}; finishing the capture")
            onCaptureComplete(captureRound)
            return
        }
        val runner = ExtraCheckRunner(kind, ExtraCheckRunner.stepsFor(spec))
        extraCheck = runner
        extraCheckTiming = timing
        extraCheckPresenter = presenter
        extraCheckFace = ExtraCheckInput(gatePasses = true)
        extraCheckFaceAt = 0L
        extraCheckBlocking = emptyList()
        previousStep = null
        stepStartedAt = SystemClock.elapsedRealtime()
        hintScheduler.reset()

        if (kind == ExtraCheckKind.SPEAK_DIGITS) {
            val recordMs = (runner.steps.sumOf { it.durationMs } + ExtraCheckRunner.MAX_QUIET_HOLD_MS + DIGITS_RECORD_SLACK_MS).toInt()
            if (!granted(Manifest.permission.RECORD_AUDIO) || !sess.startAudioRecording(recordMs)) {
                // No microphone after all (refused since, or no input): offer the head movement.
                Log.w(TAG, "Microphone can't record; offering a head movement instead")
                extraCheck = null
                sess.pauseCapture()
                showMicNeeded(duringCheck = true)
                return
            }
            eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
        }

        scanStage = ScanStage.CHALLENGE
        sess.setCapturePhase(CapturePhase.CHALLENGE)
        sess.setCapturePhase(com.usesense.sdk.capture.CapturePhase.CHALLENGE)
        if (timing == ExtraCheckTiming.BEFORE_SCAN) sess.resumeCapture()
        // Subject-paced, like aligning: a face that leaves the oval waits as
        // long as it takes, so the watchdog doesn't supervise this.
        watchdog.disarm()

        faceGuideOverlay.visibility = View.VISIBLE
        captureTopBar.visibility = View.VISIBLE
        shutterButton.visibility = View.GONE
        handler.removeCallbacks(scanBlurOn)
        setScanBlur(false)
        scanView.scanning = true
        scanView.showLines = false
        scanView.ringColorOverride = captureTheme.camera.challenge
        scanView.paused = false
        scanView.tone = ContourScanView.Tone.READY
        scanView.resetProgress()
        extraCheckView.reset()
        extraCheckView.topInset = captureTopBar.bottom.toFloat()
        extraCheckView.dotVisible = kind == ExtraCheckKind.FOLLOW_DOT
        if (kind == ExtraCheckKind.SPEAK_DIGITS) extraCheckView.digits = ExtraCheckCopy.digitsText(spec.phrase)

        presenter.beginDriven()
        eventEmitter.emit(EventType.CHALLENGE_STARTED, mapOf("type" to spec.type, "timing" to timing.name.lowercase()))
        liveAnalyzer?.active = true
        lastGate = null
        val now = SystemClock.elapsedRealtime()
        handleExtraCheckEvents(runner, runner.start(now))
        renderExtraCheck(runner)
        handler.removeCallbacks(scanTicker)
        handler.post(scanTicker)
    }

    private fun onExtraCheckTick(sess: UseSenseSession, now: Long) {
        val runner = extraCheck ?: return
        if (leaveDialog.visibility == View.VISIBLE || helpOpen) {
            // Time stands still while the person reads.
            runner.hold(now)
            return
        }
        // No gate (MediaPipe missing) or a stalled one can't see the face: never block on it.
        val fresh = extraCheckFaceAt > 0L && now - extraCheckFaceAt < GATE_STALE_MS
        val forcedPass = debugHooks?.forceState == "pass"
        val face = if (fresh && !forcedPass) extraCheckFace else ExtraCheckInput(gatePasses = true, turn = extraCheckFace.turn)
        // The paused guidance names the check, with the scan's hint timing.
        hintScheduler.update(now, if (face.gatePasses) emptyList() else extraCheckBlocking)
        val level = if (runner.kind == ExtraCheckKind.SPEAK_DIGITS) sess.audioLevel() else null
        if (level != null) extraCheckView.pushLevel(level)
        handleExtraCheckEvents(runner, runner.tick(now, face.copy(micLevel = level)))
        if (extraCheck === runner) renderExtraCheck(runner)
    }

    private fun handleExtraCheckEvents(runner: ExtraCheckRunner, events: List<ExtraCheckEvent>) {
        val sess = session ?: return
        for (event in events) {
            Log.i(TAG, "Extra check ${runner.kind}: $event step=${runner.stepIndex} retry=${runner.retrying} progress=%.2f".format(java.util.Locale.US, runner.progress))
            when (event) {
                is ExtraCheckEvent.Step -> {
                    if (!event.retry) previousStep = runner.steps.getOrNull(event.index - 1)
                    stepStartedAt = SystemClock.elapsedRealtime()
                    extraCheckPresenter?.driveStep(event.index)
                    runner.currentStep?.let { step ->
                        if (runner.kind == ExtraCheckKind.FOLLOW_DOT) extraCheckView.moveDot(step.x, step.y)
                    }
                    if (event.retry) haptic()
                    announceExtraCheck(runner)
                }
                ExtraCheckEvent.Paused -> { sess.pauseCapture(); announceExtraCheck(runner) }
                ExtraCheckEvent.Resumed -> { sess.resumeCapture(); announceExtraCheck(runner) }
                ExtraCheckEvent.Quiet, ExtraCheckEvent.Heard -> announceExtraCheck(runner)
                ExtraCheckEvent.Completed -> { finishExtraCheck(runner); return }
            }
        }
    }

    /** Ring, arrow, dot, digits and the guidance line for the runner's state. */
    private fun renderExtraCheck(runner: ExtraCheckRunner) {
        // Once done, finishExtraCheck owns the screen (green ring, tick).
        if (runner.completed) return
        scanView.progress = runner.progress
        scanView.paused = runner.paused || runner.quiet
        val dir = runner.currentStep?.direction
        extraCheckView.arrow = if (runner.kind != ExtraCheckKind.HEAD_TURN || runner.paused || runner.completed) null else when (dir) {
            com.usesense.sdk.api.models.HeadTurnStep.LEFT -> ExtraCheckOverlayView.Arrow.LEFT
            com.usesense.sdk.api.models.HeadTurnStep.RIGHT -> ExtraCheckOverlayView.Arrow.RIGHT
            com.usesense.sdk.api.models.HeadTurnStep.UP -> ExtraCheckOverlayView.Arrow.UP
            com.usesense.sdk.api.models.HeadTurnStep.DOWN -> ExtraCheckOverlayView.Arrow.DOWN
            else -> null
        }
        extraCheckView.quiet = runner.quiet
        showGuidance(ExtraCheckCopy.guidance(runner, words = isWordsPhrase(), pausedBy = pausingHint()))
    }

    /** The check a paused movement waits on: the scheduled hint, else the latest frame's first. */
    private fun pausingHint(): GateHint? = hintScheduler.current ?: extraCheckBlocking.firstOrNull()

    private fun isWordsPhrase() = extraCheckPresenter?.spec?.phrase?.any { it.isLetter() } == true

    private fun showGuidance(g: Guidance) = showGuidance(cs(g.title), g.detail?.let { cs(it) })

    private fun announceExtraCheck(runner: ExtraCheckRunner) {
        val g = ExtraCheckCopy.guidance(runner, words = isWordsPhrase(), pausedBy = pausingHint())
        hintPill.announceForAccessibility(listOfNotNull(cs(g.title), g.detail?.let { cs(it) }).joinToString(". "))
    }

    private fun finishExtraCheckVisuals(runner: ExtraCheckRunner) {
        scanView.paused = false
        scanView.ringColorOverride = captureTheme.camera.success
        scanView.progress = 1f
        extraCheckView.arrow = null
        extraCheckView.dotVisible = false
        extraCheckView.digits = null
        extraCheckView.tick = true
        showGuidance(ExtraCheckCopy.guidance(runner))
    }

    /** "Done": a short green tick, then the upload (or round 2's upload). */
    private fun finishExtraCheck(runner: ExtraCheckRunner) {
        val sess = session ?: return
        extraCheckPresenter?.endDriven()
        handler.removeCallbacks(scanTicker)
        liveAnalyzer?.active = false
        scanStage = ScanStage.IDLE
        // The movement is over: no untagged frames while the tick shows.
        sess.pauseCapture()
        haptic()
        finishExtraCheckVisuals(runner)
        hintPill.announceForAccessibility(cs(R.string.usesense_xc_done))
        val round = captureRound
        handler.postDelayed({
            if (session !== sess || isFinishing) return@postDelayed
            extraCheck = null
            onCaptureComplete(round)
        }, EXTRA_CHECK_DONE_MS)
    }

    private fun stopScanLoop() {
        handler.removeCallbacks(scanTicker)
        liveAnalyzer?.active = false
        lastGate = null
        if (scanStage == ScanStage.ALIGN || scanStage == ScanStage.PASSIVE || scanStage == ScanStage.CLOSER ||
            scanStage == ScanStage.CHALLENGE
        ) scanStage = ScanStage.IDLE
    }

    private fun showShutter(sess: UseSenseSession) {
        if (shutterButton.visibility != View.VISIBLE) {
            Log.i(TAG, "Shutter offered: gateUnavailable=$gateUnavailable gate=${liveAnalyzer?.initState} " +
                "dropped=${scanController?.droppedWindows} aligningMs=${SystemClock.elapsedRealtime() - alignStartedAt}")
        }
        shutterButton.visibility = View.VISIBLE
        showGuidance(cs(R.string.usesense_shutter_title), null)
        scanView.tone = ContourScanView.Tone.NEUTRAL
        if (!shutterAnnounced) {
            shutterAnnounced = true
            hintPill.announceForAccessibility(hintTitle.text)
        }
    }

    /** During the scan the guidance is just "Hold still" (no counters or stage names, SPEC v1.1). */
    @Suppress("UNUSED_PARAMETER")
    private fun renderScanCopy(controller: ScanController) {
        showGuidance(cs(R.string.usesense_hint_hold_still), null)
        if (!scanCopyShown) {
            scanCopyShown = true
            hintPill.announceForAccessibility(hintTitle.text)
        }
    }

    /** Instruction and optional second line. Null for [GateHint.BRIGHTENING]: the fill light works silently. */
    private fun hintText(hint: GateHint): Pair<String, String?>? = when (hint) {
        GateHint.SHOW_FACE -> cs(R.string.usesense_hint_show_face) to null
        GateHint.ONE_PERSON -> cs(R.string.usesense_hint_one_person) to null
        GateHint.FACE_COVERED -> cs(R.string.usesense_hint_face_covered) to null
        GateHint.CENTRE -> cs(R.string.usesense_hint_centre) to null
        GateHint.MOVE_CLOSER -> cs(R.string.usesense_hint_move_closer) to null
        GateHint.MOVE_BACK, GateHint.TOO_BIG -> cs(R.string.usesense_hint_move_back) to null
        GateHint.LOOK_STRAIGHT -> cs(R.string.usesense_hint_look_straight) to null
        GateHint.BRIGHTENING -> null
        GateHint.FACE_LIGHT -> cs(R.string.usesense_hint_face_light) to
            cs(R.string.usesense_hint_face_light_detail)
        GateHint.TOO_BRIGHT -> cs(R.string.usesense_hint_too_bright) to
            cs(R.string.usesense_hint_too_bright_detail)
        GateHint.HOLD_STEADY -> cs(R.string.usesense_hint_hold_steady) to null
        GateHint.EYES_OPEN -> cs(R.string.usesense_hint_eyes_open) to null
        GateHint.HOLD_STILL -> cs(R.string.usesense_hint_hold_still) to null
    }

    /** The align instruction: the hint, or "Hold still" when nothing needs doing. */
    private fun renderHint(hint: GateHint?) {
        val text = hint?.let(::hintText)
        if (text == null) {
            showGuidance(cs(R.string.usesense_hint_hold_still), null)
        } else {
            showGuidance(text.first, text.second)
        }
    }

    /** Plain guidance text under the oval; a 200 ms crossfade when it changes. */
    private fun showGuidance(title: CharSequence, detail: CharSequence?) {
        hintPill.visibility = View.VISIBLE
        // Compare with the text being faded in, not the text on screen: the
        // scan tick asks again every 66 ms, faster than the fade, and
        // restarting it each time left the guidance stuck at alpha 0.
        val target = title.toString() to (detail?.toString() ?: "")
        if (target == guidanceTarget) return
        guidanceTarget = target
        val apply = {
            hintTitle.text = title
            hintDetail.text = detail ?: ""
            hintDetail.visibility = if (detail != null) View.VISIBLE else View.GONE
        }
        hintPill.animate().cancel()
        if (hintTitle.text.isNullOrEmpty()) {
            apply()
            hintPill.alpha = 1f
            return
        }
        hintPill.animate().alpha(0f).setDuration(GUIDANCE_FADE_MS / 2).withEndAction {
            apply()
            hintPill.animate().alpha(1f).setDuration(GUIDANCE_FADE_MS / 2).start()
        }.start()
    }

    /** Put the guidance's top edge 28 dp below the oval's bottom (SPEC v1.1). */
    private fun placeHint() {
        val h = faceGuideOverlay.height
        if (h == 0) return
        // The closer step's grown oval pushes the guidance down with it.
        val ovalBottom = h * (ContourScanView.OVAL_CY + ContourScanView.OVAL_RY_OF_H * scanView.ovalScale)
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
        // Frozen during a scan (COVER-v1): decided before it starts.
        if (!fillLightLatch.mayToggle()) return
        fillLightSince = SystemClock.elapsedRealtime()
        qualityGate.onLightChanged(fillLightSince!!)
        val lp = window.attributes
        savedBrightness = lp.screenBrightness
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = lp
        scanView.fillLight = true
    }

    private fun restoreFillLight() {
        // Only called as a scan ends (finish, drop, cancel), so the scan is over.
        fillLightLatch.scanEnded()
        if (fillLightSince == null) return
        fillLightSince = null
        qualityGate.onLightChanged(SystemClock.elapsedRealtime())
        val lp = window.attributes
        lp.screenBrightness = savedBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        savedBrightness = null
        scanView.fillLight = false
    }

    private val scanBlurOn = Runnable { if (scanStage == ScanStage.PASSIVE) setScanBlur(true) }

    /** Blur the camera under the contour scan (Android 12+; older versions keep it sharp). */
    private fun setScanBlur(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = if (on) {
            val r = SCAN_BLUR_DP * resources.displayMetrics.density
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        } else {
            null
        }
        cameraPreview.setRenderEffect(effect)
        fakePreview?.setRenderEffect(effect)
    }

    /** The face blurs out behind the processing sheet (Android 12+; older versions keep the opaque sheet). */
    private fun setPreviewBlurred(blurred: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // No blur before Android 12: a near-opaque sheet hides the face instead.
            uploadOverlay.setBackgroundColor(if (blurred) CaptureFixed.CAMERA_SCRIM_OPAQUE else CaptureFixed.CAMERA_SCRIM)
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
     * The bar follows time, not bytes ([UploadBar], as iOS): sending, then
     * checking once the upload is accepted, so it keeps moving while the
     * server works and never reads as stuck.
     */
    private fun setProcessingCopy(phase: FinalizationPhase) {
        // The bar's fill is `capture.ring` (styled with the sheet in applyBranding).
        uploadOverlayTitle.text = cs(R.string.usesense_upload_title)
        uploadOverlaySubtitle.text = cs(R.string.usesense_upload_detail)
        if (debugHooks?.forceState == "upload") return
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            FinalizationPhase.PREPARING, FinalizationPhase.UPLOADING -> uploadBar.sending(now)
            FinalizationPhase.COMPLETING -> uploadBar.checking(now)
        }
        handler.removeCallbacks(uploadBarTicker)
        handler.post(uploadBarTicker)
    }

    private fun renderUploadBar() = showUploadBar(uploadBar.value(SystemClock.elapsedRealtime()))

    private fun showUploadBar(value: Float) {
        uploadProgress.progress = (value.coerceIn(0f, 1f) * 1000).toInt()
        val percent = UploadBar.accessiblePercent(value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            uploadProgress.stateDescription = "$percent%"
        } else {
            uploadProgress.contentDescription = "$percent%"
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
                            // The bar follows time, not bytes (UploadBar); bytes only feed the watchdog.
                        }
                        is FinalizationUpdate.Result -> {
                            watchdog.disarm()
                            uploadBar.done()
                            renderUploadBar()
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
     * Round 1 uploaded and the server asked for an extra check (storyboard
     * flow 4). The camera is still bound behind the upload sheet: swap it for
     * "One more quick check" (one sentence, Continue), then run the challenge
     * on the scan screen with the purple ring.
     */
    private fun runStepUpRound(sess: UseSenseSession, instruction: StepUpInstruction) {
        stepUpRoundActive = true
        // Waiting on the person to read and tap: not supervised.
        watchdog.disarm()
        val kind = ExtraCheckKind.of(instruction.challenge.type) ?: ExtraCheckKind.HEAD_TURN
        showExtraCheckSheet(kind, ExtraCheckRunner.stepsFor(instruction.challenge)) {
            setStep(PageStep.CAPTURE)
            val presenter = sess.beginStepUpRound(instruction) ?: run {
                Log.w(TAG, "No presenter for step-up ${instruction.challenge.type}")
                stepUpRoundActive = false
                startFinalization(sess, FinalizationPhase.COMPLETING)
                return@showExtraCheckSheet
            }
            challengePresenter = presenter
            ++captureRound
            beginExtraCheck(ExtraCheckTiming.AFTER_SCAN, presenter)
        }
    }

    /** "One more quick check" over the blurred camera (the upload sheet's styling). */
    private fun showExtraCheckSheet(kind: ExtraCheckKind, steps: List<ExtraCheckStep>, onContinue: () -> Unit) {
        val icon = when (kind) {
            ExtraCheckKind.HEAD_TURN -> StrokeIconView.Icons.TURN
            ExtraCheckKind.FOLLOW_DOT -> StrokeIconView.Icons.DOT
            ExtraCheckKind.SPEAK_DIGITS -> StrokeIconView.Icons.MIC
        }
        // The sheet is page surface; its icon carries the camera's challenge accent.
        showActionSheet(
            icon, CaptureTheme.Tone.CHALLENGE,
            cs(R.string.usesense_xc_sheet_title), cs(ExtraCheckCopy.sheetBody(kind, steps)),
            cs(R.string.usesense_xc_continue), onContinue,
        )
    }

    /** The upload overlay with the action sheet in place of the upload sheet. */
    private fun showActionSheet(
        icon: String, tone: CaptureTheme.Tone, title: String, body: String, button: String, onTap: () -> Unit,
    ) {
        setStep(PageStep.FINALIZING)
        handler.removeCallbacks(uploadBarTicker)
        uploadSheet.visibility = View.GONE
        actionSheet.visibility = View.VISIBLE
        styler.tile(actionSheetIcon, icon, tone)
        actionSheetTitle.text = title
        actionSheetBody.text = body
        actionSheetPrimary.text = button
        actionSheetPrimary.isEnabled = true
        actionSheetPrimary.setOnClickListener {
            if (!actionSheetPrimary.isEnabled) return@setOnClickListener
            actionSheetPrimary.isEnabled = false
            hideActionSheet()
            onTap()
        }
        actionSheet.announceForAccessibility("$title. $body")
    }

    private fun hideActionSheet() {
        actionSheet.visibility = View.GONE
        uploadSheet.visibility = View.VISIBLE
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
        if (usesSdkCaptureSequence) {
            if (pendingConfig?.showResultScreens == false) {
                // The app shows its own screens: hand over the decision now.
                callbackGate.success(result)
                pendingResult = null
                finish()
                return
            }
            showResultPage(result)
            return
        }
        showResult(result.decision, result)
    }

    /** Retries left after a rejection: session mode only (a Flow has its own policy). */
    private fun retriesLeft(): Int =
        if (isDirectMode && !isFlowRun) ((pendingConfig?.maxRetries ?: 0) - retriesUsed).coerceAtLeast(0) else 0

    private fun showResultPage(result: UseSenseResult) {
        val left = retriesLeft()
        val kind = OutcomePages.forDecision(result.decision, left)
        val deliver = {
            pendingResult?.let { callbackGate.success(it) }
            pendingResult = null
            finish()
        }
        if (kind == PageKind.REJECTED_RETRY) {
            showPage(kind, retriesLeft = left, onPrimary = {
                retriesUsed++
                pendingResult = null
                restartCapture()
            }, onSecondary = deliver)
        } else {
            showPage(kind, onPrimary = deliver)
        }
    }

    // ─── Dark pages and the leave dialog ─────────────────────────────────

    /** "[App]" / `{app}`: the resolved display name, else the host app's label, else "this app". */
    private fun appName(): String = resolvedBranding.displayName.value ?: runCatching {
        applicationInfo.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
    }.getOrNull() ?: getString(R.string.usesense_this_app)

    /** Flows: the run view's branding over the init-session's (the fresh one on "Try again"). */
    private fun flowServerBranding(initSession: com.usesense.sdk.api.models.CreateSessionResponse?): ServerBranding? =
        BrandingResolver.mergeServer(pendingServerBranding, initSession?.branding?.toServerBranding())

    /** Show a storyboard page: icon, title, body, tips, a primary and maybe a secondary button. */
    private fun showPage(kind: PageKind, retriesLeft: Int = 0, onPrimary: () -> Unit, onSecondary: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        val spec = OutcomePages.spec(kind, retriesLeft)
        pageShown = kind
        hideLeaveDialog()
        hideHelpDialog()
        styler.tile(pageIcon, spec.icon, spec.tone)
        showIllustration(kind)
        pageTitle.text = cs(spec.title, spec.titleKey)
        pageBody.text = cs(spec.body)
        pageTips.removeAllViews()
        val density = resources.displayMetrics.density
        strings?.list(spec.tipsKey ?: "", spec.tips)?.takeIf { spec.tips.isNotEmpty() }.orEmpty().forEach { tip ->
            pageTips.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
                addView(View(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(captureTheme.page.primary) }
                }, LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt()).apply { marginEnd = (12 * density).toInt() })
                addView(TextView(context).apply {
                    text = tip
                    textSize = 15f
                    styler.style(this, CaptureStyler.Role.TEXT)
                })
            })
        }
        pageTips.visibility = if (spec.tips.isEmpty()) View.GONE else View.VISIBLE
        pagePrimary.text = cs(spec.primary)
        pagePrimary.isEnabled = true
        pagePrimary.setOnClickListener { if (pagePrimary.isEnabled) onPrimary() }
        val secondary = spec.secondary
        if (secondary != null && onSecondary != null) {
            pageSecondary.text = cs(secondary)
            pageSecondary.isEnabled = true
            pageSecondary.visibility = View.VISIBLE
            pageSecondary.setOnClickListener { if (pageSecondary.isEnabled) onSecondary() }
        } else {
            pageSecondary.visibility = View.GONE
        }
        setStep(PageStep.PAGE)
        pageView.scrollTo(0, 0)
        pageTitle.announceForAccessibility("${pageTitle.text}. ${pageBody.text}")
    }

    /**
     * `icons.success` / `review` / `notVerified` replace the icon tile on the
     * result pages (BRAND-v1 4.1); the tile shows until the image loads.
     */
    private fun showIllustration(kind: PageKind) {
        val icons = captureTheme.icons
        val url = when (kind) {
            PageKind.APPROVED -> icons?.success
            PageKind.REVIEW -> icons?.review
            PageKind.REJECTED, PageKind.REJECTED_RETRY -> icons?.notVerified
            else -> null
        }
        pageIllustration.visibility = View.GONE
        pageIcon.visibility = View.VISIBLE
        pageIllustration.tag = url
        if (url == null) return
        mainScope.launch {
            val bitmap = CaptureImages.load(url) ?: return@launch
            if (pageShown != kind || pageIllustration.tag != url) return@launch
            pageIllustration.setImageBitmap(bitmap)
            pageIllustration.visibility = View.VISIBLE
            pageIcon.visibility = View.GONE
        }
    }

    /** From the camera to the upload: leaving now ends the verification. */
    private fun isMidFlow(): Boolean =
        usesSdkCaptureSequence && pageShown == null && captureScreen.visibility == View.VISIBLE

    private fun showLeaveDialog() {
        leaveDialog.visibility = View.VISIBLE
        leaveDialog.announceForAccessibility(cs(R.string.usesense_leave_title))
    }

    private fun hideLeaveDialog() {
        if (::leaveDialog.isInitialized) leaveDialog.visibility = View.GONE
    }

    /** "Leave": end this verification (no resume) and tell the app it was cancelled. */
    private fun leaveVerification() {
        hideLeaveDialog()
        abortAttempt()
        callbackGate.cancel()
        finish()
    }

    private fun showTechnicalRecovery(sess: UseSenseSession, recovery: FinalizationUpdate.Recovery) {
        eventEmitter.emit(EventType.ERROR, errorEvent(recovery.phase.name.lowercase(), recovery.error))
        if (usesSdkCaptureSequence) {
            val kind = OutcomePages.forError(recovery.error, beforeUpload = false)
            if (RecoveryAction.RETRY in recovery.actions && kind == PageKind.ERROR && isConnectionError(recovery.error)) {
                // Storyboard flow 3: the retries ran out. The scan is kept, so
                // "Try again" sends it again without a new scan.
                pendingTerminalError = recovery.error
                callbackGate.recovery()
                showActionSheet(
                    StrokeIconView.Icons.UPLOAD, CaptureTheme.Tone.WARNING,
                    cs(R.string.usesense_upload_failed_title), cs(R.string.usesense_upload_failed_body),
                    cs(R.string.usesense_page_try_again, com.usesense.sdk.ui.theme.CaptureCopyKeys.UPLOAD_RETRY),
                ) {
                    pendingTerminalError = null
                    startFinalization(sess, recovery.phase)
                }
                return
            }
            showErrorPage(recovery.error, beforeUpload = false)
            return
        }
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
        if (usesSdkCaptureSequence) {
            showErrorPage(error, beforeUpload = phase == WatchdogPhase.CAMERA.wire)
            return
        }
        showRecoveryDialog(error, canRetryUpload = false, retry = null)
    }

    private fun isConnectionError(error: UseSenseError) = error.code == UseSenseError.NETWORK_ERROR ||
        error.code == UseSenseError.NETWORK_TIMEOUT || error.code == UseSenseError.UPLOAD_FAILED ||
        error.code == UseSenseError.UPLOAD_TIMEOUT || error.code == UseSenseError.FINALIZATION_TIMEOUT

    /**
     * An error page (storyboard flow 6). Its button starts a clean attempt;
     * leaving delivers the error to the app.
     */
    private fun showErrorPage(error: UseSenseError, beforeUpload: Boolean) {
        if (isFinishing || isDestroyed) return
        abortAttempt()
        pendingTerminalError = error
        callbackGate.recovery()
        Log.w(TAG, "Error page: ${error.code} ${error.serverCode} ${error.message}")
        showPage(OutcomePages.forError(error, beforeUpload), onPrimary = {
            pendingTerminalError = null
            restartCapture()
        })
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
        tintTile(errorIconCircle, errorIcon, CaptureTheme.Tone.ERROR)
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
        cancelColour()
        endCloserVisuals()
        stopScanLoop()
        scanStage = ScanStage.IDLE
        restoreFillLight()
        watchdog.disarm()
        finalizationJob?.cancel()
        finalizationJob = null
        handler.removeCallbacksAndMessages(null)
        challengePresenter?.stop()
        inlineJob?.cancel()
        inlineJob = null
        microAction = null
        flashView?.setBackgroundColor(Color.TRANSPARENT)
        extraCheckPresenter?.endDriven()
        extraCheckPresenter = null
        extraCheck = null
        if (::extraCheckView.isInitialized) extraCheckView.reset()
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
        if (usesSdkCaptureSequence) {
            // "You're offline" / "This check timed out" / "Something went wrong":
            // the error reaches the app when the person leaves, unless "Try again" fixes it.
            showErrorPage(error, beforeUpload = true)
            return
        }
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
                tintTile(resultIconCircle, resultIcon, CaptureTheme.Tone.SUCCESS)
                resultTitle.text = getString(R.string.usesense_result_success_title)
                resultBody.text = getString(R.string.usesense_result_success_body)
                if (actionContext != null) {
                    resultActionBox.visibility = View.VISIBLE
                    resultActionText.text = actionContext?.displayText
                }
            }
            UseSenseResult.DECISION_MANUAL_REVIEW -> {
                resultIcon.setImageResource(R.drawable.usesense_icon_review)
                tintTile(resultIconCircle, resultIcon, CaptureTheme.Tone.WARNING)
                resultTitle.text = getString(R.string.usesense_result_review_title)
                resultBody.text = getString(R.string.usesense_result_review_body)
            }
            else -> {
                resultIcon.setImageResource(R.drawable.usesense_icon_denied)
                tintTile(resultIconCircle, resultIcon, CaptureTheme.Tone.ERROR)
                resultTitle.text = getString(R.string.usesense_result_failed_title)
                resultBody.text = getString(R.string.usesense_result_failed_body_unsuccessful)
            }
        }

        // As iOS ResultView: one primary button (Continue / Done / Got It)
        // that hands the decision to the host, and "Try Again" on a reject.
        // The button is the brand's primary (BRAND-v1: every capture button).
        val isReject = decision != UseSenseResult.DECISION_APPROVE &&
            decision != UseSenseResult.DECISION_MANUAL_REVIEW
        val buttonText = when (decision) {
            UseSenseResult.DECISION_APPROVE -> R.string.usesense_result_button_approve
            UseSenseResult.DECISION_MANUAL_REVIEW -> R.string.usesense_result_button_review
            else -> R.string.usesense_result_button_reject
        }
        resultButton.text = getString(buttonText)
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

    /** A white-screen icon: the tone's glyph on its 16% tile, with the theme's tile radius. */
    private fun tintTile(tile: View, glyph: ImageView, tone: CaptureTheme.Tone) {
        glyph.imageTintList = ColorStateList.valueOf(captureTheme.toneColor(tone))
        tile.background = styler.rounded(captureTheme.toneTile(tone), styler.dp(captureTheme.shape.tileRadiusDp))
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
        val isPage = step == PageStep.PAGE
        if (!isPage) pageShown = null
        pageView.visibility = if (isPage) View.VISIBLE else View.GONE
        if (step != PageStep.FINALIZING) hideActionSheetIfShown()

        if (step != PageStep.FINALIZING) {
            allScreens.forEach { it.visibility = View.GONE }
        } else {
            // Finalizing overlays the capture screen; a retry from the error
            // screen must not leave the error showing on top of it.
            errorScreen.visibility = View.GONE
            resultScreen.visibility = View.GONE
        }

        // Every page-surface screen sits on the theme's page background (and image): no flash between pages.
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
                // Back to align: the bar starts over.
                handler.removeCallbacks(uploadBarTicker)
                uploadBar.reset()
                showUploadBar(0f)
            }
            PageStep.FINALIZING -> {
                // Keep capture screen visible — show dark overlay on camera
                captureScreen.visibility = View.VISIBLE
                uploadOverlay.visibility = View.VISIBLE
                // The face blurs out for privacy behind the sheet; back and help stay (as iOS).
                faceGuideOverlay.visibility = View.GONE
                captureTopBar.visibility = View.VISIBLE
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
            PageStep.PAGE -> Unit
        }

        val insets = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        // Capture runs under the status bar (light icons over the camera); other screens are padded.
        captureFullBleed = isCaptureMode
        currentStep = step
        applyRootBackground()
        (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)?.let(::applySystemBarPadding)
        if (lightStatusBarDefault == null) lightStatusBarDefault = insets.isAppearanceLightStatusBars
        // Status bar icons: light over the camera and dark pages, dark over light pages.
        insets.isAppearanceLightStatusBars = !isCaptureMode && !captureTheme.isDark

        // The camera is bare; page-surface screens carry the brand header and
        // "Powered by Sense" (the storyboard page has its own, inside it).
        val bare = isCaptureMode || isPage
        headerBar.visibility = if (bare || !hasHeader) View.GONE else View.VISIBLE
        headerDivider.visibility = if (bare || !hasHeader) View.GONE else View.VISIBLE
        footerText.visibility = if (bare) View.GONE else View.VISIBLE
        footerDivider.visibility = if (bare) View.GONE else View.VISIBLE

        // Reset capture overlays when leaving capture. A page keeps the
        // camera's overlays as they are underneath (the mic page returns to them).
        if (!isCaptureMode && !isPage) {
            hideCaptureOverlays()
        }
    }

    private fun hideActionSheetIfShown() {
        if (::actionSheet.isInitialized && actionSheet.visibility == View.VISIBLE) hideActionSheet()
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
        captureTopBar.visibility = View.GONE
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
        cancelColour()
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
        /** Inline step-up: the orchestrator's intro, before Flash Reflection starts. */
        private const val INLINE_INTRO_MS = 1_500L
        private const val RMAS_STEPS = 3f

        /** The scan before a movement in the same capture (the old baseline). */
        private const val BEFORE_CHECK_SCAN_MS = 2_000L
        /** The green tick after an extra check, before the upload sheet. */
        private const val EXTRA_CHECK_DONE_MS = 800L
        /** Speak Digits records this much past the read plus any quiet hold. */
        private const val DIGITS_RECORD_SLACK_MS = 4_000L
        /** Space above a page's icon, with and without the brand header above it. */
        private const val PAGE_TOP_DP = 76
        private const val PAGE_TOP_WITH_HEADER_DP = 28

        private const val GUIDANCE_FADE_MS = 200L
        private const val GUIDANCE_GAP_DP = 28f
        private const val GATE_TAG = "UseSenseGate"

        /** Share of the upload sheet's bar that the upload itself fills. */
        private const val UPLOAD_BAR_FRAME_MS = 33L
        private const val SCAN_BLUR_DELAY_MS = 1500L
        /** Longest wait for the final covered-face check before finishing anyway. */
        private const val FINAL_COVER_WAIT_MS = 2500L
        private const val SCAN_BLUR_DP = 18f
        private const val DEBUG_UPLOAD_PROGRESS = 0.64f
        /** Test rig: time for the fake camera's face to settle before a forced screen. */
        private const val DEBUG_SCREEN_DELAY_MS = 1_500L
        private const val DEBUG_PASS_ALIGN_MS = 3_000L

        /** Face mesh points (468 + 10 iris). */
        private const val MAX_LANDMARKS = 478
        /** The scan clock's period (~15 Hz); the view itself animates per frame. */
        private const val SCAN_TICK_MS = 66L
        /** Scan length when the server sends none (it used to be the passive capture's length too). */
        private const val DEFAULT_SCAN_MS = 4500
        private const val MIN_SCAN_MS = 1500L
        /** The passive scan's target length (shared spec: "about 3 s", as on web). */
        private const val TARGET_SCAN_MS = 3_000L
        /** EVIDENCE-v1: banked time kept after the last colour, so its frames land in the segment. */
        private const val COLOUR_TAIL_MS = 150L
        /** Passing scan gate frames kept for the closer step's far eye distance. */
        private const val FAR_EYE_SAMPLES = 5
        /** Checks the closer step's near phase doesn't apply (see onGateFrame). */
        private val CLOSER_IGNORED_CHECKS = setOf(GateCheck.DISTANCE, GateCheck.IN_OVAL, GateCheck.LIGHT, GateCheck.COVERED)
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
