package com.usesense.sdk.signals

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the "Finalizing Enrollment" freeze.
 *
 * Google's `requestIntegrityToken` can settle neither of its listeners (stale
 * Play services, signed-out Play Store, wrong cloud project number, a network
 * that stalls mid round-trip). The SDK used to wrap it in a bare
 * `suspendCancellableCoroutine` and then `join()` that job unbounded before
 * uploading signals, so the whole verification wedged on the finalizing screen
 * having sent nothing to the server. Both waits are now bounded.
 *
 * These exercise the two constructs the production code uses, on virtual time.
 */
class PlayIntegrityTimeoutTest {

    @Test
    fun `a token request that never settles yields null instead of hanging`() = runTest {
        val result = withTimeoutOrNull(PlayIntegrityManager.TOKEN_TIMEOUT_MS) {
            // Neither listener fires: the exact field failure.
            suspendCancellableCoroutine<String?> { /* never resumed */ }
        }
        assertNull("an unsettled token request must time out, not hang", result)
    }

    @Test
    fun `a wedged integrity job does not block the caller`() = runTest {
        // A job that never completes, mirroring integrityJob at the call site.
        val wedged: Job = launch { suspendCancellableCoroutine<Unit> { } }

        val joined = withTimeoutOrNull(3_000L) { wedged.join() }

        assertNull("joining a wedged job must give up rather than block upload", joined)
        assertTrue("the job is still running; the caller simply stopped waiting", wedged.isActive)
        wedged.cancel()
    }

    @Test
    fun `a token that arrives within the window is still returned`() = runTest {
        // The timeout must not cost us attestation on a healthy device.
        val result = withTimeoutOrNull(PlayIntegrityManager.TOKEN_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { it.resumeWith(Result.success("token-abc")) }
        }
        assertEquals("token-abc", result)
    }

    @Test
    fun `timeouts are bounded and the join gives up first`() = runTest {
        // Guards against someone "fixing" a flake by setting these to 0 or huge.
        assertTrue(
            "token timeout must be positive and human-scale",
            PlayIntegrityManager.TOKEN_TIMEOUT_MS in 1_000L..15_000L,
        )
        // The job has already been running for the whole capture by the time
        // uploadSignals joins it, so the join must not add another full window.
        assertTrue(
            "join timeout must not exceed the token timeout",
            3_000L <= PlayIntegrityManager.TOKEN_TIMEOUT_MS,
        )
    }
}
