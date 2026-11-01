package com.usesense.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MediaPipe init/detect and TFLite are native calls cancellation cannot
 * interrupt. `withTimeout { withContext(io) { native() } }` would still hang;
 * boundedCall must hand control back on time regardless.
 */
class BoundedCallTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `returns the value of work that finishes in time`() = runBlocking {
        val r = boundedCall(scope, 1_000) { 42 }
        assertEquals(42, (r as Bounded.Done).value)
    }

    @Test
    fun `a null result is a result, not a timeout`() = runBlocking {
        val r = boundedCall<String?>(scope, 1_000) { null }
        assertTrue(r is Bounded.Done)
        assertNull((r as Bounded.Done).value)
    }

    @Test
    fun `an uninterruptible blocking call does not hold the caller`() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val r = boundedCall(scope, 200) {
            withContext(Dispatchers.IO) {
                // Ignores interruption and cancellation, like a wedged native call.
                while (!release.await(50, TimeUnit.MILLISECONDS)) { /* spin */ }
                "late"
            }
        }
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(r is Bounded.TimedOut)
        assertTrue("caller waited ${waitedMs}ms", waitedMs < 2_000)
        val work = (r as Bounded.TimedOut).work
        assertFalse("the work is still running and was not awaited", work.isCompleted)

        // The owner can clean up once the native call finally returns.
        val cleaned = CountDownLatch(1)
        work.invokeOnCompletion { cleaned.countDown() }
        release.countDown()
        assertTrue(cleaned.await(5, TimeUnit.SECONDS))
        scope.cancel()
    }
}
