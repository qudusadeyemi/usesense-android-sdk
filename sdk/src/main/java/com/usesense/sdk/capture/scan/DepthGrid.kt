package com.usesense.sdk.capture.scan

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The contour scan's depth field, in screen space. Display only: MediaPipe z
 * is a prediction from one flat image and proves nothing about depth.
 *
 * Follows the shared visual spec (capture-test-kit/SPEC.md, "Depth field"),
 * so the three SDKs draw the same surface:
 *  1. Mesh detail: each landmark's normalised depth (nose tip 1, face edge 0)
 *     Gaussian-splatted into a [COLS]-column grid, feathered by coverage,
 *     then blurred.
 *  2. Head dome: an ellipsoid fitted to forehead, chin and cheeks, so the
 *     lines lift over the hair and the whole head, not just the mask of the face.
 *  3. field = min(1, dome + 0.80 x mesh), smoothed over time ([step]).
 */
internal class DepthGrid(val cols: Int = COLS) {
    var rows = 0; private set
    private var width = 0f
    private var height = 0f
    private var cell = 1f

    /** Smoothed field, 0..1 per cell (rows x cols). */
    var field = FloatArray(0); private set
    private var target = FloatArray(0)
    private var acc = FloatArray(0)
    private var wsum = FloatArray(0)
    private var tmp = FloatArray(0)

    /** Forehead-to-chin distance in screen px of the latest face (sets the line lift). */
    var faceHeight = 0f; private set
    var hasFace = false; private set
    private var lastFaceAt = 0L

    /** Size the grid to the screen. Rows follow the aspect. */
    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        width = w
        height = h
        cell = w / cols
        rows = max(1, (cols * h / w).roundToInt())
        val n = rows * cols
        field = FloatArray(n); target = FloatArray(n)
        acc = FloatArray(n); wsum = FloatArray(n); tmp = FloatArray(n)
        hasFace = false
    }

    /**
     * Build the target field from [count] landmarks in screen px ([xs], [ys])
     * with MediaPipe z ([zs], smaller is nearer). Indices follow the 478-point mesh.
     */
    fun update(xs: FloatArray, ys: FloatArray, zs: FloatArray, count: Int, nowMs: Long) {
        if (rows == 0 || count < MIN_POINTS) return
        val n = rows * cols
        acc.fill(0f); wsum.fill(0f)
        var zMin = Float.MAX_VALUE; var zMax = -Float.MAX_VALUE
        for (i in 0 until count) { zMin = min(zMin, zs[i]); zMax = max(zMax, zs[i]) }
        val zRange = max(1e-6f, zMax - zMin)
        val sig = SPLAT_SIGMA
        val r = ceil(sig * 2.5f).toInt()
        val inv2s2 = 1f / (2f * sig * sig)
        for (i in 0 until count) {
            val d = (zMax - zs[i]) / zRange
            val gx = xs[i] / cell
            val gy = ys[i] / cell
            val ix = gx.roundToInt()
            val iy = gy.roundToInt()
            for (y in (iy - r)..(iy + r)) {
                if (y < 0 || y >= rows) continue
                val dy = y - gy
                for (x in (ix - r)..(ix + r)) {
                    if (x < 0 || x >= cols) continue
                    val dx = x - gx
                    val w = exp(-(dx * dx + dy * dy) * inv2s2)
                    acc[y * cols + x] += w * d
                    wsum[y * cols + x] += w
                }
            }
        }
        for (i in 0 until n) {
            val ws = wsum[i]
            target[i] = if (ws > 1e-6f) acc[i] / ws * smoothstep(0f, FEATHER, ws) else 0f
        }
        blur(target, BLUR_SIGMA)

        // Head dome from forehead (10), chin (152), cheeks (234, 454).
        val tx = xs[10]; val ty = ys[10]; val bx = xs[152]; val by = ys[152]
        faceHeight = hypot(bx - tx, by - ty)
        val faceW = kotlin.math.abs(xs[454] - xs[234])
        val dcx = (tx + bx) / 2f
        val dcy = (ty + by) / 2f + DOME_CY_SHIFT * faceHeight
        val drx = max(1f, DOME_RX * faceW)
        val dry = max(1f, DOME_RY * faceHeight)
        for (gy in 0 until rows) {
            val y = (gy + 0.5f) * cell
            val ny = (y - dcy) / dry
            for (gx in 0 until cols) {
                val x = (gx + 0.5f) * cell
                val nx = (x - dcx) / drx
                val r2 = nx * nx + ny * ny
                val dome = if (r2 < 1f) {
                    (1f - r2).toDouble().pow(0.75).toFloat() * DOME_HEIGHT * smoothstep(1f, 1f - DOME_FEATHER, sqrt(r2))
                } else {
                    0f
                }
                val i = gy * cols + gx
                target[i] = min(1f, dome + target[i] * MESH_WEIGHT)
            }
        }
        if (!hasFace) System.arraycopy(target, 0, field, 0, n)
        hasFace = true
        lastFaceAt = nowMs
    }

    /** The face dropped. The field is kept for [HOLD_MS], then fades out. */
    fun noFace(nowMs: Long) {
        if (hasFace && nowMs - lastFaceAt > HOLD_MS) {
            target.fill(0f)
        }
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

    /** Bilinear sample at screen px. */
    fun sample(x: Float, y: Float): Float {
        if (rows == 0) return 0f
        // Cell i sits at i * cell, as the splat places it.
        val gx = x / cell
        val gy = y / cell
        val x0 = kotlin.math.floor(gx).toInt()
        val y0 = kotlin.math.floor(gy).toInt()
        val fx = gx - x0
        val fy = gy - y0
        return at(x0, y0) * (1 - fx) * (1 - fy) + at(x0 + 1, y0) * fx * (1 - fy) +
            at(x0, y0 + 1) * (1 - fx) * fy + at(x0 + 1, y0 + 1) * fx * fy
    }

    private fun at(x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= cols || y >= rows) 0f else field[y * cols + x]

    private fun blur(src: FloatArray, sigma: Float) {
        val r = ceil(sigma * 2.5f).toInt()
        val k = FloatArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        for (y in 0 until rows) for (x in 0 until cols) {
            var a = 0f
            for (i in -r..r) a += src[y * cols + (x + i).coerceIn(0, cols - 1)] * k[i + r]
            tmp[y * cols + x] = a
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            var a = 0f
            for (i in -r..r) a += tmp[(y + i).coerceIn(0, rows - 1) * cols + x] * k[i + r]
            src[y * cols + x] = a
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    companion object {
        const val COLS = 96
        const val SPLAT_SIGMA = 2.6f
        const val BLUR_SIGMA = 1.8f
        const val EMA_ALPHA = 0.30f
        const val FEATHER = 0.35f
        const val MESH_WEIGHT = 0.80f
        const val DOME_RX = 0.62f
        const val DOME_RY = 0.78f
        const val DOME_CY_SHIFT = -0.12f
        const val DOME_HEIGHT = 0.62f
        const val DOME_FEATHER = 0.18f
        const val HOLD_MS = 300L
        private const val MIN_POINTS = 468
    }
}
