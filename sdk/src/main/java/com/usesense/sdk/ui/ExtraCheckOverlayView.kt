package com.usesense.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.usesense.sdk.ui.theme.CaptureColor
import com.usesense.sdk.ui.theme.CaptureFixed
import com.usesense.sdk.ui.theme.CaptureTheme
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The extra check drawn over the scan screen (storyboard "End-user
 * storyboard", screens head_*, dot_*, digits*, xc_done): the side arrow for
 * Head Turn, the amber dot and its dashed trail for Follow Dot, the digits
 * pill and live meter for Speak Digits, and the green tick when it's done.
 * The ring and the dimmed camera are [ContourScanView]'s.
 *
 * Canvas only; storyboard px are dp. Geometry follows the oval of
 * [ContourScanView.ovalFor].
 */
internal class ExtraCheckOverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        enum class Arrow { LEFT, RIGHT, UP, DOWN }

        private val dp = resources.displayMetrics.density
        private val oval = RectF()

        var arrow: Arrow? = null
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        /** Follow Dot: the dot is shown. Set its target with [moveDot]. */
        var dotVisible: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        /** Speak Digits: the text in the pill (null hides the pill and the meter). */
        var digits: String? = null
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        /** Speak Digits: meter flat (we can't hear you). */
        var quiet: Boolean = false

        /** The green tick in the oval ("Done"). */
        var tick: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                tickStart = SystemClock.uptimeMillis()
                postInvalidateOnAnimation()
            }

        /** Top of the screen's content below the status bar and the top bar, px. */
        var topInset: Float = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        /** Debug screenshots: the dot jumps to its target, the trail is set by [setTrail]. */
        var frozen: Boolean = false

        private var dotX = Float.NaN
        private var dotY = Float.NaN
        private var fromX = 0f
        private var fromY = 0f
        private var toX = 0f
        private var toY = 0f
        private var moveStart = 0L
        private val trail = ArrayList<Float>()
        private val levels = FloatArray(METER_BARS)
        private var tickStart = 0L

        private val arrowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f * dp
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = CaptureFixed.GUIDANCE_TEXT
                setShadowLayer(6f * dp, 0f, 1f * dp, CaptureFixed.ARROW_SHADOW)
            }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val haloPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * dp
            }
        private val trailPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * dp
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(4f * dp, 6f * dp), 0f)
            }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CaptureFixed.DIGITS_PILL }
        private val digitsPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CaptureFixed.DIGITS_TEXT
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
        private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tickFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tickStroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f * dp
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = CaptureFixed.GUIDANCE_TEXT
            }
        private val path = Path()

        /**
         * Camera accents (BRAND-v1 4.2): the Follow Dot dot and trail take
         * [CaptureTheme.CameraTokens.warning]; the mic meter and the done tick
         * take [CaptureTheme.CameraTokens.success].
         */
        var colors: CaptureTheme.CameraTokens = CaptureTheme.cameraTokens(null)
            set(value) {
                field = value
                applyColors()
                invalidate()
            }

        init {
            applyColors()
        }

        private fun applyColors() {
            dotPaint.color = colors.warning
            haloPaint.color = CaptureColor.withAlpha(colors.warning, 0.5f)
            trailPaint.color = CaptureColor.withAlpha(colors.warning, 0.35f)
            meterPaint.color = colors.success
            tickFill.color = colors.success
        }
        private val rect = RectF()

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            oval.set(ContourScanView.ovalFor(w.toFloat(), h.toFloat()))
        }

        /** Move the dot to (x, y), each 0..1 of this view; the first position is a jump. */
        fun moveDot(
            x: Float,
            y: Float,
        ) {
            val tx = x * width
            val ty = y * height
            if (dotX.isNaN() || frozen) {
                dotX = tx
                dotY = ty
                fromX = tx
                fromY = ty
                toX = tx
                toY = ty
                if (trail.isEmpty()) {
                    trail += tx
                    trail += ty
                }
                invalidate()
                return
            }
            fromX = dotX
            fromY = dotY
            toX = tx
            toY = ty
            moveStart = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }

        /** Debug screenshots: a fixed trail through these points (0..1 of the view). */
        fun setTrail(points: List<Pair<Float, Float>>) {
            trail.clear()
            points.forEach { (x, y) ->
                trail += x * width
                trail += y * height
            }
            invalidate()
        }

        /** Push the latest microphone level (0..1); the meter scrolls. */
        fun pushLevel(level: Float) {
            System.arraycopy(levels, 1, levels, 0, METER_BARS - 1)
            levels[METER_BARS - 1] = level.coerceIn(0f, 1f)
            invalidate()
        }

        /** Debug screenshots: hold the meter at these levels. */
        fun setLevels(values: FloatArray) {
            for (i in 0 until METER_BARS) levels[i] = values.getOrElse(i) { 0f }
            invalidate()
        }

        fun reset() {
            arrow = null
            dotVisible = false
            digits = null
            quiet = false
            tick = false
            dotX = Float.NaN
            dotY = Float.NaN
            trail.clear()
            levels.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            var animating = false
            if (dotVisible && !dotX.isNaN()) {
                if (stepDot()) animating = true
                drawTrail(canvas)
                canvas.drawCircle(dotX, dotY, 22f * dp, haloPaint)
                canvas.drawCircle(dotX, dotY, 12f * dp, dotPaint)
            }
            arrow?.let { drawArrow(canvas, it) }
            digits?.let { drawDigits(canvas, it) }
            if (tick && drawTick(canvas)) animating = true
            if (animating) postInvalidateOnAnimation()
        }

        /** Eases the dot toward its target and extends the trail. True while it moves. */
        private fun stepDot(): Boolean {
            if (dotX == toX && dotY == toY) return false
            val t = ((SystemClock.uptimeMillis() - moveStart) / DOT_MOVE_MS).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)
            dotX = fromX + (toX - fromX) * e
            dotY = fromY + (toY - fromY) * e
            val n = trail.size
            if (n < 2 || hypot(dotX - trail[n - 2], dotY - trail[n - 1]) > 3f * dp) {
                trail += dotX
                trail += dotY
            }
            if (t >= 1f) {
                dotX = toX
                dotY = toY
                return false
            }
            return true
        }

        private fun drawTrail(canvas: Canvas) {
            if (trail.size < 4) return
            path.rewind()
            path.moveTo(trail[0], trail[1])
            var i = 2
            while (i < trail.size) {
                path.lineTo(trail[i], trail[i + 1])
                i += 2
            }
            if (!frozen) path.lineTo(dotX, dotY)
            canvas.drawPath(path, trailPaint)
        }

        /** Storyboard: tip 40 from the side at the oval's centre height, 46 long, 22 x 24 chevron. */
        private fun drawArrow(
            canvas: Canvas,
            a: Arrow,
        ) {
            val cy = oval.centerY()
            path.rewind()
            when (a) {
                Arrow.LEFT, Arrow.RIGHT -> {
                    val s = if (a == Arrow.LEFT) 1f else -1f
                    val tip = if (a == Arrow.LEFT) 40f * dp else width - 40f * dp
                    path.moveTo(tip + s * 22f * dp, cy - 24f * dp)
                    path.lineTo(tip, cy)
                    path.lineTo(tip + s * 22f * dp, cy + 24f * dp)
                    path.moveTo(tip, cy)
                    path.lineTo(tip + s * 46f * dp, cy)
                }
                Arrow.UP, Arrow.DOWN -> {
                    val cx = oval.centerX()
                    val s = if (a == Arrow.UP) 1f else -1f
                    // Up sits above the ring; down inside the oval's lower edge (the guidance is below it).
                    val tip = if (a == Arrow.UP) oval.top - 54f * dp else oval.bottom - 20f * dp
                    path.moveTo(cx - 24f * dp, tip + s * 22f * dp)
                    path.lineTo(cx, tip)
                    path.lineTo(cx + 24f * dp, tip + s * 22f * dp)
                    path.moveTo(cx, tip)
                    path.lineTo(cx, tip + s * 46f * dp)
                }
            }
            canvas.drawPath(path, arrowPaint)
        }

        /** Storyboard: pill at the top, 34 monospace, letter spacing 0.32 em; the meter 116 below the oval. */
        private fun drawDigits(
            canvas: Canvas,
            text: String,
        ) {
            val isNumber = text.none { it.isLetter() }
            var size = (if (isNumber) 34f else 24f) * dp
            digitsPaint.typeface = if (isNumber) Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) else Typeface.DEFAULT_BOLD
            digitsPaint.letterSpacing = if (isNumber) 0.32f else 0f
            digitsPaint.textSize = size
            val maxW = width - 2 * 24f * dp - 2 * 22f * dp
            val w0 = digitsPaint.measureText(text)
            if (w0 > maxW) {
                size *= maxW / w0
                digitsPaint.textSize = size
            }
            val tw = digitsPaint.measureText(text)
            val fm = digitsPaint.fontMetrics
            val th = fm.descent - fm.ascent
            val top = topInset + 42f * dp
            rect.set(width / 2f - tw / 2f - 22f * dp, top, width / 2f + tw / 2f + 22f * dp, top + th + 20f * dp)
            canvas.drawRoundRect(rect, 16f * dp, 16f * dp, pillPaint)
            // Letter spacing adds a trailing gap: centre on the glyphs, not the advance.
            val shift = if (isNumber) digitsPaint.letterSpacing * size / 2f else 0f
            canvas.drawText(text, width / 2f + shift, top + 10f * dp - fm.ascent, digitsPaint)

            // Meter: 9 bars, 6 wide, 5 apart, up to 34 tall, bottom-aligned.
            val barW = 6f * dp
            val gap = 5f * dp
            val total = METER_BARS * barW + (METER_BARS - 1) * gap
            val bottom = oval.bottom + 116f * dp + 34f * dp
            var x = width / 2f - total / 2f
            for (i in 0 until METER_BARS) {
                val level = if (quiet) 0f else levels[i]
                val h = 4f * dp + (30f * dp) * level * METER_SHAPE[i]
                rect.set(x, bottom - h, x + barW, bottom)
                canvas.drawRoundRect(rect, 3f * dp, 3f * dp, meterPaint)
                x += barW + gap
            }
        }

        /** A 44 dp green disc with a white check, popping in over 220 ms. */
        private fun drawTick(canvas: Canvas): Boolean {
            val t = ((SystemClock.uptimeMillis() - tickStart) / TICK_IN_MS).coerceIn(0f, 1f)
            val scale = if (t < 1f) 0.6f + 0.4f * (1f - (1f - t) * (1f - t)) else 1f
            val cx = oval.centerX()
            val cy = oval.centerY()
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            tickFill.alpha = (TICK_ALPHA * min(1f, t * 1.5f)).toInt()
            canvas.drawCircle(cx, cy, 44f * dp, tickFill)
            path.rewind()
            path.moveTo(cx - 20f * dp, cy + 2f * dp)
            path.lineTo(cx - 6f * dp, cy + 16f * dp)
            path.lineTo(cx + 20f * dp, cy - 14f * dp)
            canvas.drawPath(path, tickStroke)
            canvas.restore()
            return t < 1f
        }

        companion object {
            const val METER_BARS = 9

            /** The storyboard's bar profile (10 18 30 22 34 16 26 12 8 of 34): taller in the middle. */
            private val METER_SHAPE = floatArrayOf(0.35f, 0.6f, 0.9f, 0.7f, 1f, 0.55f, 0.8f, 0.45f, 0.3f)
            private const val DOT_MOVE_MS = 600f
            private const val TICK_IN_MS = 220f
            /** The tick disc at 90%. */
            private const val TICK_ALPHA = 230
        }
    }
