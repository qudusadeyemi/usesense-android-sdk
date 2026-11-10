package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.capture.scan.OvalSpec
import com.usesense.sdk.capture.scan.PointMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraCheckFaceTest {
    private val identity =
        PointMapper { x, y, out ->
            out[0] = x
            out[1] = y
        }
    private val oval = OvalSpec(cx = 200f, cy = 300f, rx = 140f, ry = 200f)

    /** 478 points; the ones the reading uses placed for a face centred at (cx, cy) with the nose at [noseX]. */
    private fun face(
        cx: Float,
        cy: Float,
        noseX: Float,
    ): FloatArray {
        val p = FloatArray(478 * 3)

        fun set(
            i: Int,
            x: Float,
            y: Float,
        ) {
            p[i * 3] = x
            p[i * 3 + 1] = y
        }
        set(10, cx, cy - 120)
        set(152, cx, cy + 120)
        set(234, cx - 90, cy)
        set(454, cx + 90, cy)
        set(1, noseX, cy + 10)
        return p
    }

    @Test
    fun `a centred, frontal face is in the oval with no turn`() {
        val (inOval, turn) = ExtraCheckFace.read(face(200f, 300f, 200f), 1, identity, oval)
        assertTrue(inOval)
        assertEquals(0f, turn!!, 0.001f)
    }

    @Test
    fun `the nose left of the cheeks reads as a turn to the left`() {
        val (_, turn) = ExtraCheckFace.read(face(200f, 300f, 160f), 1, identity, oval)
        assertEquals(-40f / 90f, turn!!, 0.001f)
        assertTrue(turn < -ExtraCheckRunner.TURN_ENOUGH)
    }

    @Test
    fun `a face off to the side is out of the oval`() {
        val (inOval, _) = ExtraCheckFace.read(face(330f, 300f, 330f), 1, identity, oval)
        assertFalse(inOval)
    }

    @Test
    fun `no face is out of the oval with no reading`() {
        val (inOval, turn) = ExtraCheckFace.read(null, 0, identity, oval)
        assertFalse(inOval)
        assertNull(turn)
    }
}
