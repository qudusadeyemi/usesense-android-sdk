package com.usesense.sdk.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.usesense.sdk.flows.AppearanceLoader
import kotlin.math.PI
import kotlin.math.sin

/**
 * The "Getting ready" and loading animation (BRAND-v1 4.1 `loader.style`):
 * a spinner, three dots or an indeterminate bar, in `colors.primary`.
 * A `loader.imageUrl` replaces it (HostedPageActivity shows the image instead).
 */
internal class CaptureLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var style: AppearanceLoader.Style = AppearanceLoader.Style.SPINNER
        set(value) { field = value; requestLayout(); invalidate() }

    var color: Int = SenseDefaults.PRIMARY
        set(value) {
            field = value
            paint.color = value
            track.color = CaptureColor.withAlpha(value, TRACK_ALPHA)
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SenseDefaults.PRIMARY; strokeCap = Paint.Cap.ROUND }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CaptureColor.withAlpha(SenseDefaults.PRIMARY, TRACK_ALPHA) }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat()
        when (style) {
            AppearanceLoader.Style.SPINNER -> drawSpinner(canvas, t)
            AppearanceLoader.Style.DOTS -> drawDots(canvas, t)
            AppearanceLoader.Style.BAR -> drawBar(canvas, t)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawSpinner(canvas: Canvas, t: Float) {
        val stroke = 4f * dp
        val s = minOf(width, height) - stroke
        rect.set((width - s) / 2f, (height - s) / 2f, (width + s) / 2f, (height + s) / 2f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        track.style = Paint.Style.STROKE
        track.strokeWidth = stroke
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, t * 360f - 90f, 100f, false, paint)
    }

    private fun drawDots(canvas: Canvas, t: Float) {
        paint.style = Paint.Style.FILL
        val r = minOf(height / 2f, width / 10f)
        val gap = r * 3f
        for (i in 0 until 3) {
            val phase = sin(2 * PI * (t - i * 0.15)).toFloat()
            val scale = 0.6f + 0.4f * ((phase + 1f) / 2f)
            canvas.drawCircle(width / 2f + (i - 1) * gap, height / 2f, r * scale, paint)
        }
    }

    private fun drawBar(canvas: Canvas, t: Float) {
        val h = 4f * dp
        val top = (height - h) / 2f
        track.style = Paint.Style.FILL
        paint.style = Paint.Style.FILL
        rect.set(0f, top, width.toFloat(), top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track)
        val segW = width * 0.35f
        val x = -segW + (width + segW) * t
        rect.set(maxOf(0f, x), top, minOf(width.toFloat(), x + segW), top + h)
        if (rect.width() > 0f) canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
    }

    companion object {
        private const val PERIOD_MS = 1_100L
        private const val TRACK_ALPHA = 0.18f
    }
}
