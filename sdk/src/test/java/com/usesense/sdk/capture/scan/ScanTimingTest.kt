package com.usesense.sdk.capture.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintSchedulerTest {

    @Test fun `a hint appears only after failing for 400 ms`() {
        val s = HintScheduler()
        s.update(0, listOf(GateHint.CENTRE))
        assertNull(s.current)
        s.update(399, listOf(GateHint.CENTRE))
        assertNull(s.current)
        assertTrue(s.update(400, listOf(GateHint.CENTRE)))
        assertEquals(GateHint.CENTRE, s.current)
    }

    @Test fun `a break in failing restarts the 400 ms`() {
        val s = HintScheduler()
        s.update(0, listOf(GateHint.CENTRE))
        s.update(300, emptyList())
        s.update(350, listOf(GateHint.CENTRE))
        s.update(700, listOf(GateHint.CENTRE))
        assertNull(s.current)
        s.update(750, listOf(GateHint.CENTRE))
        assertEquals(GateHint.CENTRE, s.current)
    }

    @Test fun `a shown hint stays at least 1_2 s`() {
        val s = HintScheduler()
        s.update(0, listOf(GateHint.CENTRE))
        s.update(400, listOf(GateHint.CENTRE))
        s.update(500, emptyList())
        assertEquals(GateHint.CENTRE, s.current)
        s.update(1599, emptyList())
        assertEquals(GateHint.CENTRE, s.current)
        assertFalse(s.update(1600, emptyList()))
        assertNull(s.current)
    }

    @Test fun `only the highest ready hint shows`() {
        val s = HintScheduler()
        s.update(0, listOf(GateHint.HOLD_STILL))
        s.update(200, listOf(GateHint.LOOK_STRAIGHT, GateHint.HOLD_STILL))
        // Stillness has failed 400 ms, pose only 200 ms: stillness shows.
        s.update(400, listOf(GateHint.LOOK_STRAIGHT, GateHint.HOLD_STILL))
        assertEquals(GateHint.HOLD_STILL, s.current)
        // Once pose has failed 400 ms and stillness has been up 1.2 s, pose wins.
        s.update(1600, listOf(GateHint.LOOK_STRAIGHT, GateHint.HOLD_STILL))
        assertEquals(GateHint.LOOK_STRAIGHT, s.current)
    }

    @Test fun `new hints are reported for announcement at most every 1_2 s`() {
        val s = HintScheduler()
        val announced = mutableListOf<Long>()
        var t = 0L
        val pattern = listOf(GateHint.CENTRE, GateHint.MOVE_CLOSER, GateHint.HOLD_STILL)
        while (t < 6000) {
            // The failing hint changes every 450 ms.
            val h = pattern[((t / 450) % pattern.size).toInt()]
            if (s.update(t, listOf(h))) announced += t
            t += 50
        }
        announced.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b - a >= 1200) }
    }
}

class ScanControllerTest {

    @Test fun `banks time while passing and completes`() {
        val c = ScanController(totalMs = 1000)
        c.beginAlign(0)
        c.start(0, manual = false)
        var t = 0L
        var ev: ScanController.Event? = null
        while (ev == null && t < 5000) { t += 100; ev = c.tick(t, allPass = true) }
        assertEquals(ScanController.Event.COMPLETED, ev)
        assertEquals(1000L, t)
        assertEquals(1f, c.progress)
    }

    @Test fun `movement pauses and keeps banked time`() {
        val c = ScanController(totalMs = 4000)
        c.beginAlign(0)
        c.start(0, manual = false)
        c.tick(500, true)
        c.tick(1000, true)
        assertEquals(ScanController.Event.PAUSED, c.tick(1100, false))
        val banked = c.bankedMs
        assertNull(c.tick(1500, false))
        assertEquals(banked, c.bankedMs)
        assertEquals(ScanController.Event.RESUMED, c.tick(1600, true))
        assertEquals(banked, c.bankedMs)
        c.tick(1700, true)
        assertTrue(c.bankedMs > banked)
    }

    @Test fun `a pause over 1_5 s drops the window`() {
        val c = ScanController(totalMs = 4000)
        c.beginAlign(0)
        c.start(0, manual = false)
        c.tick(200, true)
        c.tick(300, false)
        assertNull(c.tick(1800, false))
        assertEquals(ScanController.Event.DROPPED, c.tick(1850, false))
        assertEquals(ScanController.State.ALIGNING, c.state)
        assertEquals(0L, c.bankedMs)
        assertEquals(1, c.droppedWindows)
    }

    @Test fun `after a manual start pauses never drop`() {
        val c = ScanController(totalMs = 4000)
        c.beginAlign(0)
        c.start(0, manual = true)
        c.tick(100, true)
        c.tick(200, false)
        assertNull(c.tick(10_000, false))
        assertEquals(ScanController.State.PAUSED, c.state)
    }

    @Test fun `shutter after 20 s of aligning`() {
        val c = ScanController(totalMs = 4000)
        c.beginAlign(1000)
        assertFalse(c.shutterAvailable(20_999))
        assertTrue(c.shutterAvailable(21_000))
    }

    @Test fun `shutter after 3 dropped windows`() {
        val c = ScanController(totalMs = 4000, shutterAfterMs = 1_000_000)
        c.beginAlign(0)
        var t = 0L
        repeat(3) {
            assertFalse(c.shutterAvailable(t))
            c.start(t, manual = false)
            c.tick(t + 100, false)
            c.tick(t + 1700, false)
            t += 2000
        }
        assertTrue(c.shutterAvailable(t))
    }

    @Test fun `a stalled clock banks at most 250 ms per tick`() {
        val c = ScanController(totalMs = 4000)
        c.beginAlign(0)
        c.start(0, manual = false)
        c.tick(3000, true)
        assertEquals(250L, c.bankedMs)
    }
}
