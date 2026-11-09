package com.usesense.sdk.capture.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverWatchTest {

    private val w = 320
    private val h = 400

    /** Landmarks: the six anchors spread over a face, cheeks 234/454 set the face width (200 px). */
    private fun points(): FloatArray {
        val p = FloatArray(478 * 3)
        for (i in 0 until 478) { p[i * 3] = 160f; p[i * 3 + 1] = 200f }
        p[234 * 3] = 60f; p[454 * 3] = 260f
        val at = mapOf(151 to (160f to 100f), 50 to (100f to 200f), 280 to (220f to 200f),
            5 to (160f to 190f), 13 to (160f to 260f), 199 to (160f to 320f))
        for ((i, xy) in at) { p[i * 3] = xy.first; p[i * 3 + 1] = xy.second }
        return p
    }

    /** A skin-toned frame with fine texture; [scale] brightens or darkens it all. */
    private fun skin(scale: Float = 1f): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val n = ((x * 7 + y * 13) % 9) - 4
        val r = ((120 + n) * scale).toInt().coerceIn(0, 255)
        val g = ((84 + n) * scale).toInt().coerceIn(0, 255)
        val b = ((66 + n) * scale).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Paints a flat, paler block (a palm or paper) over the chin. */
    private fun covered(src: IntArray): IntArray = paint(src, 280..360)

    /** The same block over the forehead only. */
    private fun foreheadCovered(src: IntArray): IntArray = paint(src, 80..120)

    private fun paint(src: IntArray, rows: IntRange): IntArray {
        val out = src.copyOf()
        for (y in rows) for (x in 100..220) out[y * w + x] = (0xFF shl 24) or (200 shl 16) or (170 shl 8) or 150
        return out
    }

    private var singleFlags = 0

    private fun watched(frames: List<IntArray>): List<Boolean> {
        val watch = CoverWatch()
        val pts = points()
        singleFlags = 0
        return frames.map { f ->
            watch.covered(FaceRegions.measure(f, w, h, pts, 478)).also { singleFlags += watch.lastSingleFrameFlags }
        }
    }

    @Test fun `regions are measured`() {
        assertNotNull(FaceRegions.measure(skin(), w, h, points(), 478))
    }

    @Test fun `a steady face is not covered`() {
        assertFalse(watched(List(6) { skin() }).any { it })
    }

    @Test fun `an object over the chin is caught on its second frame`() {
        val result = watched(List(3) { skin() } + covered(skin()) + covered(skin()))
        assertFalse(result.take(4).any { it })
        assertTrue(result.last())
    }

    @Test fun `one failing frame does not count as covered, but is flagged`() {
        val result = watched(List(3) { skin() } + covered(skin()) + skin() + skin())
        assertFalse(result.any { it })
        assertEquals(1, singleFlags)
    }

    @Test fun `two failing frames on the same region count`() {
        val result = watched(List(3) { skin() } + covered(skin()) + covered(skin()))
        assertTrue(result.last())
        assertEquals(0, singleFlags)
    }

    @Test fun `alternating regions do not count`() {
        val frames = List(3) { skin() } + listOf(covered(skin()), foreheadCovered(skin()), covered(skin()), foreheadCovered(skin()))
        assertFalse(watched(frames).any { it })
    }

    @Test fun `a pass resets the region's count`() {
        val frames = List(3) { skin() } + listOf(covered(skin()), skin(), covered(skin()), skin())
        assertFalse(watched(frames).any { it })
        assertEquals(2, singleFlags)
    }

    @Test fun `a global brightness change (the fill light) is not a cover`() {
        assertFalse(watched(List(3) { skin() } + skin(1.25f)).last())
    }
}

class FaceCoverTest {
    /** A 100x100 frame, the FACE_OVAL ring on a circle of radius 40 around (50, 50). */
    private fun points(): FloatArray {
        val p = FloatArray(478 * 3)
        val ring = QualityGate.FACE_OVAL
        ring.forEachIndexed { k, idx ->
            val a = 2 * Math.PI * k / ring.size
            p[idx * 3] = (50 + 40 * kotlin.math.cos(a)).toFloat()
            p[idx * 3 + 1] = (50 + 40 * kotlin.math.sin(a)).toFloat()
        }
        return p
    }

    private fun cover(mask: IntArray) = FaceCover.measure({ mask[it] }, 100, 100, 100, 100, points(), 478)!!

    @Test fun `a clear face is not covered`() {
        val c = cover(IntArray(100 * 100) { FaceCover.FACE_SKIN })
        assertFalse(c.covered)
    }

    @Test fun `a hand over the lower face is covered`() {
        // Body skin over the bottom third of the face.
        val c = cover(IntArray(100 * 100) { if (it / 100 > 62) FaceCover.BODY_SKIN else FaceCover.FACE_SKIN })
        assertTrue("hand ${c.bodySkin}", c.covered)
    }

    @Test fun `hair over the forehead is not a cover`() {
        val c = cover(IntArray(100 * 100) { if (it / 100 < 35) FaceCover.HAIR else FaceCover.FACE_SKIN })
        assertFalse(c.covered)
    }
}
