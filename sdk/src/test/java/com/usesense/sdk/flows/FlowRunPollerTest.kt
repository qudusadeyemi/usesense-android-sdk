package com.usesense.sdk.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A run with no pending action used to leave the runner on a spinner with
 * nothing re-reading it. Polling is now bounded with a stable terminal code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowRunPollerTest {

    private fun TestScope.poller() = FlowRunPoller(nowMs = { testScheduler.currentTime })

    @Test
    fun `returns as soon as the run becomes actionable`() = runTest {
        var reads = 0
        val result = poller().pollUntil(
            fetch = { reads++; if (reads < 3) "settling" else "actionable" },
            isSettled = { it == "actionable" },
        )
        assertEquals("actionable", result)
        assertEquals(3, reads)
        assertTrue(testScheduler.currentTime < FlowRunPoller.BUDGET_MS)
    }

    @Test
    fun `a run that never settles ends with RUN_TIMEOUT inside the budget`() = runTest {
        var reads = 0
        try {
            poller().pollUntil(fetch = { reads++; "settling" }, isSettled = { false })
            fail("expected RUN_TIMEOUT")
        } catch (e: FlowError) {
            assertEquals(FlowError.Code.RUN_TIMEOUT, e.code)
            assertEquals("run_timeout", e.code.wire)
            assertEquals(FlowRunPoller.SERVER_CODE, e.serverCode)
        }
        assertEquals(FlowRunPoller.BUDGET_MS, testScheduler.currentTime)
        assertTrue("backs off rather than hammering: $reads reads", reads in 5..40)
    }

    @Test
    fun `transient network faults are retried within the budget`() = runTest {
        var reads = 0
        val result = poller().pollUntil(
            fetch = {
                reads++
                if (reads <= 2) throw FlowError(FlowError.Code.NETWORK_UNAVAILABLE, "offline")
                "actionable"
            },
            isSettled = { it == "actionable" },
        )
        assertEquals("actionable", result)
    }

    @Test
    fun `a non-network error ends polling at once`() = runTest {
        try {
            poller().pollUntil<String>(
                fetch = { throw FlowError(FlowError.Code.TOKEN_EXPIRED, "expired") },
                isSettled = { true },
            )
            fail("expected TOKEN_EXPIRED")
        } catch (e: FlowError) {
            assertEquals(FlowError.Code.TOKEN_EXPIRED, e.code)
        }
        assertTrue(testScheduler.currentTime <= FlowRunPoller.INITIAL_INTERVAL_MS)
    }
}
