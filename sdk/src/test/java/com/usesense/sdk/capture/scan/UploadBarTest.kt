package com.usesense.sdk.capture.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** As iOS UploadBarTests (PR #99). */
class UploadBarTest {

    @Test fun `starts at 0_04 when the upload begins`() {
        val bar = UploadBar()
        assertEquals(0f, bar.value(0), 0f)
        bar.sending(1_000)
        assertEquals(0.04f, bar.value(1_000), 1e-4f)
    }

    @Test fun `about 0_47 after 2_3 s of sending, about 0_85 after 6_7 s of checking`() {
        val bar = UploadBar()
        bar.sending(0)
        val sent = bar.value(2_300)
        assertEquals(0.47f, sent, 0.02f)
        bar.checking(2_300)
        assertEquals(0.85f, bar.value(2_300 + 6_700), 0.03f)
    }

    @Test fun `keeps moving through 7 s of checking, under the cap, never back`() {
        val bar = UploadBar()
        bar.sending(0)
        bar.checking(2_300)
        var last = bar.value(2_300)
        var t = 2_300L
        while (t < 2_300L + 7_000L) {
            val next = bar.value(t + 1_000)
            assertTrue("moved ${next - last} at $t", next - last > 0.005f)
            assertTrue(next < UploadBar.CHECK_CAP)
            last = next
            t += 1_000
        }
        // Long after: still under the cap.
        assertTrue(bar.value(600_000) < UploadBar.CHECK_CAP)
    }

    @Test fun `checking continues from the current value without a jump`() {
        val bar = UploadBar()
        bar.sending(0)
        val before = bar.value(500)
        bar.checking(500)
        assertEquals(before, bar.value(500), 1e-5f)
    }

    @Test fun `stages only move forward and the value never goes back`() {
        val bar = UploadBar()
        bar.sending(0)
        bar.checking(1_000)
        val v = bar.value(3_000)
        bar.sending(3_000) // ignored
        assertEquals(UploadBar.Stage.CHECKING, bar.stage)
        assertTrue(bar.value(3_000) >= v)
        // An earlier clock reading can't pull the bar back.
        assertTrue(bar.value(1_500) >= v)
        bar.done()
        assertEquals(1f, bar.value(3_100), 0f)
    }

    @Test fun `reset returns to idle`() {
        val bar = UploadBar()
        bar.sending(0)
        bar.value(1_000)
        bar.reset()
        assertEquals(UploadBar.Stage.IDLE, bar.stage)
        assertEquals(0f, bar.value(2_000), 0f)
    }

    @Test fun `accessibility value is floored to 10 percent steps`() {
        assertEquals(40, UploadBar.accessiblePercent(0.47f))
        assertEquals(80, UploadBar.accessiblePercent(0.85f))
        assertEquals(0, UploadBar.accessiblePercent(0.04f))
        assertEquals(100, UploadBar.accessiblePercent(1f))
    }
}
