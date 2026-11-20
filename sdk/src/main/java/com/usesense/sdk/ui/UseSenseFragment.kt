package com.usesense.sdk.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.usesense.sdk.R
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.VerificationRequest

/**
 * The capture, embedded in your own screen. The same flow as
 * [UseSense.startVerification] / [UseSense.startVerificationWithToken] (the
 * same screens, branding, copy and callback), drawn inside your layout
 * instead of a full-screen Activity. Mirrors iOS `UseSenseViewController`.
 *
 * ```
 * val fragment = UseSenseFragment.newInstance(request)          // API key session
 * // or UseSenseFragment.newInstanceWithToken(clientToken, SessionType.ENROLLMENT)
 * fragment.setCallback(callback)
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.container, fragment, "usesense")
 *     .commit()
 * ```
 *
 * - The callback fires exactly once: [UseSenseCallback.onSuccess] when the
 *   person leaves the result screen (straight away with
 *   `showResultScreens = false`), [UseSenseCallback.onError], or
 *   [UseSenseCallback.onCancelled] (back press, "Leave", the Fragment removed,
 *   the host Activity finishing). Remove the Fragment in the callback.
 * - Configuration changes: the verification survives (no callback fires). The
 *   recreated Fragment needs its callback again: find it by tag and call
 *   [setCallback], or let the host Activity (or parent Fragment) implement
 *   [UseSenseCallback], which is picked up automatically. A result reached
 *   while no callback is attached is held and delivered on attach.
 * - Back press is handled while the Fragment is shown: mid-capture it asks
 *   before leaving, otherwise it ends the verification as cancelled.
 * - Camera and microphone permissions are requested from the Fragment.
 * - Your layout owns the system bars; give the container the space you want
 *   the capture to fill (full screen works best for the camera).
 */
class UseSenseFragment : Fragment(), HostedCaptureFragment.Host {

    private val launch: CaptureLaunch by lazy {
        CaptureLaunches[requireArguments().getString(ARG_LAUNCH_ID)]
            // The process was recreated: the verification is gone. Report it
            // instead of showing an empty screen.
            ?: CaptureLaunches.register(
                CaptureLaunch(
                    config = UseSense.currentConfig ?: UseSenseConfig(apiKey = ""),
                    kind = CaptureLaunch.Kind.DIRECT,
                    initialError = CaptureLaunch.interruptedError(),
                ),
            ).also { requireArguments().putString(ARG_LAUNCH_ID, it.id) }
    }

    /** Where the result goes. Call again after a configuration change. */
    fun setCallback(callback: UseSenseCallback) {
        launch.callback = callback
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (launch.callback == null) {
            (parentFragment as? UseSenseCallback ?: context as? UseSenseCallback)?.let { launch.callback = it }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).apply { id = R.id.usesense_fragment_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A restored child on another launch id means the process was
        // recreated: replace it so the interruption reaches this callback.
        val existing = childFragmentManager.findFragmentByTag(CAPTURE_TAG)
        if (existing == null || existing.arguments?.getString(ARG_LAUNCH_ID) != launch.id) {
            childFragmentManager.beginTransaction()
                .replace(R.id.usesense_fragment_container, HostedCaptureFragment.newInstance(launch.id, fullScreen = false), CAPTURE_TAG)
                .commitNow()
        }
    }

    /** The capture closed (the callback has fired); the app removes this Fragment. */
    override fun onCaptureClosed() = Unit

    override fun onDestroy() {
        // A configuration change keeps the verification, but the callback most
        // likely points at the old Activity: drop it until the new one attaches.
        if (activity?.isChangingConfigurations == true) launch.callback = null
        super.onDestroy()
    }

    companion object {
        private const val ARG_LAUNCH_ID = "usesense.launch_id"
        private const val CAPTURE_TAG = "usesense.capture"

        /**
         * A verification on a session the SDK creates with the API key from
         * [UseSense.initialize], as [UseSense.startVerification].
         *
         * @throws IllegalStateException if [UseSense.initialize] was not called
         */
        @JvmStatic
        fun newInstance(request: VerificationRequest): UseSenseFragment {
            val cfg = UseSense.currentConfig ?: throw IllegalStateException(
                "UseSense.initialize() must be called before UseSenseFragment.newInstance()",
            )
            val invalid = UseSense.validateRequest(request) ?: UseSense.apiKeyMissing(cfg)
            return create(
                CaptureLaunch(cfg, CaptureLaunch.Kind.DIRECT, request = request, initialError = invalid),
            )
        }

        /**
         * A verification on the session a single-use client token exchanges
         * for, as [UseSense.startVerificationWithToken]. No API key is needed
         * on the device; [UseSense.initialize] is optional (its branding,
         * base URL and capture options apply when it was called).
         *
         * @param sessionType the type your server passed to `create-token`
         */
        @JvmStatic
        @JvmOverloads
        fun newInstanceWithToken(
            clientToken: String,
            sessionType: SessionType = SessionType.AUTHENTICATION,
        ): UseSenseFragment {
            // One launch per token: a second Fragment for the same token picks
            // up, or replays, the first instead of exchanging it again.
            return create(
                CaptureLaunch.forToken(
                    UseSense.tokenConfig(),
                    clientToken.trim(),
                    sessionType,
                    callback = null,
                    initialError = UseSense.validateClientToken(clientToken),
                ),
            )
        }

        private fun create(launch: CaptureLaunch): UseSenseFragment {
            CaptureLaunches.register(launch)
            return UseSenseFragment().apply {
                arguments = Bundle().apply { putString(ARG_LAUNCH_ID, launch.id) }
            }
        }
    }
}
