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
import com.usesense.sdk.signals.DeviceSignalCollector
import com.usesense.sdk.signals.MetadataBuilder
import kotlinx.coroutines.*
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
    private var captureStartTime: Date? = null
    private var captureEndTime: Date? = null

    val state: SessionState get() = stateMachine.currentState
    val sessionId: String? get() = sessionResponse?.sessionId
    val policy: SessionPolicy? get() = sessionResponse?.policy
    val uploadConfig: UploadConfig? get() = sessionResponse?.upload
    val challengeSpec: ChallengeSpec? get() = sessionResponse?.policy?.challenge
    val requiresAudio: Boolean get() = sessionResponse?.policy?.requiresAudio == true
    val expiresAt: String? get() = sessionResponse?.expiresAt

    val capturePhase: CapturePhase get() = stateMachine.capturePhase

    var onStateChanged: ((SessionState) -> Unit)? = null

    init {
        stateMachine.addListener { _, newState -> onStateChanged?.invoke(newState) }
    }

    fun setCapturePhase(phase: CapturePhase) {
        stateMachine.setCapturePhase(phase)
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
            // Request Play Integrity token bound to session nonce
            try {
                signalCollector.requestPlayIntegrityToken(response.nonce)
            } catch (_: Exception) {
                // Play Integrity is best-effort; don't block verification
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

        val sid = sessionId ?: return Result.failure(
            ApiException(UseSenseError.invalidConfig("No session ID"))
        )
        val buffer = frameCaptureManager?.getFrameBuffer()
            ?: return Result.failure(ApiException(UseSenseError.captureFailed("No frames captured")))

        val challengeResponse = challengePresenter?.responseBuilder?.build()
        val webIntegrity = signalCollector.collectSignals()
        val androidIntegrity = signalCollector.collectAndroidIntegrity()
        val deviceTelemetry = signalCollector.collectDeviceTelemetry()

        val timestamps = buffer.timestamps
        val avgInterval = if (timestamps.size > 1) {
            (timestamps.last() - timestamps.first()) / (timestamps.size - 1)
        } else 0L

        val metadataJson = metadataBuilder.build(
            challengeResponse = challengeResponse,
            webIntegrity = webIntegrity,
            androidIntegrity = androidIntegrity,
            deviceTelemetry = deviceTelemetry,
            captureStartTime = captureStartTime ?: Date(),
            captureEndTime = captureEndTime ?: Date(),
            framesCaptured = buffer.frameCount,
            framesDropped = 0,
            avgFrameIntervalMs = avgInterval,
        )

        return uploader.upload(
            sessionId = sid,
            frames = buffer.getJpegDataList(),
            metadataJson = metadataJson,
            audioData = audioData,
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
        frameCaptureManager?.release()
        audioCaptureManager?.release()
        signalCollector.release()
        apiClient.clearSession()
    }
}
