package com.usesense.sdk.capture.scan

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
    private fun covered(src: IntArray): IntArray {
        val out = src.copyOf()
        for (y in 280..360) for (x in 100..220) out[y * w + x] = (0xFF shl 24) or (200 shl 16) or (170 shl 8) or 150
        return out
    }

    private fun watched(frames: List<IntArray>): List<Boolean> {
        val watch = CoverWatch()
        val pts = points()
        return frames.map { watch.covered(FaceRegions.measure(it, w, h, pts, 478)) }
    }

    @Test fun `regions are measured`() {
        assertNotNull(FaceRegions.measure(skin(), w, h, points(), 478))
    }

    @Test fun `a steady face is not covered`() {
        assertFalse(watched(List(6) { skin() }).any { it })
    }

    @Test fun `an object over the chin is caught after the baseline`() {
        val result = watched(List(3) { skin() } + covered(skin()))
        assertFalse(result.take(3).any { it })
        assertTrue(result.last())
    }

    @Test fun `a global brightness change (the fill light) is not a cover`() {
        assertFalse(watched(List(3) { skin() } + skin(1.25f)).last())
    }
}
