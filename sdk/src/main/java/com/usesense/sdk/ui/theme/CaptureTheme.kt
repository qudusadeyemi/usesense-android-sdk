package com.usesense.sdk.ui.theme

import com.usesense.sdk.flows.AppearanceColors
import com.usesense.sdk.flows.AppearanceIcons
import com.usesense.sdk.flows.AppearanceLoader
import com.usesense.sdk.flows.AppearanceLogo
import com.usesense.sdk.flows.AppearanceShape
import com.usesense.sdk.flows.FlowAppearance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The one resolved theme every capture screen reads (BRAND-v1 section 4).
 * Built once per session (and again when dashboard branding arrives) from the
 * merged [FlowAppearance]. Pure Kotlin with ARGB ints, so the rules are unit
 * tested; ui/theme/CaptureStyler applies it to views.
 *
 * Two surfaces:
 *  - [page]: every non-camera screen (primers, sheets, result and error pages,
 *    the leave and help dialogs, loading). Follows `mode` and the full palette.
 *  - [camera]: accents on the always-dark camera, each contrast-gated at 3:1
 *    against [CaptureFixed.CONTRAST_REFERENCE].
 * Everything else on the camera is on the fixed list ([CaptureFixed]).
 */
internal data class CaptureTheme(
    val page: PageTokens,
    val camera: CameraTokens,
    val type: TypeTokens,
    val shape: ShapeTokens,
    val logo: LogoTokens,
    val backgroundImageUrl: String?,
    val icons: AppearanceIcons?,
    val loader: LoaderTokens,
    val isDark: Boolean,
) {
    /** Page-surface colours for the active mode. */
    data class PageTokens(
        val background: Int,
        val surface: Int,
        val foreground: Int,
        val muted: Int,
        val border: Int,
        val primary: Int,
        val primaryForeground: Int,
        val success: Int,
        val error: Int,
        val warning: Int,
    )

    /** Camera accents, already gated. */
    data class CameraTokens(
        /** Oval "ready" stroke, scan ring, contour lines, upload bar fill. */
        val ring: Int,
        /** Extra-check ring while it runs, extra-check sheet icon. */
        val challenge: Int,
        /** Failing oval, paused ring, Follow Dot dot and trail. */
        val warning: Int,
        /** Done tick, done ring, mic meter. */
        val success: Int,
    )

    /** Font family names; null keeps the bundled Sense font (Outfit / DM Sans). */
    data class TypeTokens(val displayFamily: String?, val fontFamily: String?)

    /** Corner radii in dp and the primary button style. */
    data class ShapeTokens(
        val buttonRadiusDp: Int,
        val buttonStyle: AppearanceShape.ButtonStyle,
        val dialogRadiusDp: Int,
        val sheetRadiusDp: Int,
        val tileRadiusDp: Int,
    )

    data class LogoTokens(
        val url: String?,
        val placement: AppearanceLogo.Placement,
        val heightDp: Int,
    )

    data class LoaderTokens(val style: AppearanceLoader.Style, val imageUrl: String?)

    /** Which semantic colour a page icon takes (glyph = colour, tile = colour at 16%). */
    enum class Tone { PRIMARY, CHALLENGE, SUCCESS, WARNING, ERROR }

    fun toneColor(tone: Tone): Int = when (tone) {
        Tone.PRIMARY -> page.primary
        // The extra-check sheet sits on the page surface but carries the camera's challenge colour.
        Tone.CHALLENGE -> camera.challenge
        Tone.SUCCESS -> page.success
        Tone.WARNING -> page.warning
        Tone.ERROR -> page.error
    }

    /** Icon tile: the tone at 16%. */
    fun toneTile(tone: Tone): Int = CaptureColor.withAlpha(toneColor(tone), TILE_ALPHA)

    companion object {
        const val TILE_ALPHA = 0.16f
        const val DEFAULT_BUTTON_RADIUS_DP = 14
        const val DEFAULT_DIALOG_RADIUS_DP = 20
        const val DEFAULT_SHEET_RADIUS_DP = 28
        const val DEFAULT_TILE_RADIUS_DP = 12
        const val DEFAULT_LOGO_HEIGHT_DP = 28
        /** HSL lightening for a camera accent that fails 3:1: L += 5% per step, at most 8 steps. */
        const val LIGHTEN_STEP = 0.05f
        const val LIGHTEN_MAX_STEPS = 8

        /**
         * Resolve [appearance] for the device's [systemDark] setting.
         * [unsetMode] is the palette when `mode` is not set: capture pages stay
         * dark (today's look); the remote hosted link's screens stay light.
         */
        fun resolve(
            appearance: FlowAppearance?,
            systemDark: Boolean,
            unsetMode: FlowAppearance.Mode = FlowAppearance.Mode.DARK,
        ): CaptureTheme {
            val mode = appearance?.mode ?: unsetMode
            val dark = when (mode) {
                FlowAppearance.Mode.DARK -> true
                FlowAppearance.Mode.LIGHT -> false
                FlowAppearance.Mode.AUTO -> systemDark
            }
            val page = pageTokens(appearance, dark)
            val shape = appearance?.shape
            val radius = shape?.radius
            return CaptureTheme(
                page = page,
                camera = cameraTokens(appearance),
                type = TypeTokens(
                    displayFamily = appearance?.typography?.displayFamily.clean(),
                    fontFamily = appearance?.typography?.fontFamily.clean(),
                ),
                shape = ShapeTokens(
                    buttonRadiusDp = shape?.buttonRadius ?: radius ?: DEFAULT_BUTTON_RADIUS_DP,
                    buttonStyle = shape?.buttonStyle ?: AppearanceShape.ButtonStyle.FILLED,
                    dialogRadiusDp = radius ?: DEFAULT_DIALOG_RADIUS_DP,
                    sheetRadiusDp = radius?.let { min(2 * it, DEFAULT_SHEET_RADIUS_DP) } ?: DEFAULT_SHEET_RADIUS_DP,
                    tileRadiusDp = radius ?: DEFAULT_TILE_RADIUS_DP,
                ),
                logo = LogoTokens(
                    url = appearance?.logo?.url.clean(),
                    placement = appearance?.logo?.placement ?: AppearanceLogo.Placement.HEADER,
                    heightDp = appearance?.logo?.height?.takeIf { it > 0 } ?: DEFAULT_LOGO_HEIGHT_DP,
                ),
                backgroundImageUrl = appearance?.background?.imageUrl.clean(),
                icons = appearance?.icons,
                loader = LoaderTokens(
                    style = appearance?.loader?.style ?: AppearanceLoader.Style.SPINNER,
                    imageUrl = appearance?.loader?.imageUrl.clean(),
                ),
                isDark = dark,
            )
        }

        /**
         * Page palette. Neutral keys (background, surface, foreground, muted,
         * border) come from the mode's own layer only: `colors.*` in light,
         * `colors.dark.*` in dark, else the Sense default for that mode
         * ("derive nothing"). Brand keys (primary, primary foreground, success,
         * error, warning) take `colors.dark.*` in dark mode and otherwise the
         * base `colors.*`, so one brand colour serves both modes.
         */
        fun pageTokens(appearance: FlowAppearance?, dark: Boolean): PageTokens {
            val base = appearance?.colors
            val d = base?.dark
            val neutral: AppearanceColors? = if (dark) d else base
            val defaults = if (dark) SenseDefaults.DARK else SenseDefaults.LIGHT
            fun brand(pick: (AppearanceColors) -> String?): Int? =
                (if (dark) d?.let(pick).parse() else null) ?: base?.let(pick).parse()

            // background.color is a light value: it wins in light mode only.
            val background = (if (!dark) appearance?.background?.color.parse() else null)
                ?: neutral?.background.parse() ?: defaults.background
            val primary = brand { it.primary } ?: SenseDefaults.PRIMARY
            return PageTokens(
                background = background,
                surface = neutral?.surface.parse() ?: defaults.surface,
                foreground = neutral?.foreground.parse() ?: defaults.foreground,
                muted = neutral?.muted.parse() ?: defaults.muted,
                border = neutral?.border.parse() ?: defaults.border,
                primary = primary,
                primaryForeground = brand { it.primaryForeground } ?: CaptureColor.bestTextOn(primary),
                success = brand { it.success } ?: SenseDefaults.SUCCESS,
                error = brand { it.error } ?: SenseDefaults.ERROR,
                warning = brand { it.warning } ?: SenseDefaults.WARNING,
            )
        }

        /**
         * Camera accents (BRAND-v1 4.2). The camera is always dark, so a brand
         * colour reads from its dark-mode value when it has one.
         */
        fun cameraTokens(appearance: FlowAppearance?): CameraTokens {
            val c = appearance?.capture
            val colors = appearance?.colors
            fun brand(pick: (AppearanceColors) -> String?): Int? =
                colors?.dark?.let(pick).parse() ?: colors?.let(pick).parse()

            val primary = brand { it.primary }
            val ring = c?.ring.parse()?.takeIf(::passes)
                ?: primary?.takeIf(::passes)
                ?: primary?.let(::lightenUntilPasses)
                ?: SenseDefaults.PRIMARY
            return CameraTokens(
                ring = ring,
                challenge = c?.challenge.parse()?.takeIf(::passes) ?: SenseDefaults.CHALLENGE,
                warning = c?.warning.parse()?.takeIf(::passes)
                    ?: brand { it.warning }?.takeIf(::passes)
                    ?: SenseDefaults.WARNING,
                success = brand { it.success }?.takeIf(::passes) ?: SenseDefaults.SUCCESS,
            )
        }

        /** 3:1 against the camera reference grey. */
        fun passes(color: Int): Boolean =
            CaptureColor.contrast(color, CaptureFixed.CONTRAST_REFERENCE) >= CaptureFixed.MIN_CAMERA_CONTRAST

        /** HSL lightness += 5% per step (at most 8) until it passes; null if it never does. */
        fun lightenUntilPasses(color: Int): Int? {
            var c = color
            repeat(LIGHTEN_MAX_STEPS) {
                c = CaptureColor.lighten(c, LIGHTEN_STEP)
                if (passes(c)) return c
            }
            return null
        }

        private fun String?.parse(): Int? = this?.let(CaptureColor::parse)
        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Sense defaults (BRAND-v1 4.1). Dark is today's capture look. */
internal object SenseDefaults {
    data class Neutrals(val background: Int, val surface: Int, val foreground: Int, val muted: Int, val border: Int)

    val DARK = Neutrals(
        background = 0xFF12141B.toInt(),
        surface = 0xFF1D2029.toInt(),
        foreground = 0xFFF4F5F8.toInt(),
        muted = 0xFFA3A9B8.toInt(),
        border = 0x14FFFFFF, // rgba(255,255,255,0.08)
    )
    val LIGHT = Neutrals(
        background = 0xFFFDFCFA.toInt(),
        surface = 0xFFF5F3EF.toInt(),
        foreground = 0xFF1C1A17.toInt(),
        muted = 0xFF6B6760.toInt(),
        border = 0xFFE8E5DE.toInt(),
    )
    const val PRIMARY = 0xFF4F7CFF.toInt()
    const val SUCCESS = 0xFF00D4AA.toInt()
    const val ERROR = 0xFFFF6B4A.toInt()
    const val WARNING = 0xFFFFB84D.toInt()
    const val CHALLENGE = 0xFFA878FC.toInt()
    /** The darker of the two primary-foreground candidates. */
    const val INK = 0xFF1C1A17.toInt()
}

/**
 * The fixed list (BRAND-v1 4.2): never brandable, for security and
 * legibility. Every literal colour in the capture UI lives here or in
 * [SenseDefaults]; CaptureColourGuardTest fails on any other.
 */
internal object CaptureFixed {
    /** Every camera accent must reach [MIN_CAMERA_CONTRAST] against this. */
    const val CONTRAST_REFERENCE = 0xFF333333.toInt()
    const val MIN_CAMERA_CONTRAST = 3.0

    /** Camera background. */
    const val CAMERA_BACKGROUND = 0xFF0D0F14.toInt()
    /** Behind the upload / action sheet, over the blurred camera. */
    const val CAMERA_SCRIM = 0x400D0F14
    /** The same scrim where the camera can't blur (before Android 12): it hides the face instead. */
    const val CAMERA_SCRIM_OPAQUE = 0xE60D0F14.toInt()
    /** Flash Reflection's preview tint in the test rig (Flash Reflection colours are fixed). */
    const val FLASH_PREVIEW = 0x9900FFFF.toInt()
    /** Scan dim over the camera image. */
    const val SCAN_DIM = 0x1F0D0F14
    /** Oval guide fill and the ring track. */
    const val GUIDE_FILL = 0x24FFFFFF
    const val RING_TRACK = 0x2EFFFFFF
    /** The oval's neutral stroke (no face yet / shutter). */
    const val NEUTRAL_STROKE = 0xE6FFFFFF.toInt()
    /** Guidance text, its second line and their shadow. */
    const val GUIDANCE_TEXT = 0xFFFFFFFF.toInt()
    const val GUIDANCE_DETAIL = 0xD1FFFFFF.toInt()
    const val GUIDANCE_SHADOW = 0x73000000
    /** Head Turn arrow (white, like the guidance) and its shadow. */
    const val ARROW_SHADOW = 0x5A000000
    /** Camera chrome: back / help buttons and the older challenge captions. */
    const val CAMERA_TEXT_MUTED = 0x80FFFFFF.toInt()
    const val CAMERA_BUTTON = 0x8C0D0F14.toInt()
    /** Fill-light veil. */
    const val FILL_LIGHT = 0xFFFFF4E2.toInt()
    /** Speak Digits pill (monospace digits). */
    const val DIGITS_PILL = 0xB80D0F14.toInt()
    const val DIGITS_TEXT = 0xFFFFFFFF.toInt()
    /** Dim behind the leave and help dialogs. */
    const val DIALOG_SCRIM = 0x80000000.toInt()
    /** "Powered by Sense": the same grey on light and dark pages. */
    const val POWERED_BY = 0xFF8A8F9C.toInt()
}

/** Colour maths on ARGB ints (WCAG 2 luminance, HSL lightening), no Android dependency. */
internal object CaptureColor {
    /** `#RGB`, `#RRGGBB` or `#AARRGGBB` (the `#` is optional). Null for anything else. */
    fun parse(raw: String): Int? {
        val s = raw.trim().removePrefix("#")
        val argb = when (s.length) {
            3 -> "FF" + s.map { "$it$it" }.joinToString("")
            6 -> "FF$s"
            8 -> s
            else -> return null
        }
        return argb.toLongOrNull(16)?.toInt()
    }

    fun opaque(c: Int): Int = c or (0xFF shl 24)

    fun withAlpha(c: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (c and 0xFFFFFF)

    /** WCAG 2 contrast ratio between two colours (alpha ignored). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    /** White or Sense ink, whichever has the higher contrast on [background]. */
    fun bestTextOn(background: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        return if (contrast(white, background) >= contrast(SenseDefaults.INK, background)) white else SenseDefaults.INK
    }

    /** Raise HSL lightness by [amount] (0..1), keeping hue and saturation. Opaque result. */
    fun lighten(c: Int, amount: Float): Int {
        val hsl = toHsl(c)
        hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
        return fromHsl(hsl)
    }

    fun toHsl(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(hsl: FloatArray): Int {
        val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
        if (s == 0f) {
            val v = (l * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(tIn: Float): Float {
            var t = tIn
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val hn = h / 360f
        fun to255(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (to255(hue(hn + 1f / 3f)) shl 16) or (to255(hue(hn)) shl 8) or to255(hue(hn - 1f / 3f))
    }
}
