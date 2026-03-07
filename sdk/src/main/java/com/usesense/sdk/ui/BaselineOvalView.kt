package com.usesense.sdk.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Subtle non-animated oval border shown during baseline capture phase
 * as a lighter reminder of the face guide position (v1.17.5+).
 *
 * Properties: 2dp solid stroke, white at 30% opacity, no background dim.
 */
class BaselineOvalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val ovalPaint = Paint().apply {
        color = 0x4DFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ovalRect = FaceGuideOvalView.getOvalRect(width.toFloat(), height.toFloat())
        canvas.drawOval(ovalRect, ovalPaint)
    }
}
