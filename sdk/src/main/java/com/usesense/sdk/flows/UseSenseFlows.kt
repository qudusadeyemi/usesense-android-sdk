package com.usesense.sdk.flows

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Public entry point for the Flows runner. Coexists with the existing
 * Sessions API (UseSense.startVerification, etc.); this is a parallel
 * surface, not a replacement. See `guides/flows/sessions-vs-flows` in
 * the API docs for when to use which.
 *
 * Usage:
 *
 *   UseSenseFlows.run(
 *     activity = this,
 *     flowRunId = id,
 *     sdkToken = token,
 *     callback = object : FlowsCallback {
 *       override fun onResult(result: FlowRunResult) { ... }
 *       override fun onError(error: FlowError) { ... }
 *     },
 *   )
 */
object UseSenseFlows {
    @Volatile internal var pendingOptions: RunFlowOptions? = null
    @Volatile internal var pendingCallback: FlowsCallback? = null

    /**
     * Launch a Flow Run inside the host app. Presents a full-screen activity
     * that drives the parked steps and reports the terminal outcome via the
     * callback on the main thread. Mirrors `flows.run(...)` in the web SDK
     * and `UseSenseFlows.run(...)` on iOS.
     */
    @JvmOverloads
    fun run(
        activity: Context,
        flowRunId: String,
        sdkToken: String,
        callback: FlowsCallback,
        apiBaseUrl: String = "https://api.usesense.ai",
        /**
         * Optional SDK-init white-label appearance (Phase 1c). Takes precedence
         * over the operator's server-delivered appearance when theming the runner.
         */
        appearance: FlowAppearance? = null,
        /**
         * Optional SDK-init white-label copy (Phase 2). Takes precedence over the
         * operator's server-delivered copy when resolving subject-facing strings.
         */
        copy: FlowCopy? = null,
    ) {
        pendingOptions = RunFlowOptions(
            flowRunId = flowRunId,
            sdkToken = sdkToken,
            apiBaseUrl = apiBaseUrl,
            appearance = appearance,
            copy = copy,
        )
        pendingCallback = callback
        val intent = Intent(activity, FlowsActivity::class.java)
        if (activity !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
