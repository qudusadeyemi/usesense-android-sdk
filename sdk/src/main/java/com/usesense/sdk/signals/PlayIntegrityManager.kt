package com.usesense.sdk.signals

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Integrates Google Play Integrity API for device attestation.
 *
 * The integrity token is sent to the UseSense backend as part of
 * android_integrity signals. The backend verifies the token server-side
 * with Google's API to determine device trust level.
 *
 * Token contains:
 * - Device integrity verdict (MEETS_DEVICE_INTEGRITY, etc.)
 * - Account details (licensed app check)
 * - App integrity (genuine APK check)
 */
class PlayIntegrityManager(private val context: Context, private val cloudProjectNumber: Long) {

    companion object {
        private const val TAG = "PlayIntegrity"

        /**
         * Ceiling on a single token request.
         *
         * Google's `requestIntegrityToken` calls Play services and then Google's
         * servers, and in the field it can settle neither listener: stale Play
         * services, a signed-out Play Store, a mismatched cloud project number,
         * or a network that stalls mid round-trip. Without a ceiling the
         * suspending wrapper below never resumes, and because the caller joins
         * this job before uploading, the whole verification wedges on the
         * "Finalizing Enrollment" screen having sent nothing to the server.
         *
         * Attestation is best-effort by design, so timing out and moving on with
         * no token is strictly better than never finishing. 5s is generous for a
         * healthy device (typically well under 1s) and imperceptible next to the
         * capture that precedes it.
         */
        internal const val TOKEN_TIMEOUT_MS = 5_000L
    }

    /**
     * Request a Play Integrity token using the session nonce.
     *
     * @param nonce The session nonce from CreateSessionResponse, used to bind
     *              the integrity attestation to this specific verification session.
     * @return The integrity token string, or null if unavailable, if the request
     *         failed, or if it did not settle within [TOKEN_TIMEOUT_MS].
     */
    suspend fun requestIntegrityToken(nonce: String): String? {
        return try {
            val integrityManager = IntegrityManagerFactory.create(context)

            val tokenRequest = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .setNonce(nonce)
                .build()

            withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    integrityManager.requestIntegrityToken(tokenRequest)
                        .addOnSuccessListener { response ->
                            val token = response.token()
                            Log.d(TAG, "Play Integrity token obtained (${token.length} chars)")
                            continuation.resume(token)
                        }
                        .addOnFailureListener { exception ->
                            Log.w(TAG, "Play Integrity token request failed", exception)
                            continuation.resume(null)
                        }
                }
            } ?: run {
                Log.w(TAG, "Play Integrity token request timed out after ${TOKEN_TIMEOUT_MS}ms; continuing without it")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Play Integrity not available", e)
            null
        }
    }
}
