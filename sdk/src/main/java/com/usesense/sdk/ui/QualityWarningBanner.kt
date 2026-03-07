package com.usesense.sdk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.usesense.sdk.capture.ImageQualityAnalyzer

/**
 * Quality warning banner displayed below the video container.
 *
 * Uses the indigo guidance theme colors:
 * - Poor: violet-700 background/text
 * - Acceptable: violet-400 background/text
 */
class QualityWarningBanner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: TextView
    private val messageView: TextView

    // Colors per spec
    private val poorBg = Color.parseColor("#1A7C3AED")       // rgba(124, 58, 237, 0.1)
    private val poorText = Color.parseColor("#6D28D9")
    private val poorBorder = Color.parseColor("#337C3AED")    // rgba(124, 58, 237, 0.2)
    private val acceptableBg = Color.parseColor("#1AA78BFA")  // rgba(167, 139, 250, 0.1)
    private val acceptableText = Color.parseColor("#7C3AED")
    private val acceptableBorder = Color.parseColor("#33A78BFA") // rgba(167, 139, 250, 0.2)

    private val dp = resources.displayMetrics.density

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = (12 * dp).toInt()
        val vPad = (8 * dp).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        iconView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        addView(iconView)

        val gap = (6 * dp).toInt()
        messageView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(gap, 0, 0, 0)
        }
        addView(messageView)

        visibility = GONE
    }

    fun updateQuality(report: ImageQualityAnalyzer.ImageQualityReport) {
        val guidance = report.guidance.firstOrNull()
        if (guidance == null || report.overallScore >= 65) {
            visibility = GONE
            return
        }

        visibility = VISIBLE

        val isPoor = guidance.severity == ImageQualityAnalyzer.GuidanceSeverity.CRITICAL
        val bgColor = if (isPoor) poorBg else acceptableBg
        val textColor = if (isPoor) poorText else acceptableText
        val borderColor = if (isPoor) poorBorder else acceptableBorder

        val icon = when (guidance.icon) {
            ImageQualityAnalyzer.GuidanceIcon.BLUR -> "\u26A0\uFE0F"
            ImageQualityAnalyzer.GuidanceIcon.DARK -> "\uD83D\uDCA1"
            ImageQualityAnalyzer.GuidanceIcon.BRIGHT -> "\u2600\uFE0F"
            ImageQualityAnalyzer.GuidanceIcon.CONTRAST -> "\uD83D\uDCA1"
        }
        iconView.text = icon
        messageView.text = guidance.message
        messageView.setTextColor(textColor)

        val bgDrawable = GradientDrawable().apply {
            setColor(bgColor)
            setStroke((1 * dp).toInt(), borderColor)
            cornerRadius = 8 * dp
        }
        background = bgDrawable
    }
}
