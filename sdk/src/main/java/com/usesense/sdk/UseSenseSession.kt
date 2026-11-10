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
import com.usesense.sdk.evidence.BindingV2Evidence
import com.usesense.sdk.evidence.CloserEvidence
import com.usesense.sdk.evidence.CloserResolver
import com.usesense.sdk.evidence.CloserStepController
import com.usesense.sdk.evidence.ColourEvidence
import com.usesense.sdk.evidence.Evidence
import com.usesense.sdk.evidence.EvidenceCapability
import com.usesense.sdk.evidence.EvidenceConfig
import com.usesense.sdk.evidence.EvidenceDigest
import com.usesense.sdk.evidence.EvidenceUploadBlock
import com.usesense.sdk.internal.Bounded
import com.usesense.sdk.internal.CapturePhase
import com.usesense.sdk.internal.MultipartUploader
import com.usesense.sdk.internal.SessionState
import com.usesense.sdk.internal.SessionStateMachine
import com.usesense.sdk.internal.boundedCall
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

    /** The covered-face check's numbers for metadata.covered_check (set by the capture screen). */
    var coveredCheck: (() -> org.json.JSONObject?)? = null

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

        /**
         * Ceiling on loading the MediaPipe landmarker. createFromOptions is a
         * native call that cannot be interrupted; past this the session goes
         * on without a mesh (the server tolerates a missing mesh package).
         */
        const val MESH_INIT_TIMEOUT_MS = 8_000L

        /**
         * Ceiling on one MediaPipe detect + fit. A frame that takes longer
         * means the landmarker is wedged: stop feeding it and skip the mesh
         * rather than let queued frames pile up behind it.
         */
        const val MESH_FRAME_TIMEOUT_MS = 2_000L

        /** Ceiling on the optional on-device antispoof pass during packaging. */
        const val CLASSIFIER_TIMEOUT_MS = 10_000L

        /** Ceiling on the binding v2 Play Integrity Standard request, after the last frame. */
        const val BINDING_V2_WAIT_MS = 6_000L
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

    /** Set when init or a frame exceeded its ceiling; reported as face_mesh_init_error. */
    @Volatile private var meshTimeoutError: String? = null

    /**
     * A detect that exceeded MESH_FRAME_TIMEOUT_MS and is still running in
     * native code. release() must not close the landmarker under it.
     */
    @Volatile private var stuckMeshWork: Deferred<*>? = null
    /**
     * Bumped when the scan drops its window: mesh work queued for frames of the
     * dropped window must not land in the new one (frame indices restart at 0).
     */
    private val meshGeneration = AtomicInteger(0)
    private val meshFramesProcessed = AtomicInteger(0)
    private val meshFramesDetected = AtomicInteger(0)
    private val meshRequested: Boolean
        get() = VerificationPackageSkipReason.meshRequested(
            sessionResponse?.geometricCoherence?.dualPathEnabled,
            sessionResponse?.geometricCoherence?.onDevice3dmmRequired,
        ) || evidenceConfig?.landmarksRequired == true

    // ─── EVIDENCE-v1 (capture redesign Phase 2) ─────────────────────────

    /** What Phase 2 evidence the server asks for (null: old server, upload as before). */
    val evidenceConfig: EvidenceConfig? get() = sessionResponse?.evidence

    /** The guided scan runs (set by the capture screen); the closer step needs it. */
    @Volatile var guidedScan: Boolean = false

    /** The colour sequence as it played in the kept window (set by the capture screen). */
    @Volatile var colourEvidence: ColourEvidence? = null

    @Volatile private var captureClockStarted = false
    private var closerStartIndex = -1
    private var closerEndIndex = -1
    private var closerStartedAtMs: Long? = null
    private var closerEndedAtMs: Long? = null
    /** Set when the step didn't run its frames (disabled, or no far frames to compare). */
    private var closerSkipReason: String? = null
    private var closerRan = false

    /** The capture clock (ms since the kept window's first frame clock start), or null before capture. */
    fun captureClockMs(elapsedRealtimeMs: Long): Long? =
        frameCaptureManager?.getFrameBuffer()?.takeIf { captureClockStarted }?.clockMs(elapsedRealtimeMs)

    /**
     * Keep [CloserStepController.MAX_FRAMES] of `upload.max_frames` free for
     * the closer step's near frames while the scan banks (section 7: count
     * them within the budget).
     */
    private fun applyCloserReserve() {
        val buffer = frameCaptureManager?.getFrameBuffer() ?: return
        val max = uploadConfig?.maxFrames ?: return
        buffer.roundLimit = if (evidenceConfig?.closerEnabled == true && guidedScan) {
            (max - CloserStepController.MAX_FRAMES).coerceAtLeast(1)
        } else null
    }

    /** Frames banked so far in this window. */
    val bankedFrameCount: Int get() = frameCaptureManager?.getFrameBuffer()?.frameCount ?: 0

    /** Step 5 begins: the next banked frames are near-phase candidates, at most MAX_FRAMES of them. */
    fun beginCloserStep() {
        val buffer = frameCaptureManager?.getFrameBuffer() ?: return
        closerRan = true
        closerSkipReason = null
        closerStartIndex = buffer.frameCount
        closerEndIndex = -1
        closerStartedAtMs = buffer.clockNowMs()
        closerEndedAtMs = null
        val max = uploadConfig?.maxFrames ?: buffer.limit
        buffer.roundLimit = (buffer.frameCount + CloserStepController.MAX_FRAMES).coerceAtMost(max)
    }

    /** Step 5 ends (enough near frames, or out of time); later frames are not near frames. */
    fun endCloserStep() {
        val buffer = frameCaptureManager?.getFrameBuffer() ?: return
        if (closerStartIndex < 0) return
        closerEndIndex = buffer.frameCount
        closerEndedAtMs = buffer.clockNowMs()
        buffer.roundLimit = null
    }

    /** The step didn't run (the server's flag is off, or the scan left nothing to compare). */
    fun skipCloserStep(reason: String) {
        closerRan = true
        closerSkipReason = reason
        frameCaptureManager?.getFrameBuffer()?.roundLimit = null
    }

    /** Whether the closer step may still bank a frame. */
    val closerBudgetLeft: Boolean
        get() = frameCaptureManager?.getFrameBuffer()?.let { it.frameCount < it.limit } ?: false

    /**
     * Near frames confirmed so far by the uploaded frames' own mesh (the
     * resolver's rule), so the step ends when the upload will really have three.
     */
    fun closerNearFrames(): Int {
        val step = evidenceConfig?.closerStep ?: return 0
        if (closerStartIndex < 0) return 0
        val end = frameCaptureManager?.getFrameBuffer()?.frameCount ?: return 0
        val record = CloserResolver.resolve(meshedFrames(), closerStartIndex, end, step.minScale, null, null)
        return record.nearFrames.size
    }

    private fun meshedFrames(): List<CloserResolver.MeshedFrame> = faceMeshManager.frameMeshData.map { m ->
        val aspect = if (m.imageWidth > 0) m.imageHeight.toDouble() / m.imageWidth else 4.0 / 3.0
        CloserResolver.MeshedFrame(m.frameIndex, Evidence.eyeDistance(m.landmarks, aspect))
    }

    private fun resetCloser() {
        closerStartIndex = -1
        closerEndIndex = -1
        closerStartedAtMs = null
        closerEndedAtMs = null
        closerSkipReason = null
        closerRan = false
    }
    /** Fed by the scan's gate frames; its threshold is the policy's inline step-up threshold. */
    internal val suspicionEngine: SuspicionEngine by lazy {
        SuspicionEngine(
            suspicionThreshold = sessionResponse?.policy?.inlineStepUp?.suspicionThreshold ?: 55,
            everyNthFrame = 1,
        )
    }
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

    /** The dashboard's branding delivered with the session (null on older servers). */
    internal val serverBranding: com.usesense.sdk.ServerBranding?
        get() = sessionResponse?.branding?.toServerBranding()
    val requiresAudio: Boolean get() = sessionResponse?.policy?.requiresAudio == true
    val expiresAt: String? get() = sessionResponse?.expiresAt
    val geometricCoherenceConfig get() = sessionResponse?.geometricCoherence
    val inlineStepUpConfig get() = sessionResponse?.policy?.inlineStepUp

    val capturePhase: CapturePhase get() = stateMachine.capturePhase

    /** Frames in the current buffer; 0 means the camera never delivered one. */
    val capturedFrameCount: Int get() = frameCaptureManager?.capturedFrameCount ?: 0

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
            prepareBindingV2(response)
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
        prepareBindingV2(response)
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
            prepareBindingV2(response)
        }
        result.onFailure { e ->
            stateMachine.transition(SessionState.ERROR)
        }
        return result
    }

    /** Warm the Play Integrity Standard provider early: preparing can take seconds. */
    private fun prepareBindingV2(response: CreateSessionResponse) {
        if (response.evidence?.bindingV2Enabled == true) signalCollector.prepareStandardIntegrity(meshScope)
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
            meshInitResult = when (val init = boundedCall(meshScope, MESH_INIT_TIMEOUT_MS) { faceMeshManager.initialize() }) {
                is Bounded.Done -> if (init.value) FaceMeshInitResult.SUCCESS else FaceMeshInitResult.FAILED
                is Bounded.TimedOut -> {
                    // Skip the mesh: the server scores without it.
                    meshTimeoutError = "TimeoutException: face mesh init exceeded ${MESH_INIT_TIMEOUT_MS}ms"
                    meshClosed = true
                    FaceMeshInitResult.FAILED
                }
            }
        }
    }

    private fun enqueueFaceMesh(frame: CapturedFrame) {
        if (meshClosed) return
        val generation = meshGeneration.get()
        meshJobs.add(
            meshScope.launch {
                meshInitJob?.join()
                if (meshInitResult != FaceMeshInitResult.SUCCESS || meshClosed) return@launch
                // One frame at a time: the landmarker is not safe to call concurrently.
                meshMutex.withLock {
                    if (meshClosed || generation != meshGeneration.get()) return@withLock
                    val options = BitmapFactory.Options().apply {
                        // Landmarks are normalised, so a half-size decode of a
                        // 1280x720 v4 frame loses no accuracy and halves the work.
                        inSampleSize = if (frame.width >= 1280) 2 else 1
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frame.jpegData, 0, frame.jpegData.size, options)
                        ?: return@withLock
                    meshFramesProcessed.incrementAndGet()
                    val detect = boundedCall(meshScope, MESH_FRAME_TIMEOUT_MS) {
                        faceMeshManager.processFrame(bitmap, frame.index, frame.timestampMs) {
                            generation == meshGeneration.get()
                        }
                    }
                    when (detect) {
                        is Bounded.TimedOut -> {
                            // The landmarker is wedged. Stop feeding it (skip the
                            // mesh rather than block the session) and only recycle
                            // the bitmap once native code lets go of it.
                            meshClosed = true
                            meshTimeoutError = "TimeoutException: face mesh frame exceeded ${MESH_FRAME_TIMEOUT_MS}ms"
                            stuckMeshWork = detect.work
                            detect.work.invokeOnCompletion { bitmap.recycle() }
                        }
                        is Bounded.Done -> {
                            bitmap.recycle()
                            val mesh = detect.value ?: return@withLock
                            meshFramesDetected.incrementAndGet()
                            synchronized(fitterLock) {
                                if (!meshClosed && generation == meshGeneration.get()) threeDMMFitter.fitFrame(mesh)
                            }
                        }
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
        // Round 2 is scored as round 1 was: a fresh mesh fit for its frames
        // (unless the landmarker wedged in round 1), sensors running again,
        // and its own capture clock for the frames manifest.
        meshGeneration.incrementAndGet()
        faceMeshManager.reset()
        synchronized(fitterLock) { threeDMMFitter.reset() }
        meshFramesProcessed.set(0)
        meshFramesDetected.set(0)
        if (meshTimeoutError == null) meshClosed = false
        captureStartTime = Date()
        captureEndTime = null
        signalCollector.startSensorCollection()
        frameCaptureManager?.startStepUpCapture(instruction.maxFrames)
        frameCaptureManager?.getFrameBuffer()?.setCapturePhase(com.usesense.sdk.capture.CapturePhase.CHALLENGE)
        return challengePresenter
    }

    /**
     * Upload the step-up round (`?round=2`): its frames, its challenge
     * response, and the same signals as round 1 under the same keys.
     */
    suspend fun uploadStepUpRound(): Result<UploadSignalsResponse> = withContext(Dispatchers.Default) {
        frameCaptureManager?.stopCapture()
        captureEndTime = Date()
        signalCollector.stopSensorCollection()
        val sid = sessionId ?: return@withContext Result.failure(ApiException(UseSenseError.invalidConfig("No session ID")))
        val buffer = frameCaptureManager?.getFrameBuffer()
            ?: return@withContext Result.failure(ApiException(UseSenseError.captureFailed("No frames captured")))
        drainFaceMesh()
        val base = packageRound(sid, buffer, challengePresenter?.responseBuilder?.build(), round2 = true)
        val metadata = JSONObject(String(base, Charsets.UTF_8)).apply {
            put("capture_channel", "android")
            put("step_up_round", 2)
        }
        uploader.upload(
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
        captureClockStarted = true
        resetCloser()
        applyCloserReserve()
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

    /** The scan's gate is failing: stop banking frames, keep the ones banked. */
    fun pauseCapture() {
        frameCaptureManager?.pause()
    }

    fun resumeCapture() {
        frameCaptureManager?.resume()
    }

    /**
     * The scan paused too long: drop the window and start banking afresh when
     * the subject is aligned again. Frames, their mesh fits and the capture
     * clock restart; the session stays CAPTURING (sensors keep running), so
     * the upload is exactly what one uninterrupted window would have sent.
     * Banking stays paused until [resumeCapture].
     */
    fun restartCaptureWindow() {
        val fcm = frameCaptureManager ?: return
        meshGeneration.incrementAndGet()
        faceMeshManager.reset()
        synchronized(fitterLock) { threeDMMFitter.reset() }
        meshFramesProcessed.set(0)
        meshFramesDetected.set(0)
        fcm.restartPaused()
        fcm.getFrameBuffer().setCapturePhase(com.usesense.sdk.capture.CapturePhase.BASELINE)
        captureStartTime = Date()
        // The window's frames are gone, so is any closer record (the colour
        // record is reset by the capture screen's player).
        resetCloser()
        applyCloserReserve()
    }

    /**
     * Start the microphone. [durationMs] caps the recording (the challenge's
     * length by default). False when the microphone can't record (no input,
     * in use, permission revoked): the caller offers a head movement instead.
     */
    fun startAudioRecording(durationMs: Int? = null): Boolean {
        val audioDuration = durationMs
            ?: sessionResponse?.policy?.audioChallenge?.totalDurationMs
            ?: sessionResponse?.policy?.challenge?.totalDurationMs
            ?: 3000
        val manager = audioCaptureManager
            ?: AudioCaptureManager(context, context.cacheDir).also { audioCaptureManager = it }
        return manager.startRecording(audioDuration)
    }

    /** The microphone's level since the last call, 0..1 (0 when not recording). */
    fun audioLevel(): Float = audioCaptureManager?.level() ?: 0f

    /** The session's challenge is Speak Digits (it needs the microphone). */
    val challengeNeedsMicrophone: Boolean
        get() = challengeSpec?.type == ChallengeSpec.TYPE_SPEAK_PHRASE

    /**
     * The microphone can't be used: ask the server to swap Speak Digits for
     * Follow Dot (same strength). On success the session's policy is the new
     * one and [challengeSpec] is the Follow Dot; the caller makes a new presenter.
     */
    suspend fun applyMicrophoneFallback(): Result<SessionPolicy> {
        val current = sessionResponse
            ?: return Result.failure(ApiException(UseSenseError.invalidConfig("Session not created")))
        return apiClient.challengeFallback(current.sessionId).onSuccess { response ->
            sessionResponse = current.copy(policy = response.policy)
            audioCaptureManager?.release()
            audioCaptureManager = null
            challengePresenter = null
        }.map { it.policy }
    }

    fun stopCapture() {
        captureEndTime = Date()
        frameCaptureManager?.stopCapture()
        signalCollector.stopSensorCollection()
        audioData = audioCaptureManager?.stopRecording()
    }

    /**
     * Package and upload round 1. Packaging (signal collection, mesh package,
     * the optional classifier, JSON) is CPU work that callers launch from the
     * main thread; it runs on Dispatchers.Default so it can neither ANR nor
     * starve the coroutines (finalization monitor, watchdog) supervising it.
     */
    suspend fun uploadSignals(): Result<UploadSignalsResponse> = withContext(Dispatchers.Default) {
        uploadSignalsInternal()
    }

    private suspend fun uploadSignalsInternal(): Result<UploadSignalsResponse> {
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

        drainFaceMesh()

        val sid = sessionId ?: return Result.failure(
            ApiException(UseSenseError.invalidConfig("No session ID"))
        )
        val buffer = frameCaptureManager?.getFrameBuffer()
            ?: return Result.failure(ApiException(UseSenseError.captureFailed("No frames captured")))

        val metadataJson = packageRound(sid, buffer, challengePresenter?.responseBuilder?.build())

        return uploader.upload(
            sessionId = sid,
            frames = buffer.getJpegDataList(),
            metadataJson = metadataJson,
            audioData = audioData,
        ).onSuccess { response -> pendingStepUp = StepUpParser.parse(response.stepUp) }
    }

    /** Let queued face mesh work finish (bounded), then stop fitting so the package is built from a stable set of frames. */
    private suspend fun drainFaceMesh() {
        withTimeoutOrNull(MESH_DRAIN_TIMEOUT_MS) {
            meshInitJob?.join()
            synchronized(meshJobs) { meshJobs.toList() }.joinAll()
        }
        meshClosed = true
    }

    /**
     * The upload metadata for one round's frames. Round 1 and the step-up
     * round 2 carry the same signals under the same keys (channel integrity,
     * suspicion, the on-device classifier, frame hashes, the mesh
     * verification package), so the server can run the same checks on both.
     */
    private suspend fun packageRound(
        sid: String,
        buffer: FrameBuffer,
        challengeResponse: JSONObject?,
        round2: Boolean = false,
    ): ByteArray {
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

        // EVIDENCE-v1 metadata.evidence (round 1; absent when the server issued
        // no contract, so an old server sees the upload as before).
        val evidenceBlock = evidenceConfig?.takeIf { !round2 }?.let { buildEvidenceBlock(sid, it, verificationPackage) }

        // v4.1: Suspicion engine snapshot
        val suspicionSnapshot = suspicionEngine.getSnapshot()

        // v4.1: Screen detection signals
        val screenDetection = screenDetectionAnalyzer.toJson()

        // v4.2: On-device antispoof classifier (feature-flagged; server path is authoritative when off).
        val deepClassifierOnDevice = if (config.antispoofOnDeviceEnabled) {
            // Bounded: TFLite invoke is native and uncancellable. A classifier
            // that overruns is dropped; the server-side path is authoritative.
            when (val scored = boundedCall(meshScope, CLASSIFIER_TIMEOUT_MS) { buildDeepClassifierOnDevice(buffer) }) {
                is Bounded.Done -> scored.value
                is Bounded.TimedOut -> null
            }
        } else {
            null
        }

        val coveredCheckJson = runCatching { coveredCheck?.invoke() }.getOrNull()
        com.usesense.sdk.api.uploadTrace("covered_check ${coveredCheckJson ?: "absent"}")
        return metadataBuilder.build(
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
            faceMeshInitError = faceMeshManager.lastInitError ?: meshTimeoutError,
            coveredCheck = coveredCheckJson,
            evidence = evidenceBlock,
        )
    }

    /** What this build can do (EVIDENCE-v1 `capability`). */
    private val evidenceCapability: EvidenceCapability
        get() = EvidenceCapability.current(
            meshAvailable = meshInitResult == FaceMeshInitResult.SUCCESS,
            guided = guidedScan,
            playIntegrity = signalCollector.standardIntegrityAvailable,
        )

    /**
     * `metadata.evidence`: capability, the colour and closer records, and
     * binding v2 (a Play Integrity Standard token whose requestHash is the
     * evidence digest). Requested after the last frame, before the upload.
     */
    private suspend fun buildEvidenceBlock(sid: String, issued: EvidenceConfig, pkg: JSONObject?): JSONObject {
        val capability = evidenceCapability
        val colour = colourEvidence?.takeIf { issued.colourSequence != null }
        val digestFrames = EvidenceDigest.frames(pkg)
        val packaged = digestFrames.map { it.frameIndex }.toSet()
        val closer: CloserEvidence? = when {
            !guidedScan -> null
            !issued.closerEnabled -> CloserEvidence.skipped(CloserEvidence.DISABLED)
            closerSkipReason != null -> CloserEvidence.skipped(closerSkipReason!!, closerStartedAtMs, closerEndedAtMs)
            !closerRan || closerStartIndex < 0 -> CloserEvidence.skipped(CloserEvidence.TIMEOUT)
            else -> {
                // Frame indices are buffer indices = upload positions (one buffer per window).
                val meshed = meshedFrames().filter { it.index in packaged }
                val end = if (closerEndIndex >= 0) closerEndIndex else buffer().frameCount
                CloserResolver.resolve(meshed, closerStartIndex, end, issued.closerStep!!.minScale, closerStartedAtMs, closerEndedAtMs)
            }
        }
        var binding: BindingV2Evidence? = null
        val challenge = geometricCoherenceConfig?.meshBindingChallenge?.takeIf { it.isNotEmpty() }
        if (issued.bindingV2Enabled && pkg != null && challenge != null) {
            val digest = EvidenceDigest.digest(sid, challenge, digestFrames, colour, closer)
            binding = if (!capability.bindingV2) {
                BindingV2Evidence(digest, errorName = "NoCloudProject", errorMessage = "googleCloudProjectNumber not configured")
            } else {
                val r = signalCollector.standardIntegrityToken(meshScope, digest, BINDING_V2_WAIT_MS)
                BindingV2Evidence(digest, integrityToken = r.token, errorName = r.errorName, errorMessage = r.errorMessage)
            }
        }
        com.usesense.sdk.api.uploadTrace(
            "evidence frames=${digestFrames.size} colour=${colour?.segments?.size ?: -1}/${colour?.completed} " +
                "closer=${closer?.let { if (it.completed) "done ${it.farFrames}|${it.nearFrames}" else it.skippedReason } ?: "nil"} " +
                "binding=${binding?.let { if (it.integrityToken != null) "token" else it.errorName } ?: "none"}",
        )
        return EvidenceUploadBlock(capability, colour, closer, binding).toJson()
    }

    private fun buffer(): FrameBuffer = frameCaptureManager!!.getFrameBuffer()

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

            // Upload position i is frames[] part i (EVIDENCE-v1 section 3).
            val frames = buffer.getFrames()
            val positionOf = HashMap<Int, Int>(frames.size)
            frames.forEachIndexed { position, frame -> positionOf.putIfAbsent(frame.index, position) }

            val builder = VerificationPackageBuilder()
            return builder.build(
                fits = threeDMMFitter.results,
                crossFrameConsistency = threeDMMFitter.computeCrossFrameConsistency(),
                preliminaryScore = threeDMMFitter.computePreliminaryScore(),
                uploadHashes = frames.map { it.hash },
                uploadPositionOf = positionOf,
                meshBindingChallenge = gcConfig.meshBindingChallenge,
                meshDataList = faceMeshManager.frameMeshData,
                playIntegrityToken = playIntegrityToken,
                evidence = evidenceConfig != null,
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

    fun isExpired(): Boolean =
        com.usesense.sdk.internal.SessionExpiry.endsWithin(sessionResponse?.expiresAt, System.currentTimeMillis(), marginMs = 0L)

    /** Ended, or ends before a capture could finish (the person sat on the primer). */
    internal fun endsSoon(): Boolean =
        com.usesense.sdk.internal.SessionExpiry.endsWithin(sessionResponse?.expiresAt, System.currentTimeMillis())

    fun release() {
        integrityJob?.cancel()
        frameCaptureManager?.release()
        audioCaptureManager?.release()
        signalCollector.release()
        meshClosed = true
        meshScope.cancel()
        val stuck = stuckMeshWork
        if (stuck != null && !stuck.isCompleted) {
            // A detect is still inside native code: close the landmarker only
            // once it returns, never underneath it.
            stuck.invokeOnCompletion { faceMeshManager.release() }
        } else {
            // Close the landmarker only once an in-flight detect has returned.
            CoroutineScope(Dispatchers.Default).launch {
                meshMutex.withLock { faceMeshManager.release() }
            }
        }
        synchronized(fitterLock) { threeDMMFitter.reset() }
        suspicionEngine.reset()
        screenDetectionAnalyzer.reset()
        apiClient.clearSession()
    }
}
