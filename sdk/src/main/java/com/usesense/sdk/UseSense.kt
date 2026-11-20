package com.usesense.sdk

import android.app.Activity
import android.content.Context
import android.util.Log
import com.usesense.sdk.api.V4VerificationCallback
import com.usesense.sdk.api.V4VerificationRequest
import com.usesense.sdk.devicecheck.AndroidDeviceCheckSignals
import com.usesense.sdk.devicecheck.DeviceCheck
import com.usesense.sdk.devicecheck.DeviceCheckConfig
import com.usesense.sdk.devicecheck.DeviceCheckError
import com.usesense.sdk.devicecheck.DeviceCheckTransport
import com.usesense.sdk.signals.DeviceSignalCollector
import com.usesense.sdk.ui.HostedPageActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object UseSense {

    private var config: UseSenseConfig? = null
    private var appContext: Context? = null

    internal val eventEmitter = UseSenseEventEmitter()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val isInitialized: Boolean get() = config != null

    /** The `initialize` config, for surfaces launched outside it (a Flow's capture). */
    internal val currentConfig: UseSenseConfig? get() = config

    fun initialize(context: Context, config: UseSenseConfig) {
        require(config.apiKey.isNotBlank()) { "API key must not be blank" }
        this.appContext = context.applicationContext
        this.config = config
    }

    fun startVerification(
        activity: Activity,
        request: VerificationRequest,
        callback: UseSenseCallback,
    ) {
        val cfg = config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startVerification()"
        )

        validateRequest(request)?.let {
            callback.onError(it)
            return
        }

        HostedPageActivity.startDirect(activity, cfg, request, callback)
    }

    /**
     * Local checks that mirror the server: authentication needs an
     * identityId, and liveness must not carry one (the server returns 400).
     */
    internal fun validateRequest(request: VerificationRequest): UseSenseError? = when {
        request.sessionType == SessionType.AUTHENTICATION && request.identityId.isNullOrBlank() ->
            UseSenseError.invalidConfig("identity_id is required for authentication sessions")
        request.sessionType == SessionType.LIVENESS && !request.identityId.isNullOrBlank() ->
            UseSenseError.invalidConfig("identity_id must not be set for liveness sessions")
        else -> null
    }

    /**
     * Start a hosted remote enrollment flow (Section 10.1).
     * Fetches enrollment data from the server, shows introduction screen,
     * runs capture engine, and displays result.
     *
     * @param context Activity or application context
     * @param remoteEnrollmentId The enrollment ID from the remote enrollment link
     */
    fun startRemoteEnrollment(context: Context, remoteEnrollmentId: String) {
        val cfg = config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startRemoteEnrollment()"
        )
        HostedPageActivity.startEnrollment(context, cfg, remoteEnrollmentId)
    }

    /**
     * Start a hosted remote verification flow (Section 10.2).
     * Fetches session data from the server, shows action review screen,
     * runs capture engine, and displays result.
     *
     * @param context Activity or application context
     * @param remoteSessionId The session ID from the remote verification link
     */
    fun startRemoteVerification(context: Context, remoteSessionId: String) {
        val cfg = config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startRemoteVerification()"
        )
        HostedPageActivity.startVerification(context, cfg, remoteSessionId)
    }

    /**
     * Start a LiveSense v4 verification. Phase 1 ticket A-2.
     *
     * Launches LiveSenseV4Activity which drives the full flow:
     * CameraX capture at 30fps with locked exposure/WB, ML Kit face
     * detection feeding ZoomMotionController, parallel MediaCodec
     * MP4 encode, per-frame JPEG into HashChainBuilder, signature
     * via V4ChainSigner (StrongBox/TEE), upload through V4UploadClient,
     * and the opaque verdict delivered via V4VerificationCallback.
     *
     * CAMERA permission must be granted before calling this method.
     *
     * TODO: device-verify on Pixel 7 (StrongBox), Samsung A54 (TEE
     * fallback), and a mid-tier Xiaomi before GA.
     */
    fun startV4Verification(
        activity: Activity,
        request: V4VerificationRequest,
        callback: V4VerificationCallback
    ) {
        config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startV4Verification()"
        )
        com.usesense.sdk.v4.V4Bridge.install(callback)
        val intent = com.usesense.sdk.v4.LiveSenseV4Activity.buildIntent(activity, request)
        activity.startActivity(intent)
    }

    /**
     * A headless silent device check for "Your KYC": the app keeps its own KYC
     * provider's SDK and runs this next to it. No UI, no camera, no permission
     * prompts. [config] carries the `deviceCheckId`, `token` and `nonce` your
     * server got from `POST /v1/device-checks` (with `"platform": "android"`).
     *
     * Does not need [initialize]; when it was called, its base URL and Google
     * Cloud project number are used unless [config] sets its own. Never throws:
     * a malformed [config] makes [DeviceCheck.start] and [DeviceCheck.finish]
     * return [DeviceCheckError.Code.INVALID_CONFIG] without sending anything.
     *
     * ```
     * val check = UseSense.deviceCheck(context, DeviceCheckConfig(id, token, nonce))
     * check.start()           // before the provider's SDK opens
     * provider.start(metadata = mapOf("device_check_id" to id))
     * check.finish()          // optional, after it closes
     * ```
     */
    @JvmStatic
    fun deviceCheck(
        context: Context,
        config: DeviceCheckConfig,
    ): DeviceCheck {
        val app = context.applicationContext ?: context
        val baseUrl = config.baseUrl ?: this.config?.baseUrl ?: UseSenseConfig.DEFAULT_BASE_URL
        val project =
            config.googleCloudProjectNumber
                ?: this.config?.googleCloudProjectNumber
                ?: UseSenseConfig.DEFAULT_GOOGLE_CLOUD_PROJECT_NUMBER
        val log: (String) -> Unit = { Log.d(DEVICE_CHECK_TAG, it) }
        return DeviceCheck(
            config = config,
            signals = AndroidDeviceCheckSignals(app, project),
            transport =
                DeviceCheckTransport(
                    baseUrl = baseUrl,
                    deviceCheckId = config.deviceCheckId.trim(),
                    token = config.token.trim(),
                    userAgent = "UseSense-Android-SDK/${DeviceSignalCollector.SDK_VERSION}",
                    log = log,
                ),
            log = log,
        )
    }

    private const val DEVICE_CHECK_TAG = "UseSense.DeviceCheck"

    /**
     * Subscribe to SDK lifecycle events. Returns an unsubscribe function.
     */
    fun onEvent(callback: EventCallback): () -> Unit {
        return eventEmitter.addListener(callback)
    }

    fun reset() {
        config = null
        appContext = null
        eventEmitter.clear()
    }
}
