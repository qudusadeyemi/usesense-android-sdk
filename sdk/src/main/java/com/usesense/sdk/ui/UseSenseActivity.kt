package com.usesense.sdk.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.SessionState
import kotlinx.coroutines.*

class UseSenseActivity : AppCompatActivity() {

    private lateinit var session: UseSenseSession

    // Views
    private lateinit var cameraPreview: PreviewView
    private lateinit var challengeOverlay: FrameLayout
    private lateinit var dotView: View
    private lateinit var directionText: TextView
    private lateinit var speakPhraseLayout: LinearLayout
    private lateinit var phraseText: TextView
    private lateinit var recordingIndicator: TextView
    private lateinit var challengeProgress: ProgressBar
    private lateinit var instructionsLayout: LinearLayout
    private lateinit var instructionsBody: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var processingLayout: LinearLayout
    private lateinit var processingText: TextView
    private lateinit var resultLayout: LinearLayout
    private lateinit var resultIcon: TextView
    private lateinit var resultText: TextView
    private lateinit var resultDetails: TextView
    private lateinit var doneButton: MaterialButton
    private lateinit var cancelButton: ImageButton

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var challengePresenter: ChallengePresenter? = null
    private var frameCaptureManager: FrameCaptureManager? = null
    private var baselineRunnable: Runnable? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] != false

        if (cameraGranted) {
            onPermissionsGranted()
        } else {
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

        cancelButton.setOnClickListener {
            deliverCancelled()
        }

        doneButton.setOnClickListener {
            finish()
        }

        // Start the flow
        mainScope.launch { beginVerification() }
    }

    private fun bindViews() {
        cameraPreview = findViewById(R.id.cameraPreview)
        challengeOverlay = findViewById(R.id.challengeOverlay)
        dotView = findViewById(R.id.dotView)
        directionText = findViewById(R.id.directionText)
        speakPhraseLayout = findViewById(R.id.speakPhraseLayout)
        phraseText = findViewById(R.id.phraseText)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        challengeProgress = findViewById(R.id.challengeProgress)
        instructionsLayout = findViewById(R.id.instructionsLayout)
        instructionsBody = findViewById(R.id.instructionsBody)
        startButton = findViewById(R.id.startButton)
        processingLayout = findViewById(R.id.processingLayout)
        processingText = findViewById(R.id.processingText)
        resultLayout = findViewById(R.id.resultLayout)
        resultIcon = findViewById(R.id.resultIcon)
        resultText = findViewById(R.id.resultText)
        resultDetails = findViewById(R.id.resultDetails)
        doneButton = findViewById(R.id.doneButton)
        cancelButton = findViewById(R.id.cancelButton)
    }

    private suspend fun beginVerification() {
        // 1. Create session
        showProcessing(getString(R.string.usesense_analyzing))
        val createResult = session.createSession()
        createResult.onFailure { e ->
            deliverError(
                (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                    ?: UseSenseError.networkError(e.message)
            )
            return
        }

        // 2. Check permissions
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
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        // Show instructions
        showInstructions()
    }

    private fun showInstructions() {
        hideAll()
        instructionsLayout.visibility = View.VISIBLE

        val instructionText = when (session.challengeSpec?.type) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> getString(R.string.usesense_instruction_follow_dot)
            ChallengeSpec.TYPE_HEAD_TURN -> getString(R.string.usesense_instruction_head_turn)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> getString(R.string.usesense_instruction_speak_phrase)
            else -> getString(R.string.usesense_instruction_default)
        }
        instructionsBody.text = instructionText

        startButton.setOnClickListener {
            startCameraAndCapture()
        }
    }

    private fun startCameraAndCapture() {
        hideAll()
        cameraPreview.visibility = View.VISIBLE
        challengeOverlay.visibility = View.VISIBLE

        frameCaptureManager = session.initCapture()
        challengePresenter = session.createChallengePresenter()
        setupChallengeUI()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = cameraPreview.surfaceProvider }

            // Mirror preview for user (selfie feel), but frames are raw
            cameraPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            cameraPreview.scaleX = -1f // Mirror the preview display

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(
                    ContextCompat.getMainExecutor(this),
                    frameCaptureManager!!.createAnalyzer()
                )}

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                // Start baseline phase (2000ms), then challenge
                session.startCapture()
                if (session.requiresAudio) {
                    session.startAudioRecording()
                }
                startBaselinePhase()
            } catch (e: Exception) {
                deliverError(UseSenseError.cameraUnavailable())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startBaselinePhase() {
        val baselineDurationMs = 2000L
        baselineRunnable = Runnable { startChallengePhase() }
        handler.postDelayed(baselineRunnable!!, baselineDurationMs)
    }

    private fun startChallengePhase() {
        val presenter = challengePresenter
        if (presenter == null) {
            // No challenge, just capture for the remaining duration
            val remaining = (session.uploadConfig?.captureDurationMs ?: 4500) - 2000L
            handler.postDelayed({ onCaptureComplete() }, remaining.coerceAtLeast(1000))
            return
        }

        presenter.onChallengeComplete = { onCaptureComplete() }

        // Configure challenge-specific UI
        when (presenter) {
            is FollowDotChallenge -> {
                dotView.visibility = View.VISIBLE
                presenter.onDotPositionChanged = { x, y, animate -> moveDot(x, y, animate) }
            }
            is HeadTurnChallenge -> {
                directionText.visibility = View.VISIBLE
                presenter.onDirectionChanged = { dir, _ -> showDirection(dir) }
            }
            is SpeakPhraseChallenge -> {
                speakPhraseLayout.visibility = View.VISIBLE
                presenter.onPhraseDisplay = { phrase, isRecording ->
                    phraseText.text = "\"$phrase\""
                    recordingIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
                }
            }
        }

        // Start progress animation
        val totalDuration = presenter.spec.totalDurationMs
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
        val text = when (direction) {
            "left" -> getString(R.string.usesense_turn_left)
            "right" -> getString(R.string.usesense_turn_right)
            "up" -> getString(R.string.usesense_turn_up)
            "down" -> getString(R.string.usesense_turn_down)
            "center" -> getString(R.string.usesense_turn_center)
            else -> direction
        }
        directionText.text = text
    }

    private fun onCaptureComplete() {
        session.stopCapture()

        mainScope.launch {
            // Upload signals
            showProcessing(getString(R.string.usesense_uploading))
            val uploadResult = session.uploadSignals()
            uploadResult.onFailure { e ->
                deliverError(
                    (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.uploadFailed()
                )
                return@launch
            }

            // Complete session and get verdict
            showProcessing(getString(R.string.usesense_analyzing))
            val verdictResult = session.complete()
            verdictResult.onSuccess { result ->
                showResult(result)
                deliverSuccess(result)
            }
            verdictResult.onFailure { e ->
                deliverError(
                    (e as? com.usesense.sdk.api.ApiException)?.useSenseError
                        ?: UseSenseError.networkError(e.message)
                )
            }
        }
    }

    private fun showProcessing(text: String) {
        hideAll()
        processingLayout.visibility = View.VISIBLE
        processingText.text = text
    }

    private fun showResult(result: UseSenseResult) {
        hideAll()
        resultLayout.visibility = View.VISIBLE

        when (result.decision) {
            UseSenseResult.DECISION_APPROVE -> {
                resultIcon.text = "\u2713"
                resultIcon.setTextColor(ContextCompat.getColor(this, R.color.usesense_success))
                resultText.text = getString(R.string.usesense_success)
                resultDetails.text = "Scores: Channel ${result.channelTrustScore}, " +
                    "Liveness ${result.livenessScore}, Dedupe ${result.dedupeRiskScore}"
            }
            UseSenseResult.DECISION_REJECT -> {
                resultIcon.text = "\u2717"
                resultIcon.setTextColor(ContextCompat.getColor(this, R.color.usesense_error))
                resultText.text = getString(R.string.usesense_rejected)
                resultDetails.text = result.reasons.joinToString("\n")
            }
            UseSenseResult.DECISION_MANUAL_REVIEW -> {
                resultIcon.text = "\u231B"
                resultIcon.setTextColor(ContextCompat.getColor(this, R.color.usesense_warning))
                resultText.text = getString(R.string.usesense_manual_review)
                resultDetails.text = "Your verification is being reviewed."
            }
            else -> {
                resultIcon.text = "!"
                resultIcon.setTextColor(ContextCompat.getColor(this, R.color.usesense_error))
                resultText.text = getString(R.string.usesense_error_generic)
            }
        }
    }

    private fun hideAll() {
        cameraPreview.visibility = View.GONE
        challengeOverlay.visibility = View.GONE
        instructionsLayout.visibility = View.GONE
        processingLayout.visibility = View.GONE
        resultLayout.visibility = View.GONE
        dotView.visibility = View.GONE
        directionText.visibility = View.GONE
        speakPhraseLayout.visibility = View.GONE
    }

    private fun onSessionStateChanged(state: SessionState) {
        // Can be used for logging or additional UI updates
    }

    private fun deliverSuccess(result: UseSenseResult) {
        pendingCallback?.onSuccess(result)
    }

    private fun deliverError(error: UseSenseError) {
        pendingCallback?.onError(error)
        if (!isFinishing) {
            // Show error on result screen
            hideAll()
            resultLayout.visibility = View.VISIBLE
            resultIcon.text = "!"
            resultIcon.setTextColor(ContextCompat.getColor(this, R.color.usesense_error))
            resultText.text = getString(R.string.usesense_error_generic)
            resultDetails.text = error.message
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
        session.release()
        pendingCallback = null
        pendingConfig = null
        pendingRequest = null
    }

    companion object {
        internal var pendingCallback: UseSenseCallback? = null
        internal var pendingConfig: UseSenseConfig? = null
        internal var pendingRequest: VerificationRequest? = null

        fun launch(
            context: Context,
            config: UseSenseConfig,
            request: VerificationRequest,
            callback: UseSenseCallback,
        ) {
            pendingConfig = config
            pendingRequest = request
            pendingCallback = callback
            val intent = Intent(context, UseSenseActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
