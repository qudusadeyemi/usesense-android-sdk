package com.usesense.sdk.capture.scan

import com.usesense.sdk.capture.ImageQualityAnalyzer
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
     * A synthetic 478-point face in a 200x260 px ellipse centred at (200, 300):
     * the FACE_OVAL ring on the outline at z 0, the rest inside with z = r^2 - 1,
     * so depth runs from 0 at the outline to 1 at the centre. Forehead (10),
     * chin (152) and cheeks (234, 454) are ring points.
     */
    private fun face(): Triple<FloatArray, FloatArray, FloatArray> {
        val xs = FloatArray(478); val ys = FloatArray(478); val zs = FloatArray(478)
        for (i in 0 until 478) {
            val a = i * 2.399963
            val r = kotlin.math.sqrt((i + 0.5) / 478.0) * 0.95
            xs[i] = 200f + (100 * r * cos(a)).toFloat()
            ys[i] = 300f + (130 * r * sin(a)).toFloat()
            zs[i] = (r * r).toFloat() - 1f
        }
        val ring = QualityGate.FACE_OVAL
        ring.forEachIndexed { k, idx ->
            val a = -Math.PI / 2 + 2 * Math.PI * k / ring.size
            xs[idx] = 200f + (100 * cos(a)).toFloat()
            ys[idx] = 300f + (130 * sin(a)).toFloat()
            zs[idx] = 0f
        }
        return Triple(xs, ys, zs)
    }

    @Test fun `tessellation is 852 triangles of mesh points`() {
        assertEquals(FaceTessellation.TRIANGLES * 3, FaceTessellation.INDICES.size)
        assertTrue(FaceTessellation.INDICES.all { it in 0 until 468 })
    }

    @Test fun `face lifts the field, far from the head stays flat`() {
        val g = DepthGrid()
        g.resize(400f, 800f)
        assertEquals(128, g.rows)
        val (xs, ys, zs) = face()
        g.update(xs, ys, zs, 478, nowMs = 0)
        g.settle()
        assertEquals(260f, g.faceHeight, 0.5f)
        val centre = g.sample(200f, 300f)
        val cheek = g.sample(285f, 300f)
        assertTrue("centre $centre", centre > 0.8f)
        assertTrue("cheek $cheek < centre", cheek < centre)
        // The bump reaches a little past the forehead (y 170)...
        assertTrue("near forehead ${g.sample(200f, 180f)}", g.sample(200f, 180f) > 0.05f)
        // ...and stops at the sides of the head (flat lines beyond).
        assertEquals(0f, g.sample(20f, 300f), 0.02f)
        assertEquals(0f, g.sample(20f, 760f), 1e-4f)
    }

    @Test fun `the detail field follows the face, highest at the nose`() {
        val g = DepthGrid()
        g.resize(400f, 800f)
        val (xs, ys, zs) = face()
        g.update(xs, ys, zs, 478, nowMs = 0)
        g.settle()
        val nose = g.sampleDetail(200f, 300f)
        val cheek = g.sampleDetail(280f, 300f)
        assertTrue("nose $nose", nose > 0.6f)
        assertTrue("cheek $cheek < nose", cheek < nose)
        assertEquals(0f, g.sampleDetail(20f, 760f), 1e-4f)
    }

    @Test fun `depth is measured from the outline`() {
        val (xs, ys, zs) = face()
        // Shifting every z by a constant changes nothing.
        val a = ReliefBuilder.build(xs, ys, zs, 478, 400f, 800f)!!
        val b = ReliefBuilder.build(xs, ys, FloatArray(478) { zs[it] + 5f }, 478, 400f, 800f)!!
        for (i in a.raw.indices) assertEquals(a.raw[i], b.raw[i], 1e-4f)
    }

    @Test fun `blur keeps a constant field constant`() {
        val src = FloatArray(30 * 20) { 0.4f }
        val out = ReliefBuilder.gaussBlur(src, 30, 20, 2f)
        assertTrue(out.all { kotlin.math.abs(it - 0.4f) < 1e-5f })
    }

    @Test fun `too few points build nothing`() {
        val (xs, ys, zs) = face()
        assertNull(ReliefBuilder.build(xs, ys, zs, 100, 400f, 800f))
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
