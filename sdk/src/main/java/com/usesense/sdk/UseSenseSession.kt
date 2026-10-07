package com.usesense.sdk

import com.usesense.sdk.finalization.StepUpCapability
import com.usesense.sdk.finalization.StepUpInstruction
import com.usesense.sdk.finalization.StepUpParser
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.usesense.sdk.antispoof.AntiSpoofClassifier
import com.usesense.sdk.api.ApiException
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.*
import com.usesense.sdk.capture.AudioCaptureManager
import com.usesense.sdk.capture.CapturedFrame
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

internal class UseSenseSession(
    private val context: Context,
    private val config: UseSenseConfig,
    private val request: VerificationRequest,
) {
    var onUploadProgress: ((Long, Long) -> Unit)? = null

    private val apiClient = UseSenseApiClient(config).apply {
        // Surface real upload progress. The signals request is the only one
        // carrying megabytes, and on a slow uplink it runs for minutes; without
        // this the host cannot tell slow from stuck. EventType.UPLOAD_PROGRESS
        // existed but nothing ever emitted it.
        onUploadProgress = { sent, total ->
            this@UseSenseSession.onUploadProgress?.invoke(sent, total)
            if (total > 0) {
                UseSense.eventEmitter.emit(
                    EventType.UPLOAD_PROGRESS,
                    mapOf(
                        "bytes_sent" to sent,
                        "bytes_total" to total,
                        "percent" to ((sent.toDouble() / total) * 100).toInt(),
                    ),
                )
            }
        }
    }
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

    private companion object {
        /**
         * Ceiling on waiting for the Play Integrity job in uploadSignals().
         * Deliberately shorter than PlayIntegrityManager.TOKEN_TIMEOUT_MS: by
         * the time capture finishes the job has usually been running for the
         * whole capture already, so anything still outstanding is not about to
         * arrive.
         */
        const val INTEGRITY_JOIN_TIMEOUT_MS = 3_000L

        /**
         * Ceiling on waiting for queued face mesh work in uploadSignals(). Bounded
         * for the same reason as the integrity join: a MediaPipe call that never
         * settles must not strand the upload. Frames fitted by then are used.
         */
        const val MESH_DRAIN_TIMEOUT_MS = 5_000L
    }

    // v4.1: Liveness & PAD components
    internal val faceMeshManager = FaceMeshManager(context)
    internal val threeDMMFitter = OnDevice3DMMFitter()

    // Face mesh pipeline. Each captured frame is run through MediaPipe off the
    // capture thread and fitted, so buildVerificationPackage has per-frame fits
    // whose frameIndex pairs with an uploaded JPEG's hash. Until this was wired
    // the landmarker was never initialised or fed, so every Android upload
    // omitted verification_package and the server scored mesh_absent.
    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val meshMutex = Mutex()
    private val fitterLock = Any()
    private var meshInitJob: Job? = null
    private val meshJobs = Collections.synchronizedList(mutableListOf<Job>())

    @Volatile private var meshInitResult = FaceMeshInitResult.NOT_ATTEMPTED

    @Volatile private var meshClosed = false
    private val meshFramesProcessed = AtomicInteger(0)
    private val meshFramesDetected = AtomicInteger(0)
    private val meshRequested: Boolean
        get() = VerificationPackageSkipReason.meshRequested(
            sessionResponse?.geometricCoherence?.dualPathEnabled,
            sessionResponse?.geometricCoherence?.onDevice3dmmRequired,
        )
    internal val suspicionEngine = SuspicionEngine()
    internal val screenDetectionAnalyzer = ScreenDetectionAnalyzer()
    internal var stepUpEvidence: JSONObject? = null

    /** The step-up round 1's upload asked for, until the host takes it. */
    @Volatile private var pendingStepUp: StepUpInstruction? = null
    private var stepUpRoundStartTime: Date? = null

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

    /**
     * Inject pre-minted session credentials returned by a server-side init
     * step (the Flows /sdk/flow-runs/:id/init-session endpoint produces a
     * CreateSessionResponse). Skips createSession / exchangeToken; the capture
     * pipeline then sees a fully-formed session and starts immediately.
     *
     * Mirrors iOS UseSenseSession.injectHostedSessionData(_:). Kept `internal`
     * because the Flows runner lives in the same module; opening it `public`
     * is unnecessary for that path.
     */
    internal fun injectHostedSessionData(response: CreateSessionResponse) {
        sessionResponse = response
        apiClient.sessionToken = response.sessionToken
        apiClient.nonce = response.nonce
        stateMachine.transition(SessionState.CREATED)
        integrityJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                signalCollector.requestPlayIntegrityToken(response.nonce)
            } catch (_: Exception) {
                // Play Integrity is best-effort; don't block verification.
            }
        }
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

        // Load the landmarker now so it is ready by the time frames arrive.
        startFaceMesh()

        return frameCaptureManager!!
    }

    private fun startFaceMesh() {
        if (!meshRequested || meshInitJob != null) return
        meshInitResult = FaceMeshInitResult.PENDING
        meshInitJob = meshScope.launch {
            meshInitResult = if (faceMeshManager.initialize()) {
                FaceMeshInitResult.SUCCESS
            } else {
                FaceMeshInitResult.FAILED
            }
        }
    }

    private fun enqueueFaceMesh(frame: CapturedFrame) {
        if (meshClosed) return
        meshJobs.add(
            meshScope.launch {
                meshInitJob?.join()
                if (meshInitResult != FaceMeshInitResult.SUCCESS || meshClosed) return@launch
                // One frame at a time: the landmarker is not safe to call concurrently.
                meshMutex.withLock {
                    if (meshClosed) return@withLock
                    val options = BitmapFactory.Options().apply {
                        // Landmarks are normalised, so a half-size decode of a
                        // 1280x720 v4 frame loses no accuracy and halves the work.
                        inSampleSize = if (frame.width >= 1280) 2 else 1
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frame.jpegData, 0, frame.jpegData.size, options)
                        ?: return@withLock
                    try {
                        meshFramesProcessed.incrementAndGet()
                        val mesh = faceMeshManager.processFrame(bitmap, frame.index, frame.timestampMs)
                            ?: return@withLock
                        meshFramesDetected.incrementAndGet()
                        synchronized(fitterLock) {
                            if (!meshClosed) threeDMMFitter.fitFrame(mesh)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            },
        )
    }

    fun createChallengePresenter(): ChallengePresenter? {
        val spec = challengeSpec ?: return null
        challengePresenter = presenterFor(spec)
        return challengePresenter
    }

    private fun presenterFor(spec: ChallengeSpec): ChallengePresenter? =
        when (spec.type) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> FollowDotChallenge(spec)
            ChallengeSpec.TYPE_HEAD_TURN -> HeadTurnChallenge(spec)
            ChallengeSpec.TYPE_SPEAK_PHRASE -> SpeakPhraseChallenge(spec)
            else -> null
        }

    /** The step-up round 1's upload asked for, if any. Consumed once. */
    fun takeStepUp(): StepUpInstruction? {
        val instruction = pendingStepUp
        pendingStepUp = null
        return instruction
    }

    /**
     * Start a server step-up round on the still-bound camera: a fresh frame
     * buffer capped at the round's budget, and a presenter for the requested
     * challenge. The frame callback set in [startCapture] already routes to
     * whichever presenter is current.
     */
    fun beginStepUpRound(instruction: StepUpInstruction): ChallengePresenter? {
        challengePresenter = presenterFor(instruction.challenge)
        stepUpRoundStartTime = Date()
        frameCaptureManager?.startStepUpCapture(instruction.maxFrames)
        frameCaptureManager?.getFrameBuffer()?.setCapturePhase(com.usesense.sdk.capture.CapturePhase.CHALLENGE)
        return challengePresenter
    }

    /** Upload the step-up round (`?round=2`): its frames and challenge response only. */
    suspend fun uploadStepUpRound(): Result<UploadSignalsResponse> {
        frameCaptureManager?.stopCapture()
        val sid = sessionId ?: return Result.failure(ApiException(UseSenseError.invalidConfig("No session ID")))
        val buffer = frameCaptureManager?.getFrameBuffer()
            ?: return Result.failure(ApiException(UseSenseError.captureFailed("No frames captured")))
        val startMs = stepUpRoundStartTime?.time ?: 0L
        val metadata = JSONObject().apply {
            put("session_id", sid)
            put("sdk_version", DeviceSignalCollector.SDK_VERSION)
            put("platform", "android")
            put("source", "sdk")
            put("capture_channel", "android")
            put("step_up_round", 2)
            put("client_capabilities", org.json.JSONArray(StepUpCapability.ALL))
            challengePresenter?.responseBuilder?.build()?.let { put("challenge_response", it) }
            put("frame_hashes", org.json.JSONArray(buffer.frameHashes))
            put("frames_manifest", org.json.JSONArray(buffer.getFrames().map { f ->
                JSONObject()
                    .put("frame_index", f.index)
                    .put("capture_timestamp_ms", startMs + f.timestampMs)
                    .put("resolution_w", f.width)
                    .put("resolution_h", f.height)
            }))
        }
        return uploader.upload(
            sessionId = sid,
            frames = buffer.getJpegDataList(),
            metadataJson = metadata.toString().toByteArray(Charsets.UTF_8),
            round = 2,
        )
    }

    fun startCapture() {
        stateMachine.transition(SessionState.CAPTURING)
        captureStartTime = Date()
        signalCollector.startSensorCollection()
        frameCaptureManager?.startCapture()
        // Default phase until the host UI advances the state machine.
        frameCaptureManager?.getFrameBuffer()?.setCapturePhase(
            com.usesense.sdk.capture.CapturePhase.BASELINE
        )

        // Wire frame events to the challenge presenter and the face mesh pipeline
        startFaceMesh()
        frameCaptureManager?.onFrameCaptured = { frame ->
            challengePresenter?.onFrameCaptured(frame.index, frame.timestampMs)
            if (meshRequested) enqueueFaceMesh(frame)
        }
    }

    /**
     * v4: tag subsequently captured frames with the supplied phase. The host
     * UI calls this at each transition (e.g. BASELINE -> ZOOM -> CHALLENGE)
     * so the server's SfM perspective validator can filter to the zoom subset.
     */
    fun setCapturePhase(phase: com.usesense.sdk.capture.CapturePhase) {
        frameCaptureManager?.getFrameBuffer()?.setCapturePhase(phase)
    }

    /**
     * v4: run the zoom-motion phase. Convenience wrapper that sets the phase
     * to ZOOM, waits for the spec-recommended 1.5s, then restores the phase
     * to CHALLENGE so that subsequent challenge frames are tagged correctly.
     * Host UIs should display the ZoomPromptView during this call.
     */
    suspend fun runZoomPhase(durationMs: Long = 1500L) {
        if (!config.liveSenseV4Enabled) return
        val buffer = frameCaptureManager?.getFrameBuffer() ?: return
        buffer.setCapturePhase(com.usesense.sdk.capture.CapturePhase.ZOOM)
        kotlinx.coroutines.delay(durationMs)
        buffer.setCapturePhase(com.usesense.sdk.capture.CapturePhase.CHALLENGE)
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

        // Give the Play Integrity token a bounded chance to arrive before we
        // collect signals. Bounded, because attestation is best-effort (see the
        // launch site) while the upload is not: an unbounded join here meant a
        // Play services call that never settled wedged the entire verification
        // on the "Finalizing Enrollment" screen, before a single request had
        // been sent. PlayIntegrityManager caps the request itself; this is the
        // backstop for a job that is wedged some other way, so neither layer
        // alone can strand the subject.
        withTimeoutOrNull(INTEGRITY_JOIN_TIMEOUT_MS) { integrityJob?.join() }

        // Let queued face mesh work finish (bounded), then stop fitting so the
        // package is built from a stable set of frames.
        withTimeoutOrNull(MESH_DRAIN_TIMEOUT_MS) {
            meshInitJob?.join()
            synchronized(meshJobs) { meshJobs.toList() }.joinAll()
        }
        meshClosed = true

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

        // Report the real encoded size. This was hardcoded to 640x480, which is
        // wrong for the v4 path (1280x720 capture) and would have the server
        // score those frames against the wrong sharpness ruler.
        val framesManifest = buffer.getFrames().map { frame ->
            FrameManifestEntry(
                frameIndex = frame.index,
                captureTimestampMs = (captureStartTime?.time ?: 0L) + frame.timestampMs,
                resolutionW = frame.width,
                resolutionH = frame.height,
            )
        }

        // v4.1: Build face mesh signals JSON
        val faceMeshSignals = buildFaceMeshSignals()

        // Extract play integrity token from already-collected signals (avoid double collection)
        val playIntegrityToken = channelIntegrity.optString("play_integrity_token", null)

        // v4.1: Build verification package (when the server asks for mesh), or say why not
        val (verificationPackage, verificationPackageSkipReason) = buildVerificationPackage(buffer, playIntegrityToken)

        // v4.1: Suspicion engine snapshot
        val suspicionSnapshot = suspicionEngine.getSnapshot()

        // v4.1: Screen detection signals
        val screenDetection = screenDetectionAnalyzer.toJson()

        // v4.2: On-device antispoof classifier (feature-flagged; server path is authoritative when off).
        val deepClassifierOnDevice = buildDeepClassifierOnDevice(buffer)

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
            deepClassifierOnDevice = deepClassifierOnDevice,
            framePhases = if (config.liveSenseV4Enabled) buffer.framePhases else null,
            zoomMotion = if (config.liveSenseV4Enabled) buildZoomMotionStats(buffer.framePhases) else null,
            verificationPackageSkipReason = verificationPackageSkipReason,
            faceMeshInitResult = if (meshRequested) meshInitResult.value else null,
            faceMeshInitError = faceMeshManager.lastInitError,
        )

        return uploader.upload(
            sessionId = sid,
            frames = buffer.getJpegDataList(),
            metadataJson = metadataJson,
            audioData = audioData,
        ).onSuccess { response -> pendingStepUp = StepUpParser.parse(response.stepUp) }
    }

    /**
     * v4.2: Run the on-device antispoof classifier across the captured frames
     * and emit a deep_classifier_on_device payload. Returns null when the flag
     * is off, the model artifact isn't available, or no frames were scored.
     */
    private fun buildDeepClassifierOnDevice(buffer: FrameBuffer): JSONObject? {
        if (!config.antispoofOnDeviceEnabled) return null

        val meshData = faceMeshManager.frameMeshData
        if (meshData.isEmpty()) return null

        val frames = buffer.getJpegDataList()
        if (frames.isEmpty()) return null

        val classifier = AntiSpoofClassifier.load(
            context = context,
            enabled = true,
            modelVersion = "v1",
        )
        try {
            if (!classifier.isAvailable) return null

            val samples = JSONArray()
            var modelVersion = "v1"

            val bounded = minOf(frames.size, meshData.size)
            for (i in 0 until bounded) {
                val jpegBytes = frames[i]
                val mesh = meshData[i]
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: continue
                try {
                    val w = bitmap.width
                    val h = bitmap.height
                    val left = (mesh.bbox.x * w).toInt().coerceIn(0, w - 1)
                    val top = (mesh.bbox.y * h).toInt().coerceIn(0, h - 1)
                    val right = ((mesh.bbox.x + mesh.bbox.w) * w).toInt().coerceIn(left + 1, w)
                    val bottom = ((mesh.bbox.y + mesh.bbox.h) * h).toInt().coerceIn(top + 1, h)
                    val faceRect = Rect(left, top, right, bottom)

                    val sample = classifier.predict(bitmap, frameIndex = i, faceBounds = faceRect)
                        ?: continue
                    modelVersion = sample.modelVersion
                    samples.put(JSONObject().apply {
                        put("frameIndex", sample.frameIndex)
                        put("spoofProbability", sample.spoofProbability)
                        put("latencyMs", sample.latencyMs)
                        put("modelVersion", sample.modelVersion)
                        put("backbone", sample.backbone)
                    })
                } finally {
                    bitmap.recycle()
                }
            }

            if (samples.length() == 0) return null

            return JSONObject().apply {
                put("modelVersion", modelVersion)
                put("backbone", "efficientnet_b0")
                put("threshold", 0.5)
                put("samples", samples)
            }
        } finally {
            classifier.close()
        }
    }

    /**
     * v4: zoom-motion summary block. Server cross-checks this against the SfM
     * reconstruction's motion_coherence sub-score (PRD section 4.4).
     */
    private fun buildZoomMotionStats(framePhases: List<String>): JSONObject {
        val zoomCount = framePhases.count { it == com.usesense.sdk.capture.CapturePhase.ZOOM.value }
        return JSONObject().apply {
            put("frames_total", framePhases.size)
            put("frames_in_zoom", zoomCount)
            put("expected_duration_ms", 1500)
            put("sdk_version", DeviceSignalCollector.SDK_VERSION)
        }
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

    /** Returns the package, or null plus the verification_package_skip_reason. */
    private fun buildVerificationPackage(
        buffer: FrameBuffer,
        playIntegrityToken: String?,
    ): Pair<JSONObject?, String?> {
        val gcConfig = geometricCoherenceConfig
        synchronized(fitterLock) {
            val skipReason = VerificationPackageSkipReason.resolve(
                hasGeometricCoherenceConfig = gcConfig != null,
                meshRequested = meshRequested,
                initResult = meshInitResult,
                framesProcessed = meshFramesProcessed.get(),
                meshFrames = meshFramesDetected.get(),
                fittedFrames = threeDMMFitter.results.size,
            )
            if (skipReason != null || gcConfig == null) return null to skipReason

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
            ) to null
        }
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
        meshClosed = true
        meshScope.cancel()
        // Close the landmarker only once an in-flight detect has returned.
        CoroutineScope(Dispatchers.Default).launch {
            meshMutex.withLock { faceMeshManager.release() }
        }
        synchronized(fitterLock) { threeDMMFitter.reset() }
        suspicionEngine.reset()
        screenDetectionAnalyzer.reset()
        apiClient.clearSession()
    }
}
