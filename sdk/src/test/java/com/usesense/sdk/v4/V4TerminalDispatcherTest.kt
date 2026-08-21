package com.usesense.sdk.v4

import com.usesense.sdk.api.V4Phase
import com.usesense.sdk.api.V4VerificationCallback
import com.usesense.sdk.api.V4Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch

class V4TerminalDispatcherTest {
    @Test
    fun `concurrent terminal outcomes deliver exactly one callback`() {
        var completions = 0
        var failures = 0
        val callback = object : V4VerificationCallback {
            override fun onComplete(verdict: V4Verdict) { completions++ }
            override fun onFailure(error: Throwable) { failures++ }
            override fun onPhaseChange(phase: V4Phase) = Unit
        }
        val dispatcher = V4TerminalDispatcher(callback) { it() }
        val verdict = V4Verdict("id", V4Verdict.Decision.PASS, V4Verdict.Confidence.HIGH, "hardware", matchSenseEmbeddingId = null, timestamp = "now")
        val start = CountDownLatch(1)
        val threads = listOf(
            Thread { start.await(); dispatcher.success(verdict) },
            Thread { start.await(); dispatcher.failure(IllegalStateException("failed")) },
        )
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, completions + failures)
    }
}
