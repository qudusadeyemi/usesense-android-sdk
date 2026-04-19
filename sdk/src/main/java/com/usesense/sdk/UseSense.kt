package com.usesense.sdk

import android.app.Activity
import android.content.Context
import com.usesense.sdk.api.V4VerificationCallback
import com.usesense.sdk.api.V4VerificationRequest
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

        if (request.sessionType == SessionType.AUTHENTICATION && request.identityId.isNullOrBlank()) {
            callback.onError(
                UseSenseError.invalidConfig("identity_id is required for authentication sessions")
            )
            return
        }

        HostedPageActivity.startDirect(activity, cfg, request, callback)
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
     * v4 runs the perspective-distortion zoom-motion capture. The
     * capture activity is composed from the v4 modules shipped in
     * this SDK (ZoomMotionController, HardwareKeyManager,
     * HashChainBuilder, V4ChainSigner, ZoomPromptView, V4UploadClient).
     *
     * Full activity integration lands in a follow-up PR; the current
     * call throws UnsupportedOperationException with a clear message.
     * The v4 building blocks are individually usable for embedders
     * who want to build their own capture UX.
     */
    fun startV4Verification(
        activity: Activity,
        request: V4VerificationRequest,
        callback: V4VerificationCallback
    ) {
        val cfg = config ?: throw IllegalStateException(
            "UseSense.initialize() must be called before startV4Verification()"
        )
        // TODO: launch LiveSenseV4Activity (see v4/LiveSenseV4Manifest.kt
        // for the full integration checklist). Emit callback events as
        // the activity progresses through phases.
        callback.onFailure(
            UnsupportedOperationException(
                "LiveSense v4 activity integration is pending device verification. " +
                "Use the v4 building blocks directly if you need to proceed now: " +
                "ZoomMotionController, HardwareKeyManager, HashChainBuilder, " +
                "V4ChainSigner, V4UploadClient. See v4/LiveSenseV4Manifest.kt."
            )
        )
    }

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
