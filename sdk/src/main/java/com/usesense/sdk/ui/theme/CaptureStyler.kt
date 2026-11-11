package com.usesense.sdk.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.usesense.sdk.R
import com.usesense.sdk.flows.AppearanceShape
import com.usesense.sdk.ui.StrokeIconView

/**
 * Applies a [CaptureTheme] to the capture views. The hosted layout declares
 * what each view is with an `android:tag` role (see [Role]); [styleTree]
 * walks it once and every view takes its colour, font and shape from the
 * theme, so no capture view carries a literal colour (CaptureColourGuardTest).
 */
internal class CaptureStyler(private val context: Context, val theme: CaptureTheme) {

    private val density = context.resources.displayMetrics.density
    fun dp(v: Number): Float = v.toFloat() * density

    /** Page and sheet titles: `typography.displayFamily`, else the bundled Outfit. */
    val display: Typeface by lazy { typeface(theme.type.displayFamily, R.font.outfit, 700) }
    /** Everything else, camera guidance included: `typography.fontFamily`, else the bundled DM Sans. */
    val body: Typeface by lazy { typeface(theme.type.fontFamily, R.font.dm_sans, 400) }
    val bodyMedium: Typeface by lazy { typeface(theme.type.fontFamily, R.font.dm_sans, 600) }

    private val p get() = theme.page

    /** The roles a layout view can declare in `android:tag`. */
    object Role {
        const val PAGE = "page"
        const val TITLE = "title"
        const val TEXT = "text"
        const val MUTED = "muted"
        const val ACCENT = "accent"
        const val DIVIDER = "divider"
        const val HEADER = "header"
        const val BUTTON_PRIMARY = "btn-primary"
        const val BUTTON_SECONDARY = "btn-secondary"
        const val BUTTON_NEUTRAL = "btn-neutral"
        const val BUTTON_DANGER = "btn-danger"
        const val SHEET = "sheet"
        const val DIALOG = "dialog"
        const val CARD = "card"
        const val ICON = "icon"
        const val SPINNER = "spinner"
        const val UPLOAD_BAR = "upload-bar"
        const val POWERED_BY = "powered"
        const val CAMERA = "camera"
        const val CAMERA_TEXT = "cam-text"
        const val CAMERA_DETAIL = "cam-detail"
        const val CAMERA_MUTED = "cam-muted"
        const val CAMERA_BUTTON = "cam-button"
        const val CAMERA_SCRIM = "cam-scrim"
        const val DIALOG_SCRIM = "dialog-scrim"
        const val TILE = "tile"
    }

    /** Style [root] and every descendant by its tag role. */
    fun styleTree(root: View) {
        (root.tag as? String)?.let { style(root, it) }
        if (root is ViewGroup) for (i in 0 until root.childCount) styleTree(root.getChildAt(i))
    }

    fun style(v: View, role: String) {
        when (role) {
            Role.PAGE, Role.HEADER -> v.setBackgroundColor(p.background)
            Role.TITLE -> (v as? TextView)?.let { text(it, p.foreground, display) }
            Role.TEXT -> (v as? TextView)?.let { text(it, p.foreground, body) }
            Role.MUTED -> (v as? TextView)?.let { text(it, p.muted, body) }
            Role.ACCENT -> (v as? TextView)?.let { text(it, p.primary, bodyMedium) }
            Role.DIVIDER -> v.setBackgroundColor(p.border)
            Role.BUTTON_PRIMARY -> (v as? TextView)?.let(::primaryButton)
            Role.BUTTON_SECONDARY -> (v as? TextView)?.let(::secondaryButton)
            Role.BUTTON_NEUTRAL -> (v as? TextView)?.let(::neutralButton)
            Role.BUTTON_DANGER -> (v as? TextView)?.let(::dangerButton)
            Role.SHEET -> v.background = rounded(p.surface, dp(theme.shape.sheetRadiusDp))
            Role.DIALOG, Role.CARD -> v.background = rounded(p.surface, dp(theme.shape.dialogRadiusDp))
            Role.ICON -> (v as? ImageView)?.imageTintList = ColorStateList.valueOf(p.primary)
            Role.SPINNER -> (v as? ProgressBar)?.indeterminateTintList = ColorStateList.valueOf(p.primary)
            Role.UPLOAD_BAR -> (v as? ProgressBar)?.let(::uploadBar)
            Role.POWERED_BY -> (v as? TextView)?.let { text(it, CaptureFixed.POWERED_BY, body) }
            Role.CAMERA -> v.setBackgroundColor(CaptureFixed.CAMERA_BACKGROUND)
            Role.CAMERA_TEXT -> (v as? TextView)?.let { text(it, CaptureFixed.GUIDANCE_TEXT, bodyMedium); shadow(it) }
            Role.CAMERA_DETAIL -> (v as? TextView)?.let { text(it, CaptureFixed.GUIDANCE_DETAIL, bodyMedium); shadow(it) }
            Role.CAMERA_MUTED -> (v as? TextView)?.let { text(it, CaptureFixed.CAMERA_TEXT_MUTED, body) }
            Role.CAMERA_BUTTON -> {
                v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CaptureFixed.CAMERA_BUTTON) }
                (v as? TextView)?.let { text(it, CaptureFixed.GUIDANCE_TEXT, bodyMedium) }
                (v as? ImageView)?.imageTintList = ColorStateList.valueOf(CaptureFixed.GUIDANCE_TEXT)
            }
            Role.CAMERA_SCRIM -> v.setBackgroundColor(CaptureFixed.CAMERA_SCRIM)
            Role.DIALOG_SCRIM -> v.setBackgroundColor(CaptureFixed.DIALOG_SCRIM)
            Role.TILE -> (v as? StrokeIconView)?.tileRadiusPx = dp(theme.shape.tileRadiusDp)
        }
    }

    /** The guidance shadow (fixed); the layout keeps its radius and offset. */
    private fun shadow(v: TextView) {
        if (v.shadowRadius > 0f) v.setShadowLayer(v.shadowRadius, v.shadowDx, v.shadowDy, CaptureFixed.GUIDANCE_SHADOW)
    }

    private fun text(v: TextView, color: Int, face: Typeface) {
        v.setTextColor(color)
        // Keep a bold the layout asked for, at the brand face's own weights.
        val wantsBold = v.typeface?.isBold == true
        v.typeface = if (wantsBold && face.weight < BOLD_WEIGHT) Typeface.create(face, BOLD_WEIGHT, false) else face
    }

    /** Primary: filled in `colors.primary`, or outlined when `shape.buttonStyle` is outline. */
    fun primaryButton(v: TextView) {
        val r = dp(theme.shape.buttonRadiusDp)
        if (theme.shape.buttonStyle == AppearanceShape.ButtonStyle.OUTLINE) {
            v.background = ripple(outline(p.primary, r), p.primary, r)
            v.setTextColor(enabledColors(p.primary))
        } else {
            v.background = ripple(rounded(p.primary, r), p.primaryForeground, r)
            v.setTextColor(enabledColors(p.primaryForeground))
        }
        clearTint(v)
        v.typeface = bodyMedium
        v.isAllCaps = false
    }

    /** Secondary: text only, in `colors.muted`. */
    fun secondaryButton(v: TextView) {
        val r = dp(theme.shape.buttonRadiusDp)
        v.background = ripple(null, p.muted, r)
        v.setTextColor(enabledColors(p.muted))
        clearTint(v)
        v.typeface = bodyMedium
        v.isAllCaps = false
    }

    /** A neutral choice next to a primary one ("Leave"): `colors.border` outline, foreground text. */
    fun neutralButton(v: TextView) {
        val r = dp(theme.shape.buttonRadiusDp)
        v.background = ripple(outline(p.border, r), p.foreground, r)
        v.setTextColor(enabledColors(p.foreground))
        clearTint(v)
        v.typeface = bodyMedium
        v.isAllCaps = false
    }

    /** "This is not my request": outlined in `colors.error`. */
    fun dangerButton(v: TextView) {
        val r = dp(theme.shape.buttonRadiusDp)
        v.background = ripple(outline(p.error, r), p.error, r)
        v.setTextColor(enabledColors(p.error))
        clearTint(v)
        v.typeface = bodyMedium
        v.isAllCaps = false
    }

    /** The upload bar: `capture.ring` fill (BRAND-v1 4.2) on a foreground-tinted track. */
    fun uploadBar(bar: ProgressBar) {
        val r = dp(2)
        val track = rounded(CaptureColor.withAlpha(p.foreground, TRACK_ALPHA), r)
        val fill = android.graphics.drawable.ClipDrawable(rounded(theme.camera.ring, r), android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        bar.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** An icon tile in [tone] (glyph = tone, tile = tone at 16%), with the theme's tile radius. */
    fun tile(icon: StrokeIconView, path: String, tone: CaptureTheme.Tone) {
        icon.tileRadiusPx = dp(theme.shape.tileRadiusDp)
        icon.set(path, theme.toneTile(tone), theme.toneColor(tone))
    }

    fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun outline(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(0)
        setStroke(dp(OUTLINE_DP).toInt().coerceAtLeast(1), color)
    }

    private fun ripple(content: Drawable?, on: Int, radius: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(CaptureColor.withAlpha(on, RIPPLE_ALPHA)), content, rounded(on, radius))

    private fun enabledColors(color: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(CaptureColor.withAlpha(color, DISABLED_ALPHA), color),
    )

    /** A MaterialButton tints its background; the theme's drawable is the whole look. */
    private fun clearTint(v: TextView) {
        v.backgroundTintList = null
    }

    /**
     * A font-family name as a Typeface. The native SDK can't fetch web fonts,
     * so: the bundled Sense fonts by name, a font resource of that name in the
     * host app (`Inter` -> `R.font.inter`), the generic serif / monospace
     * families, a system family name, else the platform sans-serif.
     */
    private fun typeface(family: String?, bundled: Int, weight: Int): Typeface {
        val base = family?.let(::named) ?: bundledFont(bundled) ?: Typeface.DEFAULT
        return Typeface.create(base, weight, false)
    }

    private fun bundledFont(id: Int): Typeface? = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()

    private fun named(raw: String): Typeface? {
        val name = raw.split(',').firstOrNull()?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() } ?: return null
        val key = name.lowercase()
        when (key) {
            "outfit" -> return bundledFont(R.font.outfit)
            "dm sans", "dmsans" -> return bundledFont(R.font.dm_sans)
            "jetbrains mono" -> return bundledFont(R.font.jetbrains_mono)
        }
        val resName = key.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val appFont = context.resources.getIdentifier(resName, "font", context.packageName)
        if (appFont != 0) bundledFont(appFont)?.let { return it }
        return when (key) {
            "serif", "georgia", "times", "times new roman", "garamond", "merriweather", "playfair display" -> Typeface.SERIF
            "monospace", "mono", "courier", "courier new", "menlo", "consolas" -> Typeface.MONOSPACE
            else -> Typeface.create(key, Typeface.NORMAL)
        }
    }

    companion object {
        private const val RIPPLE_ALPHA = 0.2f
        private const val DISABLED_ALPHA = 0.5f
        private const val TRACK_ALPHA = 0.15f
        private const val OUTLINE_DP = 1.5f
        private const val BOLD_WEIGHT = 600
    }
}
