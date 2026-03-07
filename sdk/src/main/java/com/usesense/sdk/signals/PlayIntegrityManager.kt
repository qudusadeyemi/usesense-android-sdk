package com.usesense.sdk.signals

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
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
class PlayIntegrityManager(private val context: Context) {

    companion object {
        private const val TAG = "PlayIntegrity"
    }

    /**
     * Request a Play Integrity token using the session nonce.
     *
     * @param nonce The session nonce from CreateSessionResponse, used to bind
     *              the integrity attestation to this specific verification session.
     * @return The integrity token string, or null if unavailable.
     */
    suspend fun requestIntegrityToken(nonce: String): String? {
        return try {
            val integrityManager = IntegrityManagerFactory.create(context)

            val tokenRequest = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

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
        } catch (e: Exception) {
            Log.w(TAG, "Play Integrity not available", e)
            null
        }
    }
}
