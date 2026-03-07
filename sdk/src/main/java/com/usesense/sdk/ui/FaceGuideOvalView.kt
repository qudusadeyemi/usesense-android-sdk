package com.usesense.sdk.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Custom view that draws a semi-transparent overlay with an oval cutout
 * and a pulsing dashed border around the oval. Used during the Face Guide phase (v1.17.5+).
 *
 * Oval geometry:
 *   Center: 50% horizontal, 46% vertical
 *   Width:  38% of view width
 *   Height: 62% of view height
 */
class FaceGuideOvalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private val dashedBorderPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        isAntiAlias = true
        pathEffect = DashPathEffect(
            floatArrayOf(12f * resources.displayMetrics.density, 8f * resources.displayMetrics.density),
            0f
        )
    }

    private var pulseScale = 1f
    private var pulseAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulseAnimation()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.02f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val ovalWidth = w * 0.38f
        val ovalHeight = h * 0.62f
        val cx = w / 2f
        val cy = h * 0.46f

        // Draw on an offscreen layer to support CLEAR xfermode
        val saveCount = canvas.saveLayer(0f, 0f, w, h, null)

        // Draw semi-transparent overlay
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Clear oval cutout
        val ovalRect = RectF(
            cx - ovalWidth / 2f,
            cy - ovalHeight / 2f,
            cx + ovalWidth / 2f,
            cy + ovalHeight / 2f
        )
        canvas.drawOval(ovalRect, clearPaint)

        canvas.restoreToCount(saveCount)

        // Draw pulsing dashed border
        val scaledOvalRect = RectF(
            cx - (ovalWidth / 2f) * pulseScale,
            cy - (ovalHeight / 2f) * pulseScale,
            cx + (ovalWidth / 2f) * pulseScale,
            cy + (ovalHeight / 2f) * pulseScale
        )
        canvas.drawOval(scaledOvalRect, dashedBorderPaint)
    }

    companion object {
        /** Returns the oval geometry for use by the subtle baseline oval reminder. */
        fun getOvalRect(viewWidth: Float, viewHeight: Float): RectF {
            val ovalWidth = viewWidth * 0.38f
            val ovalHeight = viewHeight * 0.62f
            val cx = viewWidth / 2f
            val cy = viewHeight * 0.46f
            return RectF(
                cx - ovalWidth / 2f,
                cy - ovalHeight / 2f,
                cx + ovalWidth / 2f,
                cy + ovalHeight / 2f
            )
        }
    }
}
