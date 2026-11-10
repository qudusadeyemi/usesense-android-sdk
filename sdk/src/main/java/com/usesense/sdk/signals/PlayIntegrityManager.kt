package com.usesense.sdk.signals

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.async
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

        /** Ceiling on preparing the Standard provider (done ahead, at session start). */
        internal const val PREPARE_TIMEOUT_MS = 15_000L
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

    // ─── Standard request (EVIDENCE-v1 binding v2) ──────────────────────────

    /** The result of a Standard request: a token, or why there is none. */
    data class StandardResult(val token: String?, val errorName: String?, val errorMessage: String?)

    @Volatile private var providerJob: kotlinx.coroutines.Deferred<Result<StandardIntegrityManager.StandardIntegrityTokenProvider>>? = null

    /**
     * Warm up the Standard token provider (Google: prepare ahead, it can take
     * seconds). Safe to call more than once; the first call wins.
     */
    fun prepareStandard(scope: kotlinx.coroutines.CoroutineScope) {
        if (providerJob != null) return
        synchronized(this) {
            if (providerJob != null) return
            providerJob = scope.async {
                runCatching {
                    val manager = IntegrityManagerFactory.createStandard(context)
                    val request = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build()
                    withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
                        suspendCancellableCoroutine<StandardIntegrityManager.StandardIntegrityTokenProvider> { cont ->
                            manager.prepareIntegrityToken(request)
                                .addOnSuccessListener { cont.resume(it) }
                                .addOnFailureListener { cont.resumeWith(Result.failure(it)) }
                        }
                    } ?: throw java.util.concurrent.TimeoutException("prepareIntegrityToken took over ${PREPARE_TIMEOUT_MS}ms")
                }
            }
        }
    }

    /**
     * A Standard token whose `requestHash` is [requestHash] (the binding v2
     * digest, 64 hex characters, as is). Never throws; bounded by [waitMs].
     */
    suspend fun requestStandardToken(scope: kotlinx.coroutines.CoroutineScope, requestHash: String, waitMs: Long): StandardResult {
        prepareStandard(scope)
        val outcome = withTimeoutOrNull(waitMs) {
            val provider = providerJob!!.await().getOrElse { return@withTimeoutOrNull failure(it) }
            runCatching {
                suspendCancellableCoroutine<String> { cont ->
                    provider.request(
                        StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                            .setRequestHash(requestHash)
                            .build(),
                    )
                        .addOnSuccessListener { cont.resume(it.token()) }
                        .addOnFailureListener { cont.resumeWith(Result.failure(it)) }
                }
            }.fold(
                onSuccess = { StandardResult(it, null, null) },
                onFailure = { failure(it) },
            )
        }
        return outcome ?: StandardResult(null, "Timeout", "Play Integrity standard request took over ${waitMs}ms")
    }

    private fun failure(e: Throwable): StandardResult {
        Log.w(TAG, "Play Integrity standard request failed", e)
        val code = (e as? com.google.android.play.core.integrity.StandardIntegrityException)?.errorCode
        val name = e.javaClass.simpleName + (code?.let { "#$it" } ?: "")
        return StandardResult(null, name, e.message.orEmpty())
    }
}
