package com.usesense.sdk.v4

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.usesense.sdk.api.V4Phase
import com.usesense.sdk.api.V4VerificationRequest
import com.usesense.sdk.capture.FaceBoundingBox
import com.usesense.sdk.capture.HeadPoseDegrees
import com.usesense.sdk.capture.ZoomFailureReason
import com.usesense.sdk.capture.ZoomMotionController
import com.usesense.sdk.capture.ZoomObservation
import com.usesense.sdk.capture.ZoomState
import com.usesense.sdk.capture.ZoomTransitionListener
import com.usesense.sdk.integrity.HardwareKeyManager
import com.usesense.sdk.integrity.HashChainBuilder
import com.usesense.sdk.integrity.V4ChainSigner
import com.usesense.sdk.internal.FrameEncoder
import com.usesense.sdk.ui.ZoomPromptView
import com.usesense.sdk.upload.VideoEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * End-to-end LiveSense v4 capture activity. Phase 1 ticket A-2.
 *
 * Orchestration:
 *   1. CameraX PreviewView + ImageAnalysis pinned to 1280x720 / 30fps.
 *   2. Camera2Interop locks CONTROL_AE_MODE_OFF and CONTROL_AWB_MODE_OFF
 *      after a 300ms metering window so exposure / white balance are
 *      held constant for the capture. Prevents auto-exposure from
 *      papering over replay-screen flicker.
 *   3. Every analysis frame is run through ML Kit face detection. The
 *      resulting bbox + Euler angles feed ZoomMotionController.
 *   4. Frames are also encoded in parallel through two sinks:
 *         a. JPEG bytes -> HashChainBuilder (chain protocol feed)
 *         b. Bitmap -> VideoEncoder (H.264 MP4 for upload)
 *   5. When ZoomMotionController reports COMPLETE, finish the encoder,
 *      sign the terminal hash with V4ChainSigner, upload through
 *      V4UploadClient, and hand the opaque verdict back to the caller.
 *
 * Failure modes (no_motion, head_turn, timeout, face_lost) propagate
 * through V4Bridge to the caller's V4VerificationCallback.onFailure.
 *
 * Device verification checklist before GA:
 *   - Pixel 7 (StrongBox path, 30fps sustained).
 *   - Samsung A54 (TEE fallback, slower encoder).
 *   - Mid-tier Xiaomi/Redmi (thermal throttling, NV21 preference).
 *   - Samsung foldable (hinge state + camera reconfigure).
 */
class LiveSenseV4Activity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var promptView: ZoomPromptView
    private lateinit var statusText: TextView

    private val motion = ZoomMotionController()
    private lateinit var chain: HashChainBuilder
    private lateinit var keyManager: HardwareKeyManager
    private lateinit var request: V4VerificationRequest

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var encoder: VideoEncoder? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var faceDetector: FaceDetector? = null
    private var exposureLockArmed = false
    private var firstFrameAtMs = 0L
    private var startedEncoderAtMs = 0L

    private var terminated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        request = intent.extractV4Request() ?: return fail(
            IllegalArgumentException("missing v4 request extras")
        )

        chain = HashChainBuilder(request.sessionToken)
        keyManager = HardwareKeyManager(this, sessionId = request.sessionId)

        setContentView(buildRootView())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            // Request CAMERA here so v4 can be launched without host-app
            // permission plumbing. If the user denies we fail with a
            // clear error that the caller can surface.
            permissionLauncher = registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    continueAfterPermissionGranted()
                } else {
                    fail(SecurityException("CAMERA permission denied"))
                }
            }.also { it.launch(Manifest.permission.CAMERA) }
            return
        }
        continueAfterPermissionGranted()
    }

    private var permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    private fun continueAfterPermissionGranted() {

        V4Bridge.dispatchPhase(V4Phase.FRAMING)
        initFaceDetector()
        startCamera()
        motion.addListener(motionListener)
        motion.start()
        startedEncoderAtMs = SystemClock.elapsedRealtime()

        // Trigger the oval enlargement shortly after the user is framed.
        // We do this on the main thread 350ms after the first face detection
        // lands; see handleObservation.
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
        motion.removeListener(motionListener)
        runCatching { encoder?.finish() }
        runCatching { faceDetector?.close() }
        scope.launch {
            // Don't cancel pending uploads; scope keeps them alive if still running.
        }
    }

    // ── Build UI ────────────────────────────────────────────────────────────

    private fun buildRootView(): ViewGroup {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(0xFF1C1A17.toInt())
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        promptView = ZoomPromptView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setOnEnlargeAnimationComplete {
                // When the user sees the larger oval, they start moving closer.
                // The motion controller has been running since start(); nothing
                // extra to do here.
            }
        }
        statusText = TextView(this).apply {
            text = "Fit your face in the oval"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 16)
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(previewView)
        root.addView(promptView)
        root.addView(statusText)
        return root
    }

    private fun initFaceDetector() {
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
        )
    }

    // ── CameraX ─────────────────────────────────────────────────────────────

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()
                .apply { setSurfaceProvider(previewView.surfaceProvider) }

            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            // TODO: device-verify that the Camera2Interop exposure/WB lock
            // sticks on Samsung A54 and Xiaomi Redmi. Some OEMs ignore the
            // advanced constraint unless AE/AWB have been sampled first.
            Camera2Interop.Extender(analysisBuilder).apply {
                // Pin target FPS to 30 and set AWB/AE to auto initially so we
                // sample a baseline. The lock toggles on in onAnalyze after
                // ~300ms of stable frames.
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(30, 30)
                )
            }

            val analysis = analysisBuilder.build().also {
                it.setAnalyzer(analysisExecutor, ::onAnalyze)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
            )

            // Start the video encoder in the activity cache dir.
            encoder = VideoEncoder().apply { start(cacheDir) }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun onAnalyze(image: ImageProxy) {
        if (terminated) {
            image.close(); return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close(); return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (firstFrameAtMs == 0L) firstFrameAtMs = nowMs

        // Fan 1: ML Kit face detection for ZoomMotionController observations.
        val rotation = image.imageInfo.rotationDegrees
        val mlInput = InputImage.fromMediaImage(mediaImage, rotation)
        faceDetector?.process(mlInput)
            ?.addOnSuccessListener { faces ->
                faces.firstOrNull()?.let { face ->
                    val box = face.boundingBox
                    val obs = ZoomObservation(
                        timestampMs = nowMs,
                        bbox = FaceBoundingBox(
                            cx = box.exactCenterX(),
                            cy = box.exactCenterY(),
                            w = box.width().toFloat(),
                            h = box.height().toFloat(),
                        ),
                        headPose = HeadPoseDegrees(
                            yawDeg = face.headEulerAngleY,
                            pitchDeg = face.headEulerAngleX,
                            rollDeg = face.headEulerAngleZ,
                        ),
                    )
                    handleObservation(obs)
                }
            }
            ?.addOnCompleteListener {
                // Independent of success/failure, pass the frame through the
                // encoder and chain builder.
                try {
                    runFrameSinks(image, nowMs)
                } catch (e: Exception) {
                    Log.w(TAG, "frame sinks failed: ${e.message}")
                } finally {
                    image.close()
                }
            }
            ?: run {
                try { runFrameSinks(image, nowMs) } catch (_: Exception) {}
                image.close()
            }

        // Arm the exposure / white balance lock ~300ms after the first
        // steady frame so we sample a baseline AE/AWB first. We rebind
        // the ImageAnalysis UseCase with CONTROL_AE_MODE_OFF and
        // CONTROL_AWB_MODE_OFF so replay-screen flicker cannot ride
        // auto-exposure into the signal.
        if (!exposureLockArmed && nowMs - firstFrameAtMs > 300) {
            exposureLockArmed = true
            runOnUiThread { applyExposureLockAndRebind() }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun applyExposureLockAndRebind() {
        // TODO: device-verify on Samsung A54 and a mid-tier Xiaomi that
        // AE/AWB OFF sticks. Some OEMs require ISO + exposure-time +
        // colour-correction gains to be set explicitly; when available
        // via the analysis frames' CaptureResult we could pin those.
        val provider = cameraProvider ?: return
        val preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }
        val lockedBuilder = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        Camera2Interop.Extender(lockedBuilder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF,
            )
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF,
            )
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(30, 30),
            )
        }
        val locked = lockedBuilder.build().also {
            it.setAnalyzer(analysisExecutor, ::onAnalyze)
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                locked,
            )
        } catch (e: Exception) {
            Log.w(TAG, "rebind with AE/AWB lock failed: ${e.message}")
        }
    }

    private fun runFrameSinks(image: ImageProxy, timestampMs: Long) {
        val bitmap = image.toBitmap().let { raw ->
            val rot = image.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                    if (it !== raw) raw.recycle()
                }
            } else raw
        }

        // JPEG -> chain builder.
        val jpeg = FrameEncoder.bitmapToJpeg(bitmap)
        chain.append(jpeg)

        // Bitmap -> video encoder (scaled to 1280x720).
        val prepared = if (bitmap.width == 1280 && bitmap.height == 720) bitmap
            else Bitmap.createScaledBitmap(bitmap, 1280, 720, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        val ptsUs = (timestampMs - startedEncoderAtMs) * 1000L
        encoder?.appendBitmap(prepared, ptsUs)
        prepared.recycle()
    }

    private fun handleObservation(obs: ZoomObservation) {
        // Trigger the oval enlargement once the face has been stable for ~500ms.
        if (obs.timestampMs - firstFrameAtMs > 500 && promptView.let { true }) {
            runOnUiThread {
                promptView.setState(ZoomPromptView.State.ENLARGED, animated = true)
                statusText.text = "Move phone closer to fill the new oval"
            }
        }
        motion.observe(obs)
    }

    // ── Motion listener ─────────────────────────────────────────────────────

    private val motionListener = ZoomTransitionListener { next, _prev, _stats, failure ->
        if (terminated) return@ZoomTransitionListener
        when (next) {
            ZoomState.MOVING -> V4Bridge.dispatchPhase(V4Phase.ZOOM)
            ZoomState.COMPLETE -> finishAndUpload()
            ZoomState.FAILED -> failWith(failure)
            else -> { /* ignored */ }
        }
    }

    private fun finishAndUpload() {
        if (terminated) return
        terminated = true
        V4Bridge.dispatchPhase(V4Phase.UPLOADING)
        runOnUiThread { statusText.text = "Verifying" }

        scope.launch {
            try {
                val mp4 = withContext(Dispatchers.Default) {
                    encoder?.finish() ?: ByteArray(0)
                }
                val terminal = chain.terminal()
                val terminalHex = chain.terminalHex()
                val signer = V4ChainSigner(keyManager)
                val sig = withContext(Dispatchers.Default) { signer.sign(terminal) }

                V4Bridge.dispatchPhase(V4Phase.COMPLETING)
                val verdict = withContext(Dispatchers.IO) {
                    V4UploadClient.upload(
                        request = request,
                        mp4 = mp4,
                        frameHashes = chain.frameHashes(),
                        terminalHashHex = terminalHex,
                        signatureB64 = sig.signatureB64,
                        publicKeySpkiB64 = sig.publicKeySpkiB64,
                        assuranceLevel = sig.assuranceLevel,
                        stats = motion.stats(),
                    )
                }
                V4Bridge.dispatchPhase(V4Phase.COMPLETED)
                V4Bridge.dispatchSuccess(verdict)
                finish()
            } catch (t: Throwable) {
                Log.e(TAG, "v4 upload/sign failed", t)
                V4Bridge.dispatchFailure(t)
                finish()
            }
        }
    }

    private fun failWith(reason: ZoomFailureReason?) {
        if (terminated) return
        terminated = true
        val msg = when (reason) {
            ZoomFailureReason.TIMEOUT -> "You did not complete the motion in time."
            ZoomFailureReason.NO_MOTION -> "Move the phone closer to fill the new oval."
            ZoomFailureReason.HEAD_TURN -> "Please keep your head facing the camera."
            ZoomFailureReason.FACE_LOST -> "Keep your face in view."
            null -> "Capture failed."
        }
        V4Bridge.dispatchFailure(IllegalStateException("zoom_failed:${reason?.wire ?: "unknown"}:$msg"))
        finish()
    }

    private fun fail(e: Throwable) {
        if (terminated) return
        terminated = true
        V4Bridge.dispatchFailure(e)
        finish()
    }

    companion object {
        private const val TAG = "UseSense.V4Activity"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val EX_SESSION_ID = "com.usesense.v4.sessionId"
        private const val EX_SESSION_TOKEN = "com.usesense.v4.sessionToken"
        private const val EX_NONCE = "com.usesense.v4.nonce"
        private const val EX_API_BASE = "com.usesense.v4.apiBase"
        private const val EX_ENV = "com.usesense.v4.env"
        private const val EX_DISPLAY_NAME = "com.usesense.v4.displayName"
        private const val EX_BRAND_COLOR = "com.usesense.v4.brandColor"

        fun buildIntent(context: Context, request: V4VerificationRequest): Intent {
            return Intent(context, LiveSenseV4Activity::class.java).apply {
                putExtra(EX_SESSION_ID, request.sessionId)
                putExtra(EX_SESSION_TOKEN, request.sessionToken)
                putExtra(EX_NONCE, request.nonce)
                putExtra(EX_API_BASE, request.apiBaseUrl)
                putExtra(EX_ENV, request.environment)
                request.displayName?.let { putExtra(EX_DISPLAY_NAME, it) }
                request.brandPrimaryColor?.let { putExtra(EX_BRAND_COLOR, it) }
            }
        }

        internal fun Intent.extractV4Request(): V4VerificationRequest? {
            val sid = getStringExtra(EX_SESSION_ID) ?: return null
            val token = getStringExtra(EX_SESSION_TOKEN) ?: return null
            val nonce = getStringExtra(EX_NONCE) ?: return null
            val base = getStringExtra(EX_API_BASE) ?: return null
            val env = getStringExtra(EX_ENV) ?: "production"
            val brand = if (hasExtra(EX_BRAND_COLOR)) getIntExtra(EX_BRAND_COLOR, 0) else null
            return V4VerificationRequest(
                sessionId = sid,
                sessionToken = token,
                nonce = nonce,
                apiBaseUrl = base,
                environment = env,
                brandPrimaryColor = brand,
                displayName = getStringExtra(EX_DISPLAY_NAME),
            )
        }
    }
}
