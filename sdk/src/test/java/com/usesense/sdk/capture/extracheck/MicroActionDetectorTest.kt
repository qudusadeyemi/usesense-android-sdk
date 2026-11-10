package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.capture.scan.GateHint
import com.usesense.sdk.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RMAS micro-actions on the gate's mesh (on-device attack detection), and a paused movement's guidance. */
class MicroActionDetectorTest {
    /** A face with the points the detector reads; mouth width, brow gap and lip gap set. */
    private fun face(mouthW: Float = 60f, brow: Float = 20f, lips: Float = 2f): FloatArray {
        val p = FloatArray(478 * 3)
        fun set(i: Int, x: Float, y: Float) { p[i * 3] = x; p[i * 3 + 1] = y }
        set(10, 200f, 100f); set(152, 200f, 340f); set(234, 110f, 220f); set(454, 290f, 220f)
        set(61, 200f - mouthW / 2, 290f); set(291, 200f + mouthW / 2, 290f)
        set(159, 160f, 190f); set(105, 160f, 190f - brow); set(386, 240f, 190f); set(334, 240f, 190f - brow)
        set(13, 200f, 288f); set(14, 200f, 288f + lips)
        return p
    }

    @Test
    fun `smile, brows and mouth are seen against the first frame`() {
        val smile = MicroActionDetector(MicroActionDetector.SMILE)
        assertFalse(smile.feed(face(), null, null, null))
        assertFalse(smile.feed(face(mouthW = 63f), null, null, null))
        assertTrue(smile.feed(face(mouthW = 72f), null, null, null))
        val brows = MicroActionDetector(MicroActionDetector.RAISE_EYEBROWS)
        brows.feed(face(), null, null, null)
        assertTrue(brows.feed(face(brow = 24f), null, null, null))
        val mouth = MicroActionDetector(MicroActionDetector.OPEN_MOUTH)
        mouth.feed(face(), null, null, null)
        assertFalse(mouth.feed(face(lips = 8f), null, null, null))
        assertTrue(mouth.feed(face(lips = 20f), null, null, null))
    }

    @Test
    fun `blink and turns`() {
        val blink = MicroActionDetector(MicroActionDetector.BLINK)
        assertFalse(blink.feed(face(), 0.1f, 0.1f, null))
        assertTrue(blink.feed(face(), 0.8f, 0.7f, null))
        assertTrue(MicroActionDetector(MicroActionDetector.TURN_LEFT).feed(null, null, null, -0.3f))
        assertFalse(MicroActionDetector(MicroActionDetector.TURN_RIGHT).feed(null, null, null, -0.3f))
    }

    @Test
    fun `a paused movement names the check that holds it`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "left"))).also { it.start(0) }
        var t = 0L
        while (t < 500) { t += 50; r.tick(t, ExtraCheckInput(false)) }
        assertEquals(
            Guidance(R.string.usesense_hint_face_covered, R.string.usesense_xc_paused_turn_detail),
            ExtraCheckCopy.guidance(r, pausedBy = GateHint.FACE_COVERED),
        )
        assertEquals(R.string.usesense_hint_centre, ExtraCheckCopy.guidance(r).title)
    }
}
