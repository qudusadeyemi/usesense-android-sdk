package com.usesense.sdk.internal

import com.usesense.sdk.UseSenseError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule: a capture reaches a terminal error with a stable code within the
 * watchdog budget, whatever is stuck. Driven on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureWatchdogTest {

    private class Fired(val phase: WatchdogPhase, val error: UseSenseError)

    private fun TestScope.watchdog(sink: MutableList<Fired>) = CaptureWatchdog(
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
    ) { phase, error -> sink += Fired(phase, error) }

    @Test
    fun `a capture that never completes fires CAPTURE_TIMEOUT at the total budget`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.CAMERA)
        advanceTimeBy(1_000)
        dog.enter(WatchdogPhase.CAPTURE)

        advanceTimeBy(CaptureWatchdog.TOTAL_BUDGET_MS - 2_000)
        runCurrent()
        assertTrue("must not fire early", fired.isEmpty())

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, fired.size)
        assertEquals(WatchdogPhase.CAPTURE, fired[0].phase)
        assertEquals(UseSenseError.CAPTURE_TIMEOUT, fired[0].error.code)
        assertEquals("CAPTURE_TIMEOUT", fired[0].error.serverCode)
        assertTrue(fired[0].error.isRetryable)
        assertTrue(dog.fired)
        assertFalse(dog.isArmed)
    }

    @Test
    fun `a camera that never binds fires at the camera cap, not the total budget`() = runTest {
        val fired = mutableListOf<Fired>()
        watchdog(fired).arm(WatchdogPhase.CAMERA)

        advanceTimeBy(CaptureWatchdog.CAMERA_BUDGET_MS + 600)
        runCurrent()
        assertEquals(1, fired.size)
        assertEquals(WatchdogPhase.CAMERA, fired[0].phase)
        assertEquals("CAPTURE_TIMEOUT", fired[0].error.serverCode)
        assertEquals("camera", fired[0].error.details?.get("phase"))
    }

    @Test
    fun `leaving CAMERA in time lifts the camera cap`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.CAMERA)
        advanceTimeBy(5_000)
        dog.enter(WatchdogPhase.CAPTURE)
        advanceTimeBy(CaptureWatchdog.CAMERA_BUDGET_MS * 2)
        runCurrent()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a wedged packaging or upload step fires UPLOAD_TIMEOUT`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.CAPTURE)
        advanceTimeBy(8_000)
        dog.enter(WatchdogPhase.PREPARING) // e.g. a native call that never returns

        advanceTimeBy(CaptureWatchdog.TOTAL_BUDGET_MS)
        runCurrent()
        assertEquals(1, fired.size)
        assertEquals(WatchdogPhase.PREPARING, fired[0].phase)
        assertEquals(UseSenseError.UPLOAD_TIMEOUT, fired[0].error.code)
        assertEquals("UPLOAD_TIMEOUT", fired[0].error.serverCode)
    }

    @Test
    fun `steady upload progress keeps a slow upload alive past the budget`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.CAPTURE)
        dog.enter(WatchdogPhase.UPLOADING)
        // 200s of upload with a progress tick every 10s: slow, but moving.
        repeat(20) {
            advanceTimeBy(10_000)
            dog.onProgress()
        }
        runCurrent()
        assertTrue("a moving upload must not be cut off", fired.isEmpty())

        // Then the bytes stop.
        advanceTimeBy(CaptureWatchdog.PROGRESS_GRACE_MS + 1_000)
        runCurrent()
        assertEquals(1, fired.size)
        assertEquals("UPLOAD_TIMEOUT", fired[0].error.serverCode)
    }

    @Test
    fun `disarm on a terminal state or a subject-paced screen stops it`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.CAMERA)
        dog.disarm()
        advanceTimeBy(CaptureWatchdog.TOTAL_BUDGET_MS * 3)
        runCurrent()
        assertTrue(fired.isEmpty())
        assertNull(dog.phase)
    }

    @Test
    fun `re-arming for Try again resets the budget and the fired flag`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.CAPTURE)
        advanceTimeBy(CaptureWatchdog.TOTAL_BUDGET_MS + 1_000)
        runCurrent()
        assertEquals(1, fired.size)

        dog.arm(WatchdogPhase.CAMERA)
        assertFalse(dog.fired)
        advanceTimeBy(1_000)
        dog.enter(WatchdogPhase.CAPTURE)
        advanceTimeBy(CaptureWatchdog.TOTAL_BUDGET_MS - 5_000)
        runCurrent()
        assertEquals("the new attempt gets a fresh budget", 1, fired.size)
    }

    @Test
    fun `fires exactly once`() = runTest {
        val fired = mutableListOf<Fired>()
        val dog = watchdog(fired)
        dog.arm(WatchdogPhase.COMPLETING)
        advanceTimeBy(CaptureWatchdog.TOTAL_BUDGET_MS * 4)
        runCurrent()
        assertEquals(1, fired.size)
        assertNotNull(fired[0].error.details?.get("elapsed_ms"))
    }
}
