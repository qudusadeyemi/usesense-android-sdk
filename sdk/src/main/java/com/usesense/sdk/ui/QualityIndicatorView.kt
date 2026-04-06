package com.usesense.sdk.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.usesense.sdk.capture.ImageQualityAnalyzer

/**
 * Compact quality indicator overlay shown inside the camera preview.
 *
 * In full mode: shows at the top of the video container with quality text.
 * In compact mode: shows as a small colored dot at the bottom.
 */
class QualityIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { FULL, COMPACT }

    var mode: Mode = Mode.FULL
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private var currentLevel: ImageQualityAnalyzer.QualityLevel = ImageQualityAnalyzer.QualityLevel.GOOD
    private var currentMessage: String? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()

    // Brand manual: DeepSense Blue for quality indicators
    private val colorGood = Color.parseColor("#4F7CFF")        // DeepSense Blue
    private val colorAcceptable = Color.parseColor("#7C5CFC")  // LiveSense Purple
    private val colorPoor = Color.parseColor("#3D63DB")        // Blue-6 (darker)

    private var targetColor = colorGood
    private var animatedColor = colorGood

    private val colorAnimator = ValueAnimator().apply {
        duration = 400
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            animatedColor = anim.animatedValue as Int
            invalidate()
        }
    }

    fun updateQuality(report: ImageQualityAnalyzer.ImageQualityReport) {
        currentLevel = when {
            report.overallScore >= 65 -> ImageQualityAnalyzer.QualityLevel.GOOD
            report.overallScore >= 40 -> ImageQualityAnalyzer.QualityLevel.ACCEPTABLE
            else -> ImageQualityAnalyzer.QualityLevel.POOR
        }
        currentMessage = report.guidance.firstOrNull()?.message

        val newColor = when (currentLevel) {
            ImageQualityAnalyzer.QualityLevel.GOOD -> colorGood
            ImageQualityAnalyzer.QualityLevel.ACCEPTABLE -> colorAcceptable
            ImageQualityAnalyzer.QualityLevel.POOR -> colorPoor
        }

        if (newColor != targetColor) {
            targetColor = newColor
            colorAnimator.cancel()
            colorAnimator.setIntEvaluator()
            colorAnimator.setObjectValues(animatedColor, newColor)
            colorAnimator.start()
        }

        visibility = if (currentLevel == ImageQualityAnalyzer.QualityLevel.GOOD && mode == Mode.FULL) {
            GONE
        } else {
            VISIBLE
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (mode == Mode.COMPACT) {
            val dotSize = (12 * resources.displayMetrics.density).toInt()
            setMeasuredDimension(dotSize, dotSize)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (mode == Mode.COMPACT) {
            dotPaint.color = animatedColor
            val radius = width / 2f
            canvas.drawCircle(radius, radius, radius, dotPaint)
        } else {
            val msg = currentMessage ?: return
            val dp = resources.displayMetrics.density
            val hPad = 12 * dp
            val vPad = 6 * dp
            val cornerRadius = 8 * dp

            textPaint.color = Color.WHITE
            val textWidth = textPaint.measureText(msg)
            val totalWidth = textWidth + hPad * 2
            val totalHeight = textPaint.textSize + vPad * 2

            val left = (width - totalWidth) / 2
            val top = 8 * dp
            bgRect.set(left, top, left + totalWidth, top + totalHeight)

            bgPaint.color = animatedColor
            bgPaint.alpha = (0.9f * 255).toInt()
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

            val textY = bgRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(msg, width / 2f, textY, textPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        colorAnimator.cancel()
    }

    private fun ValueAnimator.setIntEvaluator() {
        setEvaluator(android.animation.ArgbEvaluator())
    }
}
