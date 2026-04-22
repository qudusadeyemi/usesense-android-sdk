package com.usesense.sdk.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Custom view for the LiveSense v4 framing oval.
 * Phase 1 ticket A-1.
 *
 * Animates the oval between scale 1.0 and 1.4 over 250ms with brand
 * cubic-bezier easing. Respects ANIMATOR_DURATION_SCALE (reduce-motion).
 */
class ZoomPromptView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { FRAMING, ENLARGED }

    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = 0x1AFFFFFF.toInt()
    }
    private var scale: Float = 1f
    private var state: State = State.FRAMING
    private var animator: ValueAnimator? = null
    private var onEnlargeAnimationComplete: (() -> Unit)? = null

    fun setState(next: State, animated: Boolean = true) {
        if (next == state) return
        state = next
        val target = if (next == State.ENLARGED) 1.4f else 1.0f
        animator?.cancel()
        if (!animated || isReduceMotion()) {
            scale = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(scale, target).apply {
            duration = TRANSITION_MS
            interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
            addUpdateListener {
                scale = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (next == State.ENLARGED) onEnlargeAnimationComplete?.invoke()
                }
            })
            start()
        }
    }

    fun setOnEnlargeAnimationComplete(cb: () -> Unit) { onEnlargeAnimationComplete = cb }

    private fun isReduceMotion(): Boolean {
        val scaleSetting = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        } catch (_: Exception) { 1f }
        return scaleSetting == 0f
    }

    override fun onDraw(canvas: Canvas) {
        val vmin = minOf(width, height)
        val baseW = vmin * 0.44f
        val baseH = vmin * 0.59f
        val w = baseW * scale
        val h = baseH * scale
        val cx = width / 2f
        val cy = height * 0.40f
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.drawOval(rect, shadowPaint)
        canvas.drawOval(rect, ovalPaint)
    }

    companion object {
        const val TRANSITION_MS: Long = 250
        const val ENLARGED_SCALE: Float = 1.4f
    }
}
