package com.usesense.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLiveGateTest {

    @Test
    fun `capture waits for the first frame`() {
        val gate = CameraLiveGate()
        var started = 0
        gate.whenLive { started++ }
        assertEquals("nothing starts before a frame arrives", 0, started)

        assertTrue(gate.markFrame())
        gate.onLive()
        assertEquals(1, started)
    }

    @Test
    fun `only the first frame opens the gate`() {
        val gate = CameraLiveGate()
        assertTrue(gate.markFrame())
        assertFalse(gate.markFrame())
        assertFalse(gate.markFrame())
    }

    @Test
    fun `a request after the camera is live runs at once, and only once`() {
        val gate = CameraLiveGate()
        assertTrue(gate.markFrame())
        gate.onLive() // nothing pending yet
        var started = 0
        gate.whenLive { started++ }
        assertEquals(1, started)
        gate.onLive()
        assertEquals(1, started)
    }

    @Test
    fun `a new binding needs its own first frame`() {
        val gate = CameraLiveGate()
        gate.markFrame()
        gate.reset()
        assertFalse(gate.isLive)
        var started = 0
        gate.whenLive { started++ }
        assertEquals(0, started)
        assertTrue(gate.markFrame())
        gate.onLive()
        assertEquals(1, started)
    }

    @Test
    fun `reset drops a start that was waiting on the old binding`() {
        val gate = CameraLiveGate()
        var started = 0
        gate.whenLive { started++ }
        gate.reset()
        gate.markFrame()
        gate.onLive()
        assertEquals(0, started)
    }
}
