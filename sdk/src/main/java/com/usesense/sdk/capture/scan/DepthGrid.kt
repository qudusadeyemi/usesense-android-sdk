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

/**
 * One frame's relief: the raw field (rows x cols, 0..~1), the face height in
 * screen px and the face centre (midpoint of forehead 10 and chin 152), which
 * the lines bulge away from.
 */
internal class Relief(
    val cols: Int,
    val rows: Int,
    val raw: FloatArray,
    val faceHeight: Float,
    val centreX: Float = 0f,
    val centreY: Float = 0f,
    /** Bump half-height in screen px (scales the bulge). */
    val bumpRy: Float = 0f,
)

/**
 * Contour relief v3 prototype (Revolut-style bulge; v2 in RELIEF-v2.md).
 * Display only: MediaPipe z is a prediction from one flat image and proves
 * nothing about depth.
 *
 *  1. Depth per landmark relative to the face outline: the FACE_OVAL ring is 0,
 *     the nearest point (the nose tip) about 1.
 *  2. The 852 mesh triangles rasterised into a [COLS]-column grid with
 *     barycentric depth; overlaps keep the larger value.
 *  3. Eye and mouth openings filled by normalised convolution, then a soft
 *     edge into the head dome.
 *  4. v3: the smooth head dome carries the shape; the mesh is low-passed
 *     ([MESH_LOWPASS_SIGMA]) and only adds a hint of nose and brow:
 *     raw = 1.0 x dome + 0.25 x lowpass(mesh), blurred 0.9 cells.
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
        // Blur sizes are specified in 130-column cells; keep them the same on screen.
        val k = cols / 130f

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
        val num = gaussBlur(mc, cols, rows, FILL_SIGMA * k)
        val den2 = gaussBlur(cover, cols, rows, FILL_SIGMA * k)
        val soft = gaussBlur(cover, cols, rows, EDGE_SIGMA * k)
        for (i in 0 until n) {
            val filled = if (cover[i] != 0f) mesh[i] else if (den2[i] > 1e-3f) num[i] / den2[i] else 0f
            mesh[i] = filled * smoothstep(EDGE_LO, EDGE_HI, soft[i])
        }

        // 4. Combine: a rounded bump just wider than the face, (1 - r^2)^2 inside
        // an ellipse: every line bows smoothly, the bend stops at the sides of
        // the head (flat lines beyond, as in Revolut) and the edge has zero
        // slope, so nothing piles up into a rim. A low-passed hint of the face
        // sits on top.
        val meshLow = gaussBlur(mesh, cols, rows, MESH_LOWPASS_SIGMA * k)
        val bcy = (ty + by) / 2f + BUMP_CY_SHIFT * faceH
        val bRx = max(1f, BUMP_RX * faceW)
        val bRy = max(1f, BUMP_RY * faceH)
        val raw = FloatArray(n)
        for (y in 0 until rows) {
            val ny = ((y + 0.5f) * cell - bcy) / bRy
            for (x in 0 until cols) {
                val nx = ((x + 0.5f) * cell - dcx) / bRx
                val r2 = nx * nx + ny * ny
                val bump = if (r2 < 1f) (1f - r2) * (1f - r2) else 0f
                val i = y * cols + x
                raw[i] = bump * DOME_HEIGHT + meshLow[i] * MESH_HEIGHT
            }
        }
        return Relief(cols, rows, gaussBlur(raw, cols, rows, max(0.3f, BLUR_SIGMA * k)), faceH, dcx, bcy, bRy)
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
    // v3 prototype: the head shape carries the relief, fine mesh detail is a hint.
    const val DOME_HEIGHT = 1.0f
    const val MESH_HEIGHT = 0.15f
    const val MESH_LOWPASS_SIGMA = 4.0f
    /**
     * v3 bump: half-axes in face widths / heights, centred a little below the
     * face's middle (as Revolut). With a bulge up to 1.25 the line map stays
     * monotonic for this profile: 1 + A(1 - v)(1 - 5v) > 0 for v in 0..1.
     */
    const val BUMP_RX = 0.58f
    const val BUMP_RY = 0.65f
    const val BUMP_CY_SHIFT = 0.05f
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
    /** Face centre in screen px, eased like the field. */
    var centreY = 0f; private set
    private var targetCentreY = 0f
    /** Bump half-height in screen px, eased like the field. */
    var bumpRy = 0f; private set
    private var targetBumpRy = 0f
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
        targetCentreY = relief.centreY
        targetBumpRy = relief.bumpRy
        if (!hasFace) {
            System.arraycopy(target, 0, field, 0, target.size)
            centreY = targetCentreY
            bumpRy = targetBumpRy
        }
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
        val dc = targetCentreY - centreY
        if (dc > 0.5f || dc < -0.5f) moving = true
        centreY += dc * alpha
        bumpRy += (targetBumpRy - bumpRy) * alpha
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
        centreY = targetCentreY
        bumpRy = targetBumpRy
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
        /** v3: the field is smooth, so a coarse grid is enough (v2 used 130). */
        const val COLS = 64
        const val EMA_ALPHA = 0.30f
        const val HOLD_MS = 300L
    }
}
