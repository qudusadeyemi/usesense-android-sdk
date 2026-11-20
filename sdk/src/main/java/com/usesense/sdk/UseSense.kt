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

    /**
     * Configure the SDK. [UseSenseConfig.apiKey] may be blank when the app
     * only starts verifications from client tokens
     * ([startVerificationWithToken]); [startVerification] then fails with
     * [UseSenseError.INVALID_CONFIG].
     */
    fun initialize(context: Context, config: UseSenseConfig) {
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

        (validateRequest(request) ?: apiKeyMissing(cfg))?.let {
            callback.onError(it)
            return
        }

        HostedPageActivity.startDirect(activity, cfg, request, callback)
    }

    /**
     * Start a verification from a client token (server-side init), with no
     * API key on the device. Same screens, branding, result and errors as
     * [startVerification]. Mirrors iOS `createSessionWithToken(clientToken:sessionType:)`
     * and the web SDK's `clientToken` option.
     *
     * 1. Your server calls `POST /v1/sessions/create-token` with its secret
     *    key (`sk_*`), the session type, and anything else the session needs
     *    (identity_id, reference_image, metadata).
     * 2. It hands the returned `client_token` (`cli_tok_...`, single-use,
     *    10 minutes) to the app.
     * 3. The app calls this. The SDK exchanges the token
     *    (`POST /v1/sessions/exchange-token`) and runs the capture.
     *
     * [initialize] is optional here: when it was called, its base URL,
     * branding and capture options apply (the API key is not used). The token
     * is single-use, so a rejected result offers no "Try again": start a new
     * verification with a new token. Exchange errors arrive on
     * [UseSenseCallback.onError] (`token_not_found`, `token_expired`,
     * `token_already_used` in [UseSenseError.serverCode]).
     *
     * @param sessionType the type your server passed to `create-token` (the
     *   exchange response does not repeat it); it picks the screens' copy.
     */
    @JvmStatic
    fun startVerificationWithToken(
        activity: Activity,
        clientToken: String,
        sessionType: SessionType,
        callback: UseSenseCallback,
    ) {
        validateClientToken(clientToken)?.let {
            callback.onError(it)
            return
        }
        HostedPageActivity.startWithToken(activity, tokenConfig(), clientToken.trim(), sessionType, callback)
    }

    /** The config a token launch runs with: [initialize]'s, else the defaults (no key needed). */
    internal fun tokenConfig(): UseSenseConfig = config ?: UseSenseConfig(apiKey = "")

    internal fun validateClientToken(clientToken: String): UseSenseError? =
        if (clientToken.isBlank()) UseSenseError.invalidConfig("clientToken must not be blank") else null

    /** An API key session without a key: the server would answer 401. */
    internal fun apiKeyMissing(config: UseSenseConfig): UseSenseError? =
        if (config.apiKey.isBlank()) {
            UseSenseError.invalidConfig(
                "UseSenseConfig.apiKey is blank: pass a key, or start from a client token with startVerificationWithToken()",
            )
        } else {
            null
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
     * [callback] (optional; the two-argument form still works) hears the
     * outcome once, as iOS `HostedEnrollmentViewController`'s `onComplete`:
     * [UseSenseCallback.onSuccess] with the decision when the person leaves
     * the result screen, [UseSenseCallback.onError], or
     * [UseSenseCallback.onCancelled] when they leave before the end.
     *
     * @param context Activity or application context
     * @param remoteEnrollmentId The enrollment ID from the remote enrollment link
     */
    @JvmOverloads
    fun startRemoteEnrollment(context: Context, remoteEnrollmentId: String, callback: UseSenseCallback? = null) {
        val cfg = config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startRemoteEnrollment()"
        )
        if (remoteEnrollmentId.isBlank() && callback != null) {
            callback.onError(UseSenseError.invalidConfig("remoteEnrollmentId must not be blank"))
            return
        }
        HostedPageActivity.startEnrollment(context, cfg, remoteEnrollmentId, callback)
    }

    /**
     * Start a hosted remote verification flow (Section 10.2).
     * Fetches session data from the server, shows action review screen,
     * runs capture engine, and displays result.
     *
     * [callback] (optional; the two-argument form still works) hears the
     * outcome once, as iOS `HostedVerificationViewController`'s `onComplete`:
     * [UseSenseCallback.onSuccess] with the decision (or
     * [UseSenseResult.DECISION_DISPUTED] when the person disputes the action),
     * [UseSenseCallback.onError], or [UseSenseCallback.onCancelled].
     *
     * @param context Activity or application context
     * @param remoteSessionId The session ID from the remote verification link
     */
    @JvmOverloads
    fun startRemoteVerification(context: Context, remoteSessionId: String, callback: UseSenseCallback? = null) {
        val cfg = config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startRemoteVerification()"
        )
        if (remoteSessionId.isBlank() && callback != null) {
            callback.onError(UseSenseError.invalidConfig("remoteSessionId must not be blank"))
            return
        }
        HostedPageActivity.startVerification(context, cfg, remoteSessionId, callback)
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
