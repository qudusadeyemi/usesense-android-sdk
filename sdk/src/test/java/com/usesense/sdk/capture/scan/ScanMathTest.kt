package com.usesense.sdk.capture.scan

import com.usesense.sdk.capture.ImageQualityAnalyzer
import com.usesense.sdk.flows.AppearanceCapture
import com.usesense.sdk.flows.FlowAppearance
import com.usesense.sdk.flows.mergeAppearance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HeadPoseMathTest {

    /** 4x4 transform Rz(c) * Ry(b) * Rx(a) with a translation, packed column- or row-major. */
    private fun matrix(a: Double, b: Double, c: Double, columnMajor: Boolean, scale: Double = 1.0): FloatArray {
        val ca = cos(a); val sa = sin(a)
        val cb = cos(b); val sb = sin(b)
        val cc = cos(c); val sc = sin(c)
        val r = arrayOf(
            doubleArrayOf(cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa),
            doubleArrayOf(sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa),
            doubleArrayOf(-sb, cb * sa, cb * ca),
        )
        val t = doubleArrayOf(1.5, -2.0, -45.0)
        val m = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) {
            val v = when {
                row < 3 && col < 3 -> r[row][col] * scale
                row < 3 && col == 3 -> t[row]
                row == 3 && col == 3 -> 1.0
                else -> 0.0
            }
            m[if (columnMajor) col * 4 + row else row * 4 + col] = v.toFloat()
        }
        return m
    }

    private fun check(columnMajor: Boolean) {
        val pitch = Math.toRadians(8.0)
        val yaw = Math.toRadians(-20.0)
        val roll = Math.toRadians(5.0)
        val p = HeadPoseMath.fromMatrix(matrix(pitch, yaw, roll, columnMajor, scale = 1.3))!!
        assertEquals(-20.0, p.yaw, 0.01)
        assertEquals(8.0, p.pitch, 0.01)
        assertEquals(5.0, p.roll, 0.01)
    }

    @Test fun `ZYX angles from a column-major matrix`() = check(columnMajor = true)

    @Test fun `ZYX angles from a row-major matrix`() = check(columnMajor = false)

    @Test fun `layout is detected from the depth translation`() {
        assertTrue(HeadPoseMath.isColumnMajor(matrix(0.0, 0.0, 0.0, columnMajor = true)))
        assertTrue(!HeadPoseMath.isColumnMajor(matrix(0.0, 0.0, 0.0, columnMajor = false)))
    }

    @Test fun `degenerate matrix gives no pose`() {
        assertNull(HeadPoseMath.fromMatrix(FloatArray(16)))
        assertNull(HeadPoseMath.fromMatrix(FloatArray(4)))
    }
}

class DepthGridTest {

    /**
     * A synthetic 478-point face: a dome of points in a 200x260 px ellipse
     * centred at (200, 300), nearest (smallest z) at the centre. Forehead (10),
     * chin (152) and cheeks (234, 454) sit on the outline.
     */
    private fun face(): Triple<FloatArray, FloatArray, FloatArray> {
        val xs = FloatArray(478); val ys = FloatArray(478); val zs = FloatArray(478)
        for (i in 0 until 478) {
            val a = i * 2.399963
            val r = kotlin.math.sqrt((i + 0.5) / 478.0)
            xs[i] = 200f + (100 * r * cos(a)).toFloat()
            ys[i] = 300f + (130 * r * sin(a)).toFloat()
            zs[i] = (r * r).toFloat() - 1f
        }
        xs[10] = 200f; ys[10] = 170f
        xs[152] = 200f; ys[152] = 430f
        xs[234] = 100f; ys[234] = 300f
        xs[454] = 300f; ys[454] = 300f
        return Triple(xs, ys, zs)
    }

    @Test fun `face lifts the field, far from the head stays flat`() {
        val g = DepthGrid()
        g.resize(400f, 800f)
        assertEquals(192, g.rows)
        val (xs, ys, zs) = face()
        g.update(xs, ys, zs, 478, nowMs = 0)
        g.settle()
        assertEquals(260f, g.faceHeight, 0.5f)
        val centre = g.sample(200f, 300f)
        val cheek = g.sample(290f, 300f)
        assertTrue("centre $centre", centre > 0.8f)
        assertTrue("cheek $cheek < centre", cheek < centre)
        // The head dome lifts the hair above the forehead too.
        assertTrue("above forehead ${g.sample(200f, 150f)}", g.sample(200f, 150f) > 0.1f)
        assertEquals(0f, g.sample(20f, 760f), 1e-4f)
    }

    @Test fun `field eases toward a new frame`() {
        val g = DepthGrid()
        g.resize(400f, 800f)
        val (xs, ys, zs) = face()
        g.update(xs, ys, zs, 478, nowMs = 0)
        g.settle()
        val before = g.sample(200f, 300f)
        // The face moves away: the old spot should fall, but only part of the way per frame.
        for (i in 0 until 478) xs[i] += 150f
        xs[10] += 0f
        g.update(xs, ys, zs, 478, nowMs = 33)
        assertTrue(g.step())
        val after = g.sample(200f, 300f)
        assertTrue("$before -> $after", after < before && after > before * 0.5f)
    }

    @Test fun `a dropped face is held briefly, then fades`() {
        val g = DepthGrid()
        g.resize(400f, 800f)
        val (xs, ys, zs) = face()
        g.update(xs, ys, zs, 478, nowMs = 0)
        g.settle()
        g.noFace(200)
        g.step()
        assertTrue(g.sample(200f, 300f) > 0.8f)
        g.noFace(400)
        repeat(30) { g.step() }
        assertTrue(g.sample(200f, 300f) < 0.05f)
    }
}

class ScanColorsTest {

    @Test fun `defaults without an appearance`() {
        assertEquals(ScanColors.DEFAULT, ScanColors.resolve(null))
        assertEquals(ScanColors.DEFAULT, ScanColors.resolve(FlowAppearance()))
        assertEquals(0xFF4F7CFF.toInt(), ScanColors.DEFAULT.ring)
        assertEquals(0xFF7A96FF.toInt(), ScanColors.DEFAULT.contourStart)
        assertEquals(0xFFA878FC.toInt(), ScanColors.DEFAULT.contourEnd)
        assertEquals(0xFFFFB84D.toInt(), ScanColors.DEFAULT.warning)
    }

    @Test fun `sense defaults clear the 3 to 1 bar`() {
        listOf(ScanColors.DEFAULT_RING, ScanColors.DEFAULT_CONTOUR_START, ScanColors.DEFAULT_CONTOUR_END, ScanColors.DEFAULT_WARNING)
            .forEach { assertTrue(ScanColors.contrast(it, ScanColors.CAMERA_BACKDROP) >= 3.0) }
    }

    @Test fun `readable custom colours are used, dark ones fall back`() {
        val c = ScanColors.resolve(
            FlowAppearance(capture = AppearanceCapture(ring = "#22D3EE", contourStart = "#1E3A8A", contourEnd = "fff", warning = "nope")),
        )
        assertEquals(0xFF22D3EE.toInt(), c.ring)
        assertEquals(ScanColors.DEFAULT_CONTOUR_START, c.contourStart)
        assertEquals(0xFFFFFFFF.toInt(), c.contourEnd)
        assertEquals(ScanColors.DEFAULT_WARNING, c.warning)
    }

    @Test fun `hex parsing`() {
        assertEquals(0xFF112233.toInt(), ScanColors.parseHex("#112233"))
        assertEquals(0xFF112233.toInt(), ScanColors.parseHex("80112233"))
        assertEquals(0xFFAABBCC.toInt(), ScanColors.parseHex("#abc"))
        assertNull(ScanColors.parseHex(""))
        assertNull(ScanColors.parseHex("#12345"))
    }

    @Test fun `capture colours decode from the wire and merge code over server`() {
        val server = FlowAppearance.decode(JSONObject("""{"capture":{"ring":"#111111","contour_start":"#7A96FF","contourEnd":"#A878FC"}}"""))
        assertEquals("#7A96FF", server.capture?.contourStart)
        assertEquals("#A878FC", server.capture?.contourEnd)
        val merged = mergeAppearance(FlowAppearance(capture = AppearanceCapture(ring = "#22D3EE")), server)
        assertEquals("#22D3EE", merged?.capture?.ring)
        assertEquals("#7A96FF", merged?.capture?.contourStart)
    }
}

class PreviewMapperTest {

    @Test fun `fill-centre crop and mirror`() {
        // 480x640 image into a 300x600 view: scale = max(0.625, 0.9375) = 0.9375,
        // displayed 450x600, 75 px cropped each side.
        val m = PreviewMapper(480, 640, 300f, 600f, mirror = true)
        val out = FloatArray(2)
        m.map(240f, 320f, out)
        assertEquals(150f, out[0], 0.01f)
        assertEquals(300f, out[1], 0.01f)
        m.map(80f, 0f, out)
        // 80 * 0.9375 - 75 = 0 -> mirrored to the right edge.
        assertEquals(300f, out[0], 0.01f)
        assertEquals(0f, out[1], 0.01f)
    }
}

class FaceRegionTest {

    @Test fun `face box is measured, not the whole frame`() {
        // Bright frame, dark face box in the middle.
        val w = 100; val h = 100
        val argb = IntArray(w * h) { 0xFFF0F0F0.toInt() }
        for (y in 30 until 70) for (x in 30 until 70) argb[y * w + x] = 0xFF282828.toInt()
        val r = ImageQualityAnalyzer().analyzeFaceRegion(argb, w, h, 30, 30, 70, 70)
        assertNotNull(r)
        assertEquals(40.0, r!!.meanLuma, 1.0)
        assertEquals(0.0, r.clippedRatio, 0.0)
        assertTrue(r.sharpness < 1.0)
    }

    @Test fun `clipped highlights are counted`() {
        val w = 96; val h = 96
        val argb = IntArray(w * h) { i -> if (i % 4 == 0) 0xFFFFFFFF.toInt() else 0xFF808080.toInt() }
        val r = ImageQualityAnalyzer().analyzeFaceRegion(argb, w, h, 0, 0, w, h)!!
        assertEquals(0.25, r.clippedRatio, 0.02)
        assertEquals(r.clippedRatio, r.highlightRatio, 0.0)
        assertTrue(r.sharpness > 100)
    }

    @Test fun `empty box gives nothing`() {
        assertNull(ImageQualityAnalyzer().analyzeFaceRegion(IntArray(100), 10, 10, 5, 5, 6, 6))
    }
}
