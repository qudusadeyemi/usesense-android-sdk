package com.usesense.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.usesense.sdk.capture.scan.DepthGrid
import com.usesense.sdk.capture.scan.Relief
import com.usesense.sdk.capture.scan.OvalSpec
import com.usesense.sdk.capture.scan.ScanColors
import kotlin.math.max
import kotlin.math.min

/**
 * The capture guide over a full-bleed camera preview, drawn to the shared
 * visual spec (capture-test-kit/SPEC.md):
 *  - align: the oval guide, a thin stroke over a light fill; the stroke turns
 *    the warning colour while a check fails;
 *  - scan: the camera dimmed 12%, horizontal contour lines across the whole
 *    width lifted by the face relief (RELIEF-v2.md: mesh raster, lines that
 *    never cross, top-lit shading), and the progress ring 6 dp outside the oval;
 *  - fill light: a warm white veil over the whole screen while the screen lights the face.
 *
 * Canvas only. Units follow the spec's px as dp.
 */
internal class ContourScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Tone { NEUTRAL, WARNING, READY }

    private val dp = resources.displayMetrics.density

    var colors: ScanColors = ScanColors.DEFAULT
        set(value) { field = value; invalidate() }

    var tone: Tone = Tone.NEUTRAL
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Ring target, 0..1. The drawn ring eases toward it. */
    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); kick() }

    /** Paused: the ring's progress turns the warning colour. */
    var paused: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Scan mode: dim, contour lines and ring instead of the align guide. */
    var scanning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) sweepStart = SystemClock.uptimeMillis()
            kick()
        }

    /** Screen fill light: a warm white veil over the whole screen. */
    var fillLight: Boolean = false
        set(value) { if (field != value) { field = value; kick() } }

    /** Debug screenshots: lines fully swept in, field converged. */
    var frozen: Boolean = false

    val depthGrid = DepthGrid()

    private var shownProgress = 0f
    private var shownFill = 0f
    private var sweepStart = 0L
    private val ovalRect = RectF()
    private val ringRect = RectF()
    private val ringPath = Path()
    private val ringSegment = Path()
    private val ringMeasure = PathMeasure()

    private val guideFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
    private val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val dimPaint = Paint().apply { color = Color.argb(31, 13, 15, 20) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        color = Color.argb(46, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH_DP * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val fillLightPaint = Paint()

    /** Segment buffer for one line (x0, y0, x1, y1 ...). */
    private var segments = FloatArray(0)
    /** Per-sample y of the line above and of this line. */
    private var prevY = FloatArray(0)
    private var curY = FloatArray(0)

    /** View size for the gate thread, which builds the relief at this size. */
    @Volatile var reliefWidth = 0f; private set
    @Volatile var reliefHeight = 0f; private set

    /** The oval in view pixels. */
    fun ovalSpec(): OvalSpec? {
        if (width == 0 || height == 0) return null
        return OvalSpec(ovalRect.centerX(), ovalRect.centerY(), ovalRect.width() / 2f, ovalRect.height() / 2f)
    }

    /** A relief built off the main thread for the current size. */
    fun updateRelief(relief: Relief) {
        depthGrid.apply(relief, SystemClock.uptimeMillis())
        if (frozen) depthGrid.settle()
        kick()
    }

    fun clearFace() {
        depthGrid.noFace(SystemClock.uptimeMillis())
        kick()
    }

    fun resetProgress() {
        progress = 0f
        shownProgress = 0f
        invalidate()
    }

    private fun kick() = postInvalidateOnAnimation()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ovalRect.set(ovalFor(w.toFloat(), h.toFloat()))
        val gap = -RING_GAP_DP * dp
        ringRect.set(ovalRect)
        ringRect.inset(gap, gap)
        ringPath.reset()
        ringPath.addArc(ringRect, -90f, 359.9f) // from 12 o'clock, clockwise
        ringMeasure.setPath(ringPath, false)
        depthGrid.resize(w.toFloat(), h.toFloat())
        reliefWidth = w.toFloat()
        reliefHeight = h.toFloat()
        val samples = kotlin.math.ceil(w / (SAMPLE_STEP_DP * dp)).toInt() + 2
        segments = FloatArray(samples * 4)
        prevY = FloatArray(samples); curY = FloatArray(samples)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        var animating = false

        val dProgress = progress - shownProgress
        if (kotlin.math.abs(dProgress) > 0.002f) {
            shownProgress += dProgress * 0.25f
            animating = true
        } else {
            shownProgress = progress
        }
        val fillTarget = if (fillLight) 1f else 0f
        if (kotlin.math.abs(fillTarget - shownFill) > 0.01f) {
            shownFill += (fillTarget - shownFill) * 0.15f
            animating = true
        } else {
            shownFill = fillTarget
        }

        // Fill light: a warm white veil over the whole screen, under the guide,
        // with the screen at full brightness. A border made the camera look inset.
        if (shownFill > 0.01f) {
            fillLightPaint.color = Color.argb((shownFill * FILL_LIGHT_ALPHA * 255).toInt(), 255, 244, 226)
            canvas.drawRect(0f, 0f, w, h, fillLightPaint)
        }

        if (scanning) {
            canvas.drawRect(0f, 0f, w, h, dimPaint)
            if (depthGrid.hasFace) {
                if (!frozen && depthGrid.step()) animating = true
                if (drawLines(canvas, w, h)) animating = true
            }
            drawRing(canvas)
        } else {
            canvas.drawOval(ovalRect, guideFill)
            guideStroke.color = when (tone) {
                Tone.WARNING -> colors.warning
                Tone.READY -> colors.ring
                Tone.NEUTRAL -> Color.argb(230, 255, 255, 255)
            }
            canvas.drawOval(ovalRect, guideStroke)
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawRing(canvas: Canvas) {
        canvas.drawPath(ringPath, trackPaint)
        if (shownProgress > 0.003f) {
            ringSegment.reset()
            ringMeasure.getSegment(0f, ringMeasure.length * shownProgress, ringSegment, true)
            ringPaint.color = if (paused) colors.warning else colors.ring
            canvas.drawPath(ringSegment, ringPaint)
        }
    }

    /**
     * Contour lines, edge to edge. v3 prototype (Revolut-style bulge): each
     * line moves away from the face centre in proportion to the field,
     *   y' = y + [BULGE] x f(x, y) x (y - centreY),
     * so lines arch up over the forehead and head and sag down over the mouth
     * and chin, the way a rounded object bends a grid. The map is monotonic for
     * this field, so lines keep their order; [MIN_GAP_DP] is only a safety net.
     * One colour, even alpha, no shading. Returns true while the sweep runs.
     */
    private fun drawLines(canvas: Canvas, w: Float, h: Float): Boolean {
        val spacing = LINE_SPACING_DP * dp
        val step = SAMPLE_STEP_DP * dp
        val minGap = MIN_GAP_DP * dp
        val bottom = h * LINES_BOTTOM
        val fadeStart = bottom - h * LINES_FADE
        val count = (bottom / spacing).toInt()
        val yc = depthGrid.centreY
        val ry = depthGrid.bumpRy
        val knee = max(1f, BULGE_KNEE * ry)
        val nS = prevY.size
        val elapsed = if (frozen) Long.MAX_VALUE / 2 else SystemClock.uptimeMillis() - sweepStart
        var sweeping = false
        val colour = colors.ring
        val seg = segments
        prevY.fill(-1e9f)

        for (k in 0..count) {
            val y = k * spacing
            for (j in 0 until nS) {
                val x = j * step
                val d = depthGrid.sample(x, y)
                // Away from the centre, levelling off within [BULGE_KNEE] of the
                // bump height: lines above the nose already arch almost fully.
                val t = kotlin.math.tanh((y - yc) / knee)
                val amp = if (t < 0f) BULGE_UP else BULGE_DOWN
                // v3.1: plus a direct lift by the face's own shape (not scaled by
                // distance from the centre), so the nose, brow and lips show.
                val lift = DETAIL_LIFT * ry * depthGrid.sampleDetail(x, y)
                curY[j] = maxOf(y + amp * ry * d * t - lift, prevY[j] + minGap)
            }
            val swap = prevY; prevY = curY; curY = swap
            val appearAt = k.toFloat() / count * SWEEP_MS
            val a0 = ((elapsed - appearAt) / LINE_FADE_MS).coerceIn(0f, 1f)
            if (a0 < 1f) sweeping = true
            if (a0 <= 0f) continue
            val edgeFade = if (y > fadeStart) 1f - (y - fadeStart) / (bottom - fadeStart) else 1f
            var len = 0
            for (j in 1 until nS) {
                if (len + 4 > seg.size) break
                seg[len] = (j - 1) * step; seg[len + 1] = prevY[j - 1]; seg[len + 2] = j * step; seg[len + 3] = prevY[j]
                len += 4
            }
            linePaint.color = ColorUtils.setAlphaComponent(colour, (LINE_ALPHA * a0 * edgeFade * 255).toInt().coerceIn(0, 255))
            canvas.drawLines(seg, 0, len, linePaint)
        }
        return sweeping
    }

    companion object {
        // Shared visual spec (capture-test-kit/SPEC.md). Lengths are dp.
        const val OVAL_CX = 0.5f
        const val OVAL_CY = 0.40f
        const val OVAL_RX_OF_W = 0.36f
        const val OVAL_RY_OF_H = 0.235f
        const val RING_GAP_DP = 6f
        const val LINE_SPACING_DP = 9f
        const val SAMPLE_STEP_DP = 3f
        const val LINES_BOTTOM = 0.84f
        const val LINES_FADE = 0.14f
        /**
         * v3: how far lines move away from the face centre, in bump heights per
         * unit field: more upward over the forehead and head than downward
         * under the chin, as Revolut. Below ~0.65 the map stays monotonic for
         * the (1 - r^2)^2 bump.
         */
        const val BULGE_UP = 0.50f
        const val BULGE_DOWN = 0.32f
        /** v3.1: lift by the face's own shape, in bump heights (nose ~1). */
        const val DETAIL_LIFT = 0.12f
        /** Distance from the centre, in bump heights, over which the bulge levels off. */
        const val BULGE_KNEE = 0.25f
        /** Safety net only: the bulge keeps lines in order by itself. */
        const val MIN_GAP_DP = 2.5f
        /** v3: one colour at an even alpha. */
        const val LINE_ALPHA = 0.90f
        const val LINE_WIDTH_DP = 1.3f
        const val SWEEP_MS = 520f
        const val LINE_FADE_MS = 140f
        /** Veil opacity: lights the face while the camera still shows through. */
        const val FILL_LIGHT_ALPHA = 0.38f

        fun ovalFor(w: Float, h: Float): RectF {
            val cx = w * OVAL_CX
            val cy = h * OVAL_CY
            val rx = w * OVAL_RX_OF_W
            val ry = h * OVAL_RY_OF_H
            return RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        }
    }
}
