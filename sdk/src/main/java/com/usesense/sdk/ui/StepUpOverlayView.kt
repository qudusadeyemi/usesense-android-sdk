package com.usesense.sdk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Overlay view for inline step-up challenges.
 * Follows UseSense Brand Manual v3.0:
 * - DeepSense Blue (#4F7CFF) for shield icon and primary accent
 * - MatchSense Green (#00D4AA) for success states
 * - Warm neutrals for text and backgrounds
 * - Motion: cubic-bezier(0.16, 1, 0.3, 1), 250ms normal
 * - Border radius: 14dp (lg) for cards, 10dp (md) for buttons
 */
class StepUpOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    // Brand colors
    private val brandBlue = Color.parseColor("#4F7CFF")
    private val brandGreen = Color.parseColor("#00D4AA")
    private val n0 = Color.parseColor("#FFFFFF")
    private val n8 = Color.parseColor("#1C1A17")
    private val n5 = Color.parseColor("#6B6760")

    // Brand motion
    private val brandEase = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val durationNormal = 250L

    private val titleView: TextView
    private val subtitleView: TextView
    private val actionLabel: TextView
    private val stepCounter: TextView
    private val progressBar: ProgressBar
    private val contentCard: LinearLayout

    /**
     * Full-screen color overlay for flash reflection challenge.
     */
    val flashOverlay: View

    init {
        setBackgroundColor(Color.argb(191, 28, 26, 23)) // n8 at 75%

        // Flash overlay (behind content card, used for color flashes)
        flashOverlay = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(flashOverlay)

        // Content card
        contentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val cardBg = GradientDrawable().apply {
                setColor(n0)
                cornerRadius = 14 * dp // brand lg radius
            }
            background = cardBg
            elevation = 8 * dp
            val pad = (32 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(
                (300 * dp).toInt(),
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        addView(contentCard)

        // Shield icon placeholder (text-based, brand blue)
        val shieldIcon = TextView(context).apply {
            text = "\uD83D\uDEE1\uFE0F" // shield emoji as placeholder
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
            val iconBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EBF0FF")) // blue-0
            }
            background = iconBg
            val iconSize = (72 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (20 * dp).toInt()
            }
        }
        contentCard.addView(shieldIcon)

        // Title: Outfit-style (bold, tight tracking)
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(n8)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.02f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        contentCard.addView(titleView)

        // Subtitle: DM Sans-style (regular, 1.6 line-height)
        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(n5)
            setLineSpacing(0f, 1.6f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (24 * dp).toInt() }
        }
        contentCard.addView(subtitleView)

        // Action label (for RMAS actions)
        actionLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(brandBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.02f
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (12 * dp).toInt() }
        }
        contentCard.addView(actionLabel)

        // Step counter (for RMAS: "1 of 3")
        stepCounter = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#9E9A92")) // n4
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (16 * dp).toInt() }
        }
        contentCard.addView(stepCounter)

        // Progress bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (4 * dp).toInt())
            layoutParams = lp
            progressDrawable?.setTint(brandBlue)
            max = 100
            visibility = GONE
        }
        contentCard.addView(progressBar)

        visibility = GONE
    }

    fun showIntro() {
        visibility = VISIBLE
        flashOverlay.setBackgroundColor(Color.TRANSPARENT)
        titleView.text = "Additional Verification"
        subtitleView.text = "Hold still for a quick check"
        subtitleView.visibility = VISIBLE
        actionLabel.visibility = GONE
        stepCounter.visibility = GONE
        progressBar.visibility = GONE
        contentCard.visibility = VISIBLE
        fadeIn()
    }

    fun showFlashPhase() {
        titleView.text = "Hold still"
        subtitleView.text = "Verifying..."
        subtitleView.visibility = VISIBLE
        actionLabel.visibility = GONE
        stepCounter.visibility = GONE
        progressBar.visibility = GONE
    }

    fun showRMASAction(actionType: String, label: String, step: Int) {
        titleView.text = "Follow the prompt"
        subtitleView.visibility = GONE
        actionLabel.text = label
        actionLabel.visibility = VISIBLE
        stepCounter.text = "$step of 3"
        stepCounter.visibility = VISIBLE
        progressBar.visibility = VISIBLE
        progressBar.progress = 0
    }

    fun updateProgress(progress: Int) {
        progressBar.progress = progress
    }

    fun showComplete() {
        titleView.text = "Verification complete"
        titleView.setTextColor(brandGreen)
        subtitleView.visibility = GONE
        actionLabel.visibility = GONE
        stepCounter.visibility = GONE
        progressBar.visibility = GONE
    }

    fun hide() {
        fadeOut {
            visibility = GONE
            // Reset state
            titleView.setTextColor(n8)
            flashOverlay.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun fadeIn() {
        alpha = 0f
        translationY = 12 * dp
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(durationNormal)
            .setInterpolator(brandEase)
            .start()
    }

    private fun fadeOut(onEnd: () -> Unit) {
        animate()
            .alpha(0f)
            .translationY(12 * dp)
            .setDuration(durationNormal)
            .setInterpolator(brandEase)
            .withEndAction(onEnd)
            .start()
    }
}
