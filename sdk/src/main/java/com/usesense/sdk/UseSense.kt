package com.usesense.sdk

import android.app.Activity
import android.content.Context
import com.usesense.sdk.ui.UseSenseActivity

object UseSense {

    private var config: UseSenseConfig? = null
    private var appContext: Context? = null

    internal val eventEmitter = UseSenseEventEmitter()

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

        UseSenseActivity.launch(activity, cfg, request, callback)
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
