package com.usesense.sdk

import android.content.Context
import com.usesense.sdk.api.ApiException
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.*
import com.usesense.sdk.capture.AudioCaptureManager
import com.usesense.sdk.capture.FrameBuffer
import com.usesense.sdk.capture.FrameCaptureManager
import com.usesense.sdk.challenge.*
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.MultipartUploader
import com.usesense.sdk.internal.SessionState
import com.usesense.sdk.internal.SessionStateMachine
import com.usesense.sdk.liveness.*
import com.usesense.sdk.signals.CaptureConfigInfo
import com.usesense.sdk.signals.DeviceSignalCollector
import com.usesense.sdk.signals.FrameManifestEntry
import com.usesense.sdk.signals.MetadataBuilder
import com.usesense.sdk.signals.ScreenDetectionAnalyzer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

internal class UseSenseSession(
    private val context: Context,
    private val config: UseSenseConfig,
    private val request: VerificationRequest,
) {
    private val apiClient = UseSenseApiClient(config)
    private val stateMachine = SessionStateMachine()
    private val signalCollector = DeviceSignalCollector(context, config.googleCloudProjectNumber)
    private val metadataBuilder = MetadataBuilder()
    private val uploader = MultipartUploader(apiClient)

    private var sessionResponse: CreateSessionResponse? = null
    private var frameCaptureManager: FrameCaptureManager? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var challengePresenter: ChallengePresenter? = null
    private var audioData: ByteArray? = null
    private var integrityJob: Job? = null
    private var captureStartTime: Date? = null
    private var captureEndTime: Date? = null

    // v4.1: Liveness & PAD components
    internal val faceMeshManager = FaceMeshManager(context)
    internal val threeDMMFitter = OnDevice3DMMFitter()
    internal val suspicionEngine = SuspicionEngine()
    internal val screenDetectionAnalyzer = ScreenDetectionAnalyzer()
    internal var stepUpEvidence: JSONObject? = null

    val state: SessionState get() = stateMachine.currentState
    val sessionId: String? get() = sessionResponse?.sessionId
    val policy: SessionPolicy? get() = sessionResponse?.policy
    val uploadConfig: UploadConfig? get() = sessionResponse?.upload
    val challengeSpec: ChallengeSpec? get() = sessionResponse?.policy?.challenge
    val requiresAudio: Boolean get() = sessionResponse?.policy?.requiresAudio == true
    val expiresAt: String? get() = sessionResponse?.expiresAt
    val geometricCoherenceConfig get() = sessionResponse?.geometricCoherence
    val inlineStepUpConfig get() = sessionResponse?.policy?.inlineStepUp

    val capturePhase: CapturePhase get() = stateMachine.capturePhase

    var onStateChanged: ((SessionState) -> Unit)? = null

    init {
        stateMachine.addListener { _, newState -> onStateChanged?.invoke(newState) }
    }

    fun setCapturePhase(phase: CapturePhase) {
        stateMachine.setCapturePhase(phase)
    }

    fun setCaptureInfo(cameraFacing: String, cameraResolution: String) {
        signalCollector.setCaptureInfo(cameraFacing, cameraResolution)
    }

    /**
     * Exchange a client_token for full session credentials (server-side init flow).
     * The integrator's backend calls /v1/sessions/create-token first, then passes
     * the client_token to the SDK, which exchanges it here.
     */
    suspend fun exchangeToken(clientToken: String): Result<CreateSessionResponse> {
        val result = apiClient.exchangeToken(clientToken)
        result.onSuccess { response ->
            sessionResponse = response
            stateMachine.transition(SessionState.CREATED)
            integrityJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    signalCollector.requestPlayIntegrityToken(response.nonce)
                } catch (_: Exception) { }
            }
        }
        result.onFailure {
            stateMachine.transition(SessionState.ERROR)
        }
        return result
    }

    suspend fun createSession(): Result<CreateSessionResponse> {
        val createRequest = CreateSessionRequest(
            sessionType = request.sessionType.value,
            platform = "android",
            identityId = request.identityId,
            externalUserId = request.externalUserId,
            metadata = request.metadata,
        )

        val result = apiClient.createSession(createRequest)
        result.onSuccess { response ->
            sessionResponse = response
            stateMachine.transition(SessionState.CREATED)
            // Request Play Integrity token concurrently — don't block session setup
            integrityJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    signalCollector.requestPlayIntegrityToken(response.nonce)
                } catch (_: Exception) {
                    // Play Integrity is best-effort; don't block verification
                }
            }
        }
        result.onFailure { e ->
            stateMachine.transition(SessionState.ERROR)
        }
        return result
    }

    fun initCapture(): FrameCaptureManager {
        val upload = sessionResponse?.upload
            ?: throw IllegalStateException("Session not created")
        frameCaptureManager = FrameCaptureManager(upload)

        if (requiresAudio) {
            audioCaptureManager = AudioCaptureManager(context, context.cacheDir)
        }

        return frameCaptureManager!!
    }

    fun createChallengePresenter(): ChallengePresenter? {
        val spec = challengeSpec ?: return null
        challengePresenter = when (spec.type) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> FollowDotChallenge(spec)
            ChallengeSpec.TYPE_HEAD_TURN -> HeadTurnChallenge(spec)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> SpeakPhraseChallenge(spec)
            else -> null
        }
        return challengePresenter
    }

    fun startCapture() {
        stateMachine.transition(SessionState.CAPTURING)
        captureStartTime = Date()
        signalCollector.startSensorCollection()
        frameCaptureManager?.startCapture()

        // Wire frame events to challenge presenter
        frameCaptureManager?.onFrameCaptured = { frame ->
            challengePresenter?.onFrameCaptured(frame.index, frame.timestampMs)
        }
    }

    fun startAudioRecording() {
        val audioDuration = sessionResponse?.policy?.audioChallenge?.totalDurationMs
            ?: sessionResponse?.policy?.challenge?.totalDurationMs
            ?: 3000
        audioCaptureManager?.startRecording(audioDuration)
    }

    fun stopCapture() {
        captureEndTime = Date()
        frameCaptureManager?.stopCapture()
        signalCollector.stopSensorCollection()
        audioData = audioCaptureManager?.stopRecording()
    }

    suspend fun uploadSignals(): Result<UploadSignalsResponse> {
        stateMachine.transition(SessionState.UPLOADING)

        // Ensure Play Integrity token is ready before collecting signals
        integrityJob?.join()

        val sid = sessionId ?: return Result.failure(
            ApiException(UseSenseError.invalidConfig("No session ID"))
        )
        val buffer = frameCaptureManager?.getFrameBuffer()
            ?: return Result.failure(ApiException(UseSenseError.captureFailed("No frames captured")))

        val challengeResponse = challengePresenter?.responseBuilder?.build()
        val channelIntegrity = signalCollector.collectSignals()
        val deviceTelemetry = signalCollector.collectDeviceTelemetry()

        val frameTimestamps = buffer.timestamps
        val avgInterval = if (frameTimestamps.size > 1) {
            (frameTimestamps.last() - frameTimestamps.first()) / (frameTimestamps.size - 1)
        } else 0L

        val upload = uploadConfig
        val captureConfigInfo = CaptureConfigInfo(
            captureDurationMs = upload?.captureDurationMs ?: 8000,
            targetFps = upload?.targetFps ?: 3,
            maxFrames = upload?.maxFrames ?: 30,
        )

        val framesManifest = buffer.getFrames().map { frame ->
            FrameManifestEntry(
                frameIndex = frame.index,
                captureTimestampMs = (captureStartTime?.time ?: 0L) + frame.timestampMs,
                resolutionW = 640,
                resolutionH = 480,
            )
        }

        // v4.1: Build face mesh signals JSON
        val faceMeshSignals = buildFaceMeshSignals()

        // Extract play integrity token from already-collected signals (avoid double collection)
        val playIntegrityToken = channelIntegrity.optString("play_integrity_token", null)

        // v4.1: Build verification package (if GC dual-path enabled)
        val verificationPackage = buildVerificationPackage(buffer, playIntegrityToken)

        // v4.1: Suspicion engine snapshot
        val suspicionSnapshot = suspicionEngine.getSnapshot()

        // v4.1: Screen detection signals
        val screenDetection = screenDetectionAnalyzer.toJson()

        val metadataJson = metadataBuilder.build(
            sessionId = sid,
            source = "sdk",
            challengeResponse = challengeResponse,
            channelIntegrity = channelIntegrity,
            deviceTelemetry = deviceTelemetry,
            captureStartTime = captureStartTime ?: Date(),
            captureEndTime = captureEndTime ?: Date(),
            captureConfig = captureConfigInfo,
            framesManifest = framesManifest,
            framesCaptured = buffer.frameCount,
            framesDropped = 0,
            avgFrameIntervalMs = avgInterval,
            frameTimestamps = frameTimestamps,
            frameHashes = buffer.frameHashes,
            faceMeshSignals = faceMeshSignals,
            verificationPackage = verificationPackage,
            suspicion = suspicionSnapshot,
            suspicionTriggered = suspicionEngine.triggered,
            inlineStepUp = stepUpEvidence,
            screenDetection = screenDetection,
        )

        return uploader.upload(
            sessionId = sid,
            frames = buffer.getJpegDataList(),
            metadataJson = metadataJson,
            audioData = audioData,
        )
    }

    private fun buildFaceMeshSignals(): JSONObject? {
        val meshData = faceMeshManager.frameMeshData
        if (meshData.isEmpty()) return null

        return JSONObject().apply {
            // Stable identifier for the bundled MediaPipe FaceLandmarker model,
            // sourced from MediaPipeModelInfo which is regenerated on every model
            // bump by the mediapipe-sdk-sync workflow in qudusadeyemi/usesense-watchtower.
            // The backend stamps these onto session.mesh_integrity so the mesh
            // integrity card can compare model versions across iOS, Android, and
            // web SDK uploads. Mirrors the iOS UseSenseSession.swift payload.
            put("model", MediaPipeModelInfo.VERSION_LABEL)
            put("model_sha256", MediaPipeModelInfo.SHA256)
            put("model_source", "bundled")
            put("frame_count", meshData.size)
            val framesArray = JSONArray()
            for (data in meshData) {
                framesArray.put(JSONObject().apply {
                    put("frame_index", data.frameIndex)
                    put("timestamp_ms", data.timestampMs)
                    put("headPose", JSONObject().apply {
                        put("yaw", data.headPose.yaw)
                        put("pitch", data.headPose.pitch)
                        put("roll", data.headPose.roll)
                    })
                    put("leftEAR", data.leftEAR)
                    put("rightEAR", data.rightEAR)
                    put("bbox", JSONObject().apply {
                        put("x", data.bbox.x)
                        put("y", data.bbox.y)
                        put("w", data.bbox.w)
                        put("h", data.bbox.h)
                    })
                })
            }
            put("frames", framesArray)
        }
    }

    private fun buildVerificationPackage(buffer: FrameBuffer, playIntegrityToken: String?): JSONObject? {
        val gcConfig = geometricCoherenceConfig ?: return null
        if (!gcConfig.dualPathEnabled) return null
        if (threeDMMFitter.results.isEmpty()) return null

        val frameHashMap = mutableMapOf<Int, String>()
        for (frame in buffer.getFrames()) {
            frameHashMap[frame.index] = frame.hash
        }

        val builder = VerificationPackageBuilder()
        return builder.build(
            fitter = threeDMMFitter,
            frameHashes = frameHashMap,
            meshBindingChallenge = gcConfig.meshBindingChallenge,
            meshDataList = faceMeshManager.frameMeshData,
            playIntegrityToken = playIntegrityToken,
        )
    }

    suspend fun complete(): Result<UseSenseResult> {
        stateMachine.transition(SessionState.COMPLETING)

        val sid = sessionId ?: return Result.failure(
            ApiException(UseSenseError.invalidConfig("No session ID"))
        )

        val result = apiClient.completeSession(sid)
        return result.map { verdict ->
            stateMachine.transition(SessionState.DONE)
            // Security: redact internal scores, pillar verdicts, reasons,
            // signature, and all analysis details before exposing to host app
            UseSenseResult(
                sessionId = verdict.sessionId,
                sessionType = verdict.sessionType,
                identityId = verdict.identityId,
                decision = verdict.decision,
                timestamp = verdict.timestamp,
            )
        }.onFailure {
            stateMachine.transition(SessionState.ERROR)
        }
    }

    fun isExpired(): Boolean {
        val expires = sessionResponse?.expiresAt ?: return false
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val expiryDate = fmt.parse(expires) ?: return false
            Date().after(expiryDate)
        } catch (_: Exception) {
            false
        }
    }

    fun release() {
        integrityJob?.cancel()
        frameCaptureManager?.release()
        audioCaptureManager?.release()
        signalCollector.release()
        faceMeshManager.release()
        threeDMMFitter.reset()
        suspicionEngine.reset()
        screenDetectionAnalyzer.reset()
        apiClient.clearSession()
    }
}
