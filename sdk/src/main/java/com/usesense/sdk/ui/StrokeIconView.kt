package com.usesense.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.PathParser

/**
 * A round badge with a stroked line icon (24-unit viewport, 2-unit stroke,
 * round caps), as the storyboard's sheet and page icons. The icon is SVG path
 * data from [Icons].
 */
internal class StrokeIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var icon: Path? = null
    /** Icon size as a share of the badge (26 of 52 on sheets, 36 of 72 on pages). */
    var iconScale = 0.5f

    fun set(pathData: String, background: Int, foreground: Int) {
        icon = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull()
        disc.color = background
        stroke.color = foreground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        canvas.drawCircle(width / 2f, height / 2f, s / 2f, disc)
        val p = icon ?: return
        val iconSize = s * iconScale
        val k = iconSize / 24f
        stroke.strokeWidth = 2f * k
        canvas.save()
        canvas.translate(width / 2f - iconSize / 2f, height / 2f - iconSize / 2f)
        canvas.scale(k, k)
        stroke.strokeWidth = 2f
        canvas.drawPath(p, stroke)
        canvas.restore()
    }

    /** The storyboard's icons (lucide-style, 24 x 24). */
    object Icons {
        const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
        const val CLOCK = "M12 7v5l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"
        const val X = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9 9l6 6M15 9l-6 6"
        const val CAMERA = "M3 7h4l2-3h6l2 3h4v13H3zM12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
        const val WIFI = "M2 8.8a15 15 0 0 1 20 0M5 12.8a10 10 0 0 1 14 0M8.5 16.4a5 5 0 0 1 7 0M12 20h.01M3 3l18 18"
        const val MIC = "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3zM19 10v2a7 7 0 0 1-14 0v-2M12 19v3"
        const val HOURGLASS = "M6 2h12M6 22h12M7 2c0 6 10 6 10 10S7 16 7 22M17 2c0 6-10 6-10 10s10 4 10 10"
        const val ALERT = "M12 9v4M12 17h.01M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"
        const val FACE = "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M9 10h.01M15 10h.01M9.5 15a3.5 3.5 0 0 0 5 0"
        const val TURN = "M3 12a9 9 0 0 1 15.5-6.2M21 12a9 9 0 0 1-15.5 6.2M18 2v4h-4M6 22v-4h4"
        const val DOT = "M12 12m-3 0a3 3 0 1 0 6 0 3 3 0 1 0-6 0M12 2v3M12 19v3M2 12h3M19 12h3"
        const val UPLOAD = "M12 16V4M7 9l5-5 5 5M4 20h16"
    }

    companion object {
        const val TINT_PURPLE_BG = 0x2EA878FC
        const val TINT_PURPLE_FG = 0xFFC3A6FF.toInt()
        const val TINT_AMBER_BG = 0x29FFB84D
        const val TINT_AMBER_FG = 0xFFFFB84D.toInt()
        const val TINT_GREEN_BG = 0x2900D4AA
        const val TINT_GREEN_FG = 0xFF00D4AA.toInt()
        const val TINT_RED_BG = 0x29FF6B4A
        const val TINT_RED_FG = 0xFFFF6B4A.toInt()
        const val TINT_BLUE_BG = 0x294F7CFF
        const val TINT_BLUE_FG = 0xFF7A96FF.toInt()
    }
}
