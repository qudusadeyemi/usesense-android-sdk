package com.usesense.sdk.capture.extracheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pose is relaxed only toward the side the current step asks for. */
class ExtraCheckMotionTest {
    private fun headTurn() =
        ExtraCheckRunner(
            ExtraCheckKind.HEAD_TURN,
            listOf(ExtraCheckStep(2000, direction = "left"), ExtraCheckStep(2000, direction = "right")),
        ).also { it.start(0) }

    @Test
    fun `turning toward the asked side is allowed, the other side is not`() {
        val r = headTurn()
        assertTrue(ExtraCheckMotion.forStep(r, turn = -0.4f, previous = null, msIntoStep = 500)!!.yawAllowed)
        assertFalse(ExtraCheckMotion.forStep(r, turn = 0.4f, previous = null, msIntoStep = 500)!!.yawAllowed)
        assertFalse(ExtraCheckMotion.forStep(r, turn = -0.4f, previous = null, msIntoStep = 500)!!.pitchAllowed)
        assertTrue(ExtraCheckMotion.forStep(r, turn = -0.4f, previous = null, msIntoStep = 500)!!.moving)
    }

    @Test
    fun `the previous side is allowed briefly while the head comes back`() {
        val r = headTurn()
        var t = 0L
        while (t < 2050) { t += 50; r.tick(t, ExtraCheckInput(true, turn = -0.4f)) }
        assertEquals(1, r.stepIndex)
        val left = ExtraCheckStep(2000, direction = "left")
        assertTrue(ExtraCheckMotion.forStep(r, turn = -0.4f, previous = left, msIntoStep = 300)!!.yawAllowed)
        assertFalse(ExtraCheckMotion.forStep(r, turn = -0.4f, previous = left, msIntoStep = 2000)!!.yawAllowed)
    }

    @Test
    fun `follow dot allows the dot's side, digits relaxes nothing`() {
        val dot = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, listOf(ExtraCheckStep(1000, x = 0.85f, y = 0.2f))).also { it.start(0) }
        assertTrue(ExtraCheckMotion.forStep(dot, turn = 0.3f, previous = null, msIntoStep = 0)!!.yawAllowed)
        assertFalse(ExtraCheckMotion.forStep(dot, turn = -0.3f, previous = null, msIntoStep = 0)!!.yawAllowed)
        assertTrue(ExtraCheckMotion.forStep(dot, turn = -0.3f, previous = null, msIntoStep = 0)!!.pitchAllowed)
        val digits = ExtraCheckRunner(ExtraCheckKind.SPEAK_DIGITS, listOf(ExtraCheckStep(4000))).also { it.start(0) }
        assertNull(ExtraCheckMotion.forStep(digits, turn = 0f, previous = null, msIntoStep = 0))
    }
}
