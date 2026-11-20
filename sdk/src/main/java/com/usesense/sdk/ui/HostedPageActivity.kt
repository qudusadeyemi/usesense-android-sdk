package com.usesense.sdk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.usesense.sdk.ServerBranding
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.VerificationRequest
import com.usesense.sdk.api.models.CreateSessionResponse

/**
 * The full-screen capture: hosts [HostedCaptureFragment] edge to edge in
 * the Activity's content view, and finishes when the capture closes. All
 * capture logic lives in the Fragment, shared with the embeddable
 * [UseSenseFragment].
 */
class HostedPageActivity : AppCompatActivity(), HostedCaptureFragment.Host {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (configuration change): the FragmentManager restores the
        // capture Fragment, which carries on from its launch.
        if (savedInstanceState == null) {
            val launchId = intent.getStringExtra(EXTRA_LAUNCH_ID).orEmpty()
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, HostedCaptureFragment.newInstance(launchId, fullScreen = true), FRAGMENT_TAG)
                .commitNow()
        }
    }

    override fun onCaptureClosed() = finish()

    companion object {
        private const val EXTRA_LAUNCH_ID = "usesense.launch_id"
        private const val FRAGMENT_TAG = "usesense.capture"

        private fun start(context: Context, launch: CaptureLaunch) {
            CaptureLaunches.register(launch)
            val intent = Intent(context, HostedPageActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_ID, launch.id)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Launch the hosted page UI for a direct SDK verification/enrollment flow.
         */
        fun startDirect(
            context: Context,
            config: UseSenseConfig,
            request: VerificationRequest,
            callback: UseSenseCallback,
        ) = start(context, CaptureLaunch(config, CaptureLaunch.Kind.DIRECT, request = request, callback = callback))

        /**
         * Server-side init: the same flow as [startDirect], on the session the
         * single-use [clientToken] exchanges for. No API key is used.
         */
        internal fun startWithToken(
            context: Context,
            config: UseSenseConfig,
            clientToken: String,
            sessionType: SessionType,
            callback: UseSenseCallback,
        ) = start(context, CaptureLaunch.forToken(config, clientToken, sessionType, callback))

        /**
         * Flows-runner entry point. Launches the same hosted capture UI as
         * startDirect but with pre-minted session credentials, so the screen
         * skips the createSession API call. The callback fires on the same
         * UseSenseCallback contract as direct mode; the Flows runner extracts
         * sessionId + identityId from the success result and advances the run.
         */
        internal fun startWithPrebuiltSession(
            context: Context,
            config: UseSenseConfig,
            response: CreateSessionResponse,
            sessionType: SessionType,
            serverBranding: ServerBranding? = null,
            callback: UseSenseCallback,
            refreshSession: (suspend () -> CreateSessionResponse)? = null,
        ) = start(
            context,
            CaptureLaunch(
                config,
                CaptureLaunch.Kind.PREBUILT,
                request = VerificationRequest(sessionType = sessionType),
                prebuiltSession = response,
                serverBranding = serverBranding,
                sessionFactory = refreshSession,
                callback = callback,
            ),
        )

        @JvmOverloads
        fun startEnrollment(
            context: Context,
            config: UseSenseConfig,
            remoteEnrollmentId: String,
            callback: UseSenseCallback? = null,
        ) = start(
            context,
            CaptureLaunch(config, CaptureLaunch.Kind.REMOTE_ENROLLMENT, remoteId = remoteEnrollmentId, callback = callback),
        )

        @JvmOverloads
        fun startVerification(
            context: Context,
            config: UseSenseConfig,
            remoteSessionId: String,
            callback: UseSenseCallback? = null,
        ) = start(
            context,
            CaptureLaunch(config, CaptureLaunch.Kind.REMOTE_VERIFICATION, remoteId = remoteSessionId, callback = callback),
        )
    }
}
