package com.usesense.sdk.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.usesense.sdk.*
import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.capture.FrameCaptureManager
import com.usesense.sdk.capture.ImageQualityAnalyzer
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.SessionState
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import com.usesense.sdk.R

class UseSenseActivity : AppCompatActivity() {

    private lateinit var session: UseSenseSession

    // Screen containers
    private lateinit var introScreen: LinearLayout
    private lateinit var permissionScreen: LinearLayout
    private lateinit var captureScreen: LinearLayout
    private lateinit var uploadingScreen: LinearLayout
    private lateinit var successScreen: LinearLayout
    private lateinit var deniedScreen: LinearLayout
    private lateinit var failureScreen: LinearLayout
    private lateinit var blockedScreen: LinearLayout

    // Capture screen views
    private lateinit var cameraPreview: PreviewView
    private lateinit var videoContainer: FrameLayout
    private lateinit var challengeOverlay: FrameLayout
    private lateinit var baselineOvalView: View
    private lateinit var dotView: View
    private lateinit var directionCircle: FrameLayout
    private lateinit var directionArrow: TextView
    private lateinit var directionText: TextView
    private lateinit var speakPhraseLayout: LinearLayout
    private lateinit var phraseText: TextView
    private lateinit var recordingIndicator: TextView
    private lateinit var challengeProgress: ProgressBar
    private lateinit var phaseBadge: TextView
    private lateinit var captureTitle: TextView
    private lateinit var captureSubtitle: TextView
    private lateinit var qualityIndicator: QualityIndicatorView
    private lateinit var qualityBanner: QualityWarningBanner

    // Instructions modal overlay (inside video container)
    private lateinit var instructionsOverlay: FrameLayout
    private lateinit var instructionsIcon: TextView
    private lateinit var instructionsTitle: TextView
    private lateinit var instructionsBody: TextView
    private lateinit var instructionsCta: TextView

    // Face guide overlay
    private lateinit var faceGuideOverlay: FrameLayout
    private lateinit var faceGuideReadyButton: MaterialButton

    // Countdown overlay
    private lateinit var countdownOverlay: FrameLayout
    private lateinit var countdownCircle: FrameLayout
    private lateinit var countdownNumber: TextView

    // Permission screen views
    private lateinit var permissionIcon: ImageView
    private lateinit var permissionTitle: TextView
    private lateinit var permissionMessage: TextView
    private lateinit var permissionButton: MaterialButton

    // Success screen views
    private lateinit var successIcon: ImageView
    private lateinit var successTitle: TextView
    private lateinit var successSubtitle: TextView
    private lateinit var successButton: MaterialButton

    // Failure screen views
    private lateinit var failureMessage: TextView
    private lateinit var failureRetryButton: MaterialButton

    // Blocked screen views
    private lateinit var blockedRefreshButton: MaterialButton

    // Denied screen views
    private lateinit var deniedButton: MaterialButton

    private lateinit var cancelButton: ImageButton

    // Quality analysis
    private val qualityAnalyzer = ImageQualityAnalyzer()
    private var lastQualityAnalysisMs = 0L
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var challengePresenter: ChallengePresenter? = null
    private var frameCaptureManager: FrameCaptureManager? = null

    private val eventEmitter get() = UseSense.eventEmitter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true

        if (cameraGranted) {
            eventEmitter.emit(EventType.PERMISSIONS_GRANTED, mapOf("camera" to true))
            onPermissionsGranted()
        } else {
            eventEmitter.emit(EventType.PERMISSIONS_DENIED, mapOf("camera" to false))
            deliverError(UseSenseError.cameraPermissionDenied())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usesense)
        bindViews()

        val config = pendingConfig ?: run {
            deliverError(UseSenseError.invalidConfig("SDK not initialized"))
            return
        }
        val request = pendingRequest ?: run {
            deliverError(UseSenseError.invalidConfig("No verification request"))
            return
        }

        session = UseSenseSession(this, config, request)
        session.onStateChanged = { state -> onSessionStateChanged(state) }

        cancelButton.setOnClickListener { deliverCancelled() }

        mainScope.launch { beginVerification() }
    }

    private fun bindViews() {
        // Screen containers
        introScreen = findViewById(R.id.introScreen)
        permissionScreen = findViewById(R.id.permissionScreen)
        captureScreen = findViewById(R.id.captureScreen)
        uploadingScreen = findViewById(R.id.uploadingScreen)
        successScreen = findViewById(R.id.successScreen)
        deniedScreen = findViewById(R.id.deniedScreen)
        failureScreen = findViewById(R.id.failureScreen)
        blockedScreen = findViewById(R.id.blockedScreen)

        // Capture screen
        cameraPreview = findViewById(R.id.cameraPreview)
        videoContainer = findViewById(R.id.videoContainer)
        challengeOverlay = findViewById(R.id.challengeOverlay)
        baselineOvalView = findViewById(R.id.baselineOvalView)
        dotView = findViewById(R.id.dotView)
        directionCircle = findViewById(R.id.directionCircle)
        directionArrow = findViewById(R.id.directionArrow)
        directionText = findViewById(R.id.directionText)
        speakPhraseLayout = findViewById(R.id.speakPhraseLayout)
        phraseText = findViewById(R.id.phraseText)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        challengeProgress = findViewById(R.id.challengeProgress)
        phaseBadge = findViewById(R.id.phaseBadge)
        captureTitle = findViewById(R.id.captureTitle)
        captureSubtitle = findViewById(R.id.captureSubtitle)
        qualityIndicator = findViewById(R.id.qualityIndicator)
        qualityBanner = findViewById(R.id.qualityBanner)

        // Instructions modal
        instructionsOverlay = findViewById(R.id.instructionsOverlay)
        instructionsIcon = findViewById(R.id.instructionsIcon)
        instructionsTitle = findViewById(R.id.instructionsTitle)
        instructionsBody = findViewById(R.id.instructionsBody)
        instructionsCta = findViewById(R.id.instructionsCta)

        // Face guide
        faceGuideOverlay = findViewById(R.id.faceGuideOverlay)
        faceGuideReadyButton = findViewById(R.id.faceGuideReadyButton)

        // Countdown
        countdownOverlay = findViewById(R.id.countdownOverlay)
        countdownCircle = findViewById(R.id.countdownCircle)
        countdownNumber = findViewById(R.id.countdownNumber)

        // Permission screen
        permissionIcon = findViewById(R.id.permissionIcon)
        permissionTitle = findViewById(R.id.permissionTitle)
        permissionMessage = findViewById(R.id.permissionMessage)
        permissionButton = findViewById(R.id.permissionButton)

        // Success screen
        successIcon = findViewById(R.id.successIcon)
        successTitle = findViewById(R.id.successTitle)
        successSubtitle = findViewById(R.id.successSubtitle)
        successButton = findViewById(R.id.successButton)

        // Failure screen
        failureMessage = findViewById(R.id.failureMessage)
        failureRetryButton = findViewById(R.id.failureRetryButton)

        // Blocked screen
        blockedRefreshButton = findViewById(R.id.blockedRefreshButton)

        // Denied screen
        deniedButton = findViewById(R.id.deniedButton)

        cancelButton = findViewById(R.id.cancelButton)

        // Button listeners for outcome screens
        successButton.setOnClickListener { finish() }
        deniedButton.setOnClickListener { finish() }
        blockedRefreshButton.setOnClickListener {
            mainScope.launch { beginVerification() }
        }
        failureRetryButton.setOnClickListener {
            mainScope.launch { beginVerification() }
        }
    }

    private suspend fun beginVerification() {
        showScreen(introScreen)
        val createResult = session.createSession()
        createResult.onFailure { e ->
            eventEmitter.emit(EventType.ERROR, mapOf("error" to (e.message ?: "")))
            val error = (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                ?: UseSenseError.networkError(e.message)
            deliverError(error)
            return
        }

        eventEmitter.emit(EventType.SESSION_CREATED, mapOf("session_id" to (session.sessionId ?: "")))
        eventEmitter.emit(EventType.PERMISSIONS_REQUESTED)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permsNeeded = mutableListOf(Manifest.permission.CAMERA)
        if (session.requiresAudio) {
            permsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        val notGranted = permsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            eventEmitter.emit(EventType.PERMISSIONS_GRANTED, mapOf("camera" to true))
            onPermissionsGranted()
        } else {
            // Show permission screen before requesting
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

        showScreen(permissionScreen)

        permissionButton.setOnClickListener {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        showInstructions()
    }

    private fun showInstructions() {
        // Show capture screen first (camera will start behind the modal)
        showScreen(captureScreen)

        val challengeType = session.challengeSpec?.type

        // Set instruction icon
        val icon = when (challengeType) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> "\uD83D\uDC41" // eye
            ChallengeSpec.TYPE_HEAD_TURN -> "\u21C4" // arrows
            ChallengeSpec.TYPE_SPEAK_PHRASE -> "\uD83C\uDFA4" // microphone
            else -> "\uD83D\uDC64" // person
        }
        instructionsIcon.text = icon

        // Set title
        val title = when (challengeType) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> getString(R.string.usesense_instructions_title_follow_dot)
            ChallengeSpec.TYPE_HEAD_TURN -> getString(R.string.usesense_instructions_title_head_turn)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> getString(R.string.usesense_instructions_title_speak_phrase)
            else -> getString(R.string.usesense_verifying)
        }
        instructionsTitle.text = title

        // Set body
        val body = when (challengeType) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> getString(R.string.usesense_instruction_follow_dot)
            ChallengeSpec.TYPE_HEAD_TURN -> getString(R.string.usesense_instruction_head_turn)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> getString(R.string.usesense_instruction_speak_phrase)
            else -> getString(R.string.usesense_instruction_default)
        }
        instructionsBody.text = body

        // Show overlay
        instructionsOverlay.visibility = View.VISIBLE
        captureTitle.text = getString(R.string.usesense_getting_ready)
        captureSubtitle.text = ""

        instructionsCta.setOnClickListener {
            instructionsOverlay.visibility = View.GONE
            startCameraAndCapture()
        }
    }

    private fun startCameraAndCapture() {
        frameCaptureManager = session.initCapture()
        challengePresenter = session.createChallengePresenter()

        captureTitle.text = getString(R.string.usesense_position_face)
        captureSubtitle.text = getString(R.string.usesense_center_face)

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }

            cameraPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        frameCaptureManager?.createAnalyzer()?.analyze(imageProxy)

                        // Quality analysis at 4Hz (every 250ms)
                        val now = System.currentTimeMillis()
                        val phase = session.capturePhase
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
                            } catch (_: Exception) {
                                // Quality analysis is best-effort
                            }
                        }

                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                val requiresStepup = session.policy?.requiresStepup == true

                if (requiresStepup) {
                    showFaceGuide()
                } else {
                    session.startCapture()
                    eventEmitter.emit(EventType.CAPTURE_STARTED)
                    if (session.requiresAudio) {
                        session.startAudioRecording()
                        eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
                    }
                    startBaselinePhase()
                }
            } catch (e: Exception) {
                deliverError(UseSenseError.cameraUnavailable())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateQualityUI(report: ImageQualityAnalyzer.ImageQualityReport) {
        qualityIndicator.updateQuality(report)
        qualityBanner.updateQuality(report)

        // Show quality views when we have data
        qualityIndicator.visibility = View.VISIBLE
        qualityBanner.visibility = if (report.isAcceptable) View.GONE else View.VISIBLE

        // Update video container border glow (Section 6.7)
        updateQualityGlow(report)
    }

    private fun updateQualityGlow(report: ImageQualityAnalyzer.ImageQualityReport) {
        val bg = videoContainer.background
        if (bg is GradientDrawable) {
            val dp = resources.displayMetrics.density
            val strokeWidth = (3 * dp).toInt()

            val borderColor = when {
                report.overallScore >= 65 -> Color.TRANSPARENT
                report.overallScore >= 40 -> Color.parseColor("#80A78BFA") // indigo guidance
                else -> Color.parseColor("#997C3AED") // deep indigo
            }
            bg.setStroke(if (borderColor == Color.TRANSPARENT) 0 else strokeWidth, borderColor)
        }
    }

    private fun showFaceGuide() {
        session.setCapturePhase(CapturePhase.FACE_GUIDE)
        faceGuideOverlay.visibility = View.VISIBLE

        captureTitle.text = getString(R.string.usesense_position_face)
        captureSubtitle.text = getString(R.string.usesense_center_face)

        faceGuideReadyButton.setOnClickListener {
            faceGuideOverlay.visibility = View.GONE

            session.startCapture()
            eventEmitter.emit(EventType.CAPTURE_STARTED)
            if (session.requiresAudio) {
                session.startAudioRecording()
                eventEmitter.emit(EventType.AUDIO_RECORD_STARTED)
            }
            startBaselinePhase()
        }
    }

    private fun startBaselinePhase() {
        session.setCapturePhase(CapturePhase.BASELINE)
        challengeOverlay.visibility = View.VISIBLE

        captureTitle.text = getString(R.string.usesense_hold_still)
        captureSubtitle.text = getString(R.string.usesense_keep_still)

        val requiresStepup = session.policy?.requiresStepup == true
        if (requiresStepup) {
            baselineOvalView.visibility = View.VISIBLE
            showPhaseBadge("BASELINE")
        }

        handler.postDelayed({
            baselineOvalView.visibility = View.GONE

            if (requiresStepup) {
                startCountdownPhase()
            } else {
                startChallengePhase()
            }
        }, BASELINE_MS)
    }

    private fun startCountdownPhase() {
        session.setCapturePhase(CapturePhase.COUNTDOWN)
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

        val scaleUpX = ObjectAnimator.ofFloat(countdownCircle, "scaleX", 0.3f, 1.15f).apply {
            duration = 360
            interpolator = OvershootInterpolator(1.5f)
        }
        val scaleUpY = ObjectAnimator.ofFloat(countdownCircle, "scaleY", 0.3f, 1.15f).apply {
            duration = 360
            interpolator = OvershootInterpolator(1.5f)
        }
        val fadeIn = ObjectAnimator.ofFloat(countdownCircle, "alpha", 0f, 1f).apply {
            duration = 360
        }
        val settleX = ObjectAnimator.ofFloat(countdownCircle, "scaleX", 1.15f, 1.0f).apply {
            duration = 540
            startDelay = 360
        }
        val settleY = ObjectAnimator.ofFloat(countdownCircle, "scaleY", 1.15f, 1.0f).apply {
            duration = 540
            startDelay = 360
        }

        AnimatorSet().apply {
            playTogether(scaleUpX, scaleUpY, fadeIn, settleX, settleY)
            start()
        }
    }

    private fun startChallengePhase() {
        session.setCapturePhase(CapturePhase.CHALLENGE)
        eventEmitter.emit(EventType.CHALLENGE_STARTED, mapOf(
            "type" to (session.challengeSpec?.type ?: "")
        ))

        val presenter = challengePresenter
        if (presenter == null) {
            val remaining = (session.uploadConfig?.captureDurationMs ?: 4500) - BASELINE_MS
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

        // Animate progress bar
        val totalDuration = presenter.spec.totalDurationMs
        challengeProgress.visibility = View.VISIBLE
        challengeProgress.max = 1000
        val progressAnimator = ValueAnimator.ofInt(0, 1000).apply {
            duration = totalDuration.toLong()
            addUpdateListener { challengeProgress.progress = it.animatedValue as Int }
        }
        progressAnimator.start()

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
                .x(targetX)
                .y(targetY)
                .setDuration(400)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .start()
        } else {
            dotView.x = targetX
            dotView.y = targetY
        }
    }

    private fun showDirection(direction: String) {
        // Update text label below video
        val text = when (direction) {
            "left" -> getString(R.string.usesense_turn_left)
            "right" -> getString(R.string.usesense_turn_right)
            "up" -> getString(R.string.usesense_turn_up)
            "down" -> getString(R.string.usesense_turn_down)
            "center" -> getString(R.string.usesense_turn_center)
            else -> direction
        }
        directionText.text = text

        // Arrow character (Appendix B)
        val arrow = when (direction) {
            "left" -> "\u2190"
            "right" -> "\u2192"
            "up" -> "\u2191"
            "down" -> "\u2193"
            "center" -> "\u25CB"
            else -> ""
        }
        directionArrow.text = arrow

        // Build gradient background for direction circle (Section 3.5: indigo gradient)
        val isCenter = direction == "center"
        val startColor = if (isCenter) Color.parseColor("#6366F1") else Color.parseColor("#4F46E5")
        val endColor = if (isCenter) Color.parseColor("#8B5CF6") else Color.parseColor("#6366F1")
        val gradientBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            shape = GradientDrawable.OVAL
        }
        directionCircle.background = gradientBg
        directionCircle.visibility = View.VISIBLE

        playDirectionEnterAnimation()
    }

    private fun playDirectionEnterAnimation() {
        directionCircle.scaleX = 0.5f
        directionCircle.scaleY = 0.5f
        directionCircle.alpha = 0f

        val scaleUpX = ObjectAnimator.ofFloat(directionCircle, "scaleX", 0.5f, 1.1f).apply {
            duration = 210
            interpolator = OvershootInterpolator(1.5f)
        }
        val scaleUpY = ObjectAnimator.ofFloat(directionCircle, "scaleY", 0.5f, 1.1f).apply {
            duration = 210
            interpolator = OvershootInterpolator(1.5f)
        }
        val fadeIn = ObjectAnimator.ofFloat(directionCircle, "alpha", 0f, 1f).apply {
            duration = 210
        }
        val settleX = ObjectAnimator.ofFloat(directionCircle, "scaleX", 1.1f, 1.0f).apply {
            duration = 140
            startDelay = 210
        }
        val settleY = ObjectAnimator.ofFloat(directionCircle, "scaleY", 1.1f, 1.0f).apply {
            duration = 140
            startDelay = 210
        }

        AnimatorSet().apply {
            playTogether(scaleUpX, scaleUpY, fadeIn, settleX, settleY)
            start()
        }
    }

    private fun showPhaseBadge(label: String) {
        phaseBadge.text = label
        phaseBadge.visibility = View.VISIBLE
    }

    private fun onCaptureComplete() {
        session.setCapturePhase(CapturePhase.DONE)
        session.stopCapture()
        eventEmitter.emit(EventType.CAPTURE_COMPLETED)
        eventEmitter.emit(EventType.CHALLENGE_COMPLETED)

        mainScope.launch {
            showScreen(uploadingScreen)
            eventEmitter.emit(EventType.UPLOAD_STARTED)

            val uploadResult = session.uploadSignals()
            uploadResult.onFailure { e ->
                eventEmitter.emit(EventType.ERROR, mapOf("phase" to "upload", "error" to (e.message ?: "")))
                deliverError(
                    (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.uploadFailed()
                )
                return@launch
            }
            eventEmitter.emit(EventType.UPLOAD_COMPLETED)

            eventEmitter.emit(EventType.COMPLETE_STARTED)
            val verdictResult = session.complete()
            verdictResult.onSuccess { result ->
                eventEmitter.emit(EventType.DECISION_RECEIVED, mapOf(
                    "decision" to result.decision,
                    "session_id" to result.sessionId,
                ))
                showOutcomeScreen(result)
                deliverSuccess(result)
            }
            verdictResult.onFailure { e ->
                eventEmitter.emit(EventType.ERROR, mapOf("phase" to "complete", "error" to (e.message ?: "")))
                deliverError(
                    (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.networkError(e.message)
                )
            }
        }
    }

    private fun showOutcomeScreen(result: UseSenseResult) {
        when (result.decision) {
            UseSenseResult.DECISION_APPROVE -> {
                successIcon.setImageResource(R.drawable.usesense_icon_success)
                successTitle.text = getString(R.string.usesense_success)
                successSubtitle.text = getString(R.string.usesense_success_detail)
                showScreen(successScreen)
            }
            UseSenseResult.DECISION_MANUAL_REVIEW -> {
                successIcon.setImageResource(R.drawable.usesense_icon_review)
                successTitle.text = getString(R.string.usesense_manual_review)
                successSubtitle.text = getString(R.string.usesense_manual_review_detail)
                showScreen(successScreen)
            }
            UseSenseResult.DECISION_REJECT -> {
                showScreen(deniedScreen)
            }
            else -> {
                failureMessage.text = getString(R.string.usesense_error_generic)
                showScreen(failureScreen)
            }
        }
    }

    private val allScreens by lazy {
        listOf(introScreen, permissionScreen, captureScreen, uploadingScreen,
            successScreen, deniedScreen, failureScreen, blockedScreen)
    }

    private fun showScreen(screen: View) {
        allScreens.forEach { it.visibility = View.GONE }
        screen.visibility = View.VISIBLE

        if (screen != captureScreen) {
            hideCaptureOverlays()
        }
    }

    private fun hideCaptureOverlays() {
        challengeOverlay.visibility = View.GONE
        faceGuideOverlay.visibility = View.GONE
        countdownOverlay.visibility = View.GONE
        instructionsOverlay.visibility = View.GONE
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

    private fun onSessionStateChanged(state: SessionState) {
        // Logged via event emitter
    }

    private fun deliverSuccess(result: UseSenseResult) {
        pendingCallback?.onSuccess(result)
    }

    private fun deliverError(error: UseSenseError) {
        pendingCallback?.onError(error)
        if (!isFinishing) {
            if (error.code == 429 || error.code == UseSenseError.QUOTA_EXCEEDED) {
                showScreen(blockedScreen)
            } else {
                failureMessage.text = error.message
                showScreen(failureScreen)
            }
        }
    }

    private fun deliverCancelled() {
        pendingCallback?.onCancelled()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        session.release()
        pendingCallback = null
        pendingConfig = null
        pendingRequest = null
    }

    companion object {
        private const val BASELINE_MS = 2000L
        private const val COUNTDOWN_MS = 3000L

        internal var pendingCallback: UseSenseCallback? = null
        internal var pendingConfig: UseSenseConfig? = null
        internal var pendingRequest: VerificationRequest? = null

        fun start(
            context: Context,
            config: UseSenseConfig,
            request: VerificationRequest,
            callback: UseSenseCallback,
        ) {
            pendingConfig = config
            pendingRequest = request
            pendingCallback = callback
            val intent = Intent(context, UseSenseActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
