package com.usesense.sdk.capture.scan

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One frame's relief: the raw field (rows x cols, 0..~1) and the face height in screen px. */
internal class Relief(val cols: Int, val rows: Int, val raw: FloatArray, val faceHeight: Float)

/**
 * Contour relief v2 (capture-test-kit RELIEF-v2.md; a port of `updateField`
 * in reference/index.html). Display only: MediaPipe z is a prediction from one
 * flat image and proves nothing about depth.
 *
 *  1. Depth per landmark relative to the face outline: the FACE_OVAL ring is 0,
 *     the nearest point (the nose tip) about 1.
 *  2. The 852 mesh triangles rasterised into a [COLS]-column grid with
 *     barycentric depth; overlaps keep the larger value.
 *  3. Eye and mouth openings filled by normalised convolution, then a soft
 *     edge into the head dome.
 *  4. raw = 0.50 x dome + 0.70 x mesh, blurred 0.9 cells.
 *
 * Pure and allocation-per-call, so it runs on the gate thread; the view only
 * eases toward the result ([DepthGrid]).
 */
internal object ReliefBuilder {

    /**
     * @param xs, ys landmarks in screen px (mirrored aspect-fill, as the preview)
     * @param zs MediaPipe z (any scale: depth is normalised per frame)
     */
    fun build(xs: FloatArray, ys: FloatArray, zs: FloatArray, count: Int, width: Float, height: Float, cols: Int = DepthGrid.COLS): Relief? {
        if (count < MIN_POINTS || width <= 0f || height <= 0f) return null
        val rows = max(1, (cols * height / width).roundToInt())
        val n = rows * cols
        val cell = width / cols

        // 1. Depth relative to the outline.
        var zMin = Float.MAX_VALUE
        for (i in 0 until MESH_POINTS) zMin = min(zMin, zs[i])
        var zEdge = 0f
        for (k in QualityGate.FACE_OVAL) zEdge += zs[k]
        zEdge /= QualityGate.FACE_OVAL.size
        val range = max(1e-6f, zEdge - zMin)
        val gx = FloatArray(count)
        val gy = FloatArray(count)
        val gd = FloatArray(count)
        for (i in 0 until count) {
            gx[i] = xs[i] / cell
            gy[i] = ys[i] / cell
            gd[i] = max(0f, (zEdge - zs[i]) / range)
        }

        // 2. Rasterise the triangles.
        val mesh = FloatArray(n)
        val cover = FloatArray(n)
        val tri = FaceTessellation.INDICES
        var t = 0
        while (t + 2 < tri.size) {
            val ia = tri[t]; val ib = tri[t + 1]; val ic = tri[t + 2]
            t += 3
            if (ia >= count || ib >= count || ic >= count) continue
            val ax = gx[ia]; val ay = gy[ia]; val bx = gx[ib]; val by = gy[ib]; val cx = gx[ic]; val cy = gy[ic]
            val x0 = max(0, floor(min(ax, min(bx, cx))).toInt())
            val x1 = min(cols - 1, ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(0, floor(min(ay, min(by, cy))).toInt())
            val y1 = min(rows - 1, ceil(max(ay, max(by, cy))).toInt())
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9f) continue
            for (y in y0..y1) {
                val py = y + 0.5f
                for (x in x0..x1) {
                    val px = x + 0.5f
                    val w1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den
                    val w2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den
                    val w3 = 1f - w1 - w2
                    if (w1 < -BARY_SLACK || w2 < -BARY_SLACK || w3 < -BARY_SLACK) continue
                    val d = w1 * gd[ia] + w2 * gd[ib] + w3 * gd[ic]
                    val i = y * cols + x
                    if (d > mesh[i] || cover[i] == 0f) mesh[i] = d
                    cover[i] = 1f
                }
            }
        }

        // Head dome for the hair and the head outline (unit height).
        val tx = xs[10]; val ty = ys[10]; val bx = xs[152]; val by = ys[152]
        val faceH = hypot(bx - tx, by - ty)
        val faceW = abs(xs[454] - xs[234])
        val dcx = (tx + bx) / 2f
        val dcy = (ty + by) / 2f + DOME_CY_SHIFT * faceH
        val drx = max(1f, DOME_RX * faceW)
        val dry = max(1f, DOME_RY * faceH)

        // 3. Fill the openings, then a soft edge.
        val mc = FloatArray(n)
        for (i in 0 until n) mc[i] = mesh[i] * cover[i]
        val num = gaussBlur(mc, cols, rows, FILL_SIGMA)
        val den2 = gaussBlur(cover, cols, rows, FILL_SIGMA)
        val soft = gaussBlur(cover, cols, rows, EDGE_SIGMA)
        for (i in 0 until n) {
            val filled = if (cover[i] != 0f) mesh[i] else if (den2[i] > 1e-3f) num[i] / den2[i] else 0f
            mesh[i] = filled * smoothstep(EDGE_LO, EDGE_HI, soft[i])
        }

        // 4. Combine.
        val raw = FloatArray(n)
        for (y in 0 until rows) {
            val sy = (y + 0.5f) * cell
            val ny = (sy - dcy) / dry
            for (x in 0 until cols) {
                val sx = (x + 0.5f) * cell
                val nx = (sx - dcx) / drx
                val r2 = nx * nx + ny * ny
                val dome = if (r2 < 1f) {
                    (1f - r2).toDouble().pow(0.75).toFloat() * smoothstep(1f, 1f - DOME_FEATHER, sqrt(r2))
                } else {
                    0f
                }
                val i = y * cols + x
                raw[i] = dome * DOME_HEIGHT + mesh[i] * MESH_HEIGHT
            }
        }
        return Relief(cols, rows, gaussBlur(raw, cols, rows, BLUR_SIGMA), faceH)
    }

    /** Separable Gaussian, radius ceil(2.5 sigma), clamped at the edges. */
    fun gaussBlur(src: FloatArray, cols: Int, rows: Int, sigma: Float): FloatArray {
        val r = ceil(sigma * 2.5f).toInt()
        val k = FloatArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2f * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until rows) {
            val row = y * cols
            for (x in 0 until cols) {
                var a = 0f
                for (i in -r..r) a += src[row + (x + i).coerceIn(0, cols - 1)] * k[i + r]
                tmp[row + x] = a
            }
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            var a = 0f
            for (i in -r..r) a += tmp[(y + i).coerceIn(0, rows - 1) * cols + x] * k[i + r]
            out[y * cols + x] = a
        }
        return out
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    const val MESH_POINTS = 468
    const val MIN_POINTS = 468
    const val BARY_SLACK = 0.02f
    const val FILL_SIGMA = 3.0f
    const val EDGE_SIGMA = 2.0f
    const val EDGE_LO = 0.15f
    const val EDGE_HI = 0.95f
    const val BLUR_SIGMA = 0.9f
    const val DOME_HEIGHT = 0.50f
    const val MESH_HEIGHT = 0.70f
    const val DOME_RX = 0.62f
    const val DOME_RY = 0.78f
    const val DOME_CY_SHIFT = -0.12f
    const val DOME_FEATHER = 0.18f
}

/**
 * The contour scan's depth field as the view draws it: the latest [Relief]
 * eased in with an EMA (alpha 0.30) per display frame, held [HOLD_MS] when
 * the face drops, then faded out.
 */
internal class DepthGrid(val cols: Int = COLS) {
    var rows = 0; private set
    private var width = 0f
    private var cell = 1f

    /** Smoothed field, 0..~1 per cell (rows x cols). */
    var field = FloatArray(0); private set
    private var target = FloatArray(0)

    /** Forehead-to-chin distance in screen px of the latest face (sets the line lift). */
    var faceHeight = 0f; private set
    var hasFace = false; private set
    private var lastFaceAt = 0L

    /** One grid cell in screen px. */
    val cellSize: Float get() = cell

    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        width = w
        cell = w / cols
        rows = max(1, (cols * h / w).roundToInt())
        field = FloatArray(rows * cols)
        target = FloatArray(rows * cols)
        hasFace = false
    }

    /** Take a relief built for this grid's size (ignored if the size changed since). */
    fun apply(relief: Relief, nowMs: Long) {
        if (relief.cols != cols || relief.rows != rows) return
        System.arraycopy(relief.raw, 0, target, 0, target.size)
        faceHeight = relief.faceHeight
        if (!hasFace) System.arraycopy(target, 0, field, 0, target.size)
        hasFace = true
        lastFaceAt = nowMs
    }

    /** Build and apply on the calling thread (tests; the app builds on the gate thread). */
    fun update(xs: FloatArray, ys: FloatArray, zs: FloatArray, count: Int, nowMs: Long, height: Float = rows * cell) {
        if (rows == 0) return
        ReliefBuilder.build(xs, ys, zs, count, width, height, cols)?.let { apply(it, nowMs) }
    }

    /** The face dropped. The field is kept for [HOLD_MS], then fades out. */
    fun noFace(nowMs: Long) {
        if (hasFace && nowMs - lastFaceAt > HOLD_MS) target.fill(0f)
    }

    /** One display frame of temporal smoothing. Returns true while still moving. */
    fun step(alpha: Float = EMA_ALPHA): Boolean {
        var moving = false
        for (i in field.indices) {
            val d = target[i] - field[i]
            if (d > 0.002f || d < -0.002f) moving = true
            field[i] += d * alpha
        }
        return moving
    }

    /** Converge on the current target at once (frozen-frame screenshots). */
    fun settle() {
        System.arraycopy(target, 0, field, 0, field.size)
    }

    /** Bilinear sample at screen px (cell i at i x cell, as the reference). */
    fun sample(x: Float, y: Float): Float {
        if (rows == 0) return 0f
        val gx = x / cell
        val gy = y / cell
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val fx = gx - x0
        val fy = gy - y0
        return at(x0, y0) * (1 - fx) * (1 - fy) + at(x0 + 1, y0) * fx * (1 - fy) +
            at(x0, y0 + 1) * (1 - fx) * fy + at(x0 + 1, y0 + 1) * fx * fy
    }

    private fun at(x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= cols || y >= rows) 0f else field[y * cols + x]

    companion object {
        const val COLS = 130
        const val EMA_ALPHA = 0.30f
        const val HOLD_MS = 300L
    }
}
