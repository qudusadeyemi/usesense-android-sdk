package com.usesense.sdk.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Custom view: semi-transparent overlay with oval cutout + pulsing dashed border.
 * Used during the Face Guide phase (v1.17.5+).
 *
 * Section 6.8 / 7.5:
 *   Mask: radial gradient ellipse, transparent center -> rgba(0,0,0,0.6)
 *   Oval: 55% width of container, 3:4 aspect ratio, max 80% height
 *   Border: 3dp dashed rgba(255,255,255,0.8)
 *   Pulse: scale 1 -> 1.1 -> 1, opacity 1 -> 0.6 -> 1, 2000ms ease-in-out infinite
 */
class FaceGuideOvalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = 0x99000000.toInt() // rgba(0,0,0,0.6)
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private val dp = resources.displayMetrics.density

    private val dashedBorderPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt() // rgba(255,255,255,0.8)
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        isAntiAlias = true
        pathEffect = DashPathEffect(
            floatArrayOf(12f * dp, 8f * dp), 0f
        )
    }

    private var pulseScale = 1f
    private var pulseAlpha = 1f
    private var pulseAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulseAnimation()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Section 8.4: Face Guide Pulse
     * scale 1 -> 1.1 -> 1, opacity 1 -> 0.6 -> 1, 2000ms ease-in-out infinite
     */
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedFraction
                pulseScale = 1f + 0.1f * fraction    // 1.0 -> 1.1
                pulseAlpha = 1f - 0.4f * fraction     // 1.0 -> 0.6
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val ovalRect = getOvalRect(w, h)

        // Draw on offscreen layer for CLEAR xfermode
        val saveCount = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        canvas.drawOval(ovalRect, clearPaint)
        canvas.restoreToCount(saveCount)

        // Draw pulsing dashed border
        val cx = ovalRect.centerX()
        val cy = ovalRect.centerY()
        val hw = ovalRect.width() / 2f * pulseScale
        val hh = ovalRect.height() / 2f * pulseScale
        val scaledRect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)

        dashedBorderPaint.alpha = (pulseAlpha * 204).toInt() // 204 = 0xCC = 80%
        canvas.drawOval(scaledRect, dashedBorderPaint)
    }

    companion object {
        /**
         * Section 6.8: Oval dimensions
         *   Width: 55% of container width
         *   Aspect ratio: 3:4 (height = width * 4/3)
         *   Max height: 80% of container
         *   Centered horizontally and vertically
         */
        fun getOvalRect(viewWidth: Float, viewHeight: Float): RectF {
            val ovalWidth = viewWidth * 0.55f
            var ovalHeight = ovalWidth * (4f / 3f)

            // Cap at 80% of container height
            if (ovalHeight > viewHeight * 0.8f) {
                ovalHeight = viewHeight * 0.8f
            }

            val cx = viewWidth / 2f
            val cy = viewHeight / 2f
            return RectF(
                cx - ovalWidth / 2f,
                cy - ovalHeight / 2f,
                cx + ovalWidth / 2f,
                cy + ovalHeight / 2f
            )
        }
    }
}
