package com.usesense.sdk.capture.scan

import com.usesense.sdk.flows.FlowAppearance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colours of the capture guide: the progress ring, the contour lines (a
 * blend from [contourStart] to [contourEnd]) and the warning tone. Each comes
 * from FlowAppearance `capture` when set, else the Sense defaults. A custom
 * colour below 3:1 contrast on the dark camera background falls back to its
 * default, so a brand colour can never make the guide unreadable.
 *
 * Colours are ARGB ints. Parsing is done here (not android.graphics.Color)
 * so the rules are unit tested.
 */
internal data class ScanColors(
    val ring: Int,
    val contourStart: Int,
    val contourEnd: Int,
    val warning: Int,
) {
    companion object {
        const val DEFAULT_RING = 0xFF4F7CFF.toInt()
        const val DEFAULT_CONTOUR_START = 0xFF7A96FF.toInt()
        const val DEFAULT_CONTOUR_END = 0xFFA878FC.toInt()
        const val DEFAULT_WARNING = 0xFFFFB84D.toInt()

        /** The 60% black mask over a mid-grey camera image: the darkest-case backdrop the guide sits on. */
        const val CAMERA_BACKDROP = 0xFF333333.toInt()
        const val MIN_CONTRAST = 3.0

        val DEFAULT = ScanColors(DEFAULT_RING, DEFAULT_CONTOUR_START, DEFAULT_CONTOUR_END, DEFAULT_WARNING)

        fun resolve(appearance: FlowAppearance?): ScanColors {
            val c = appearance?.capture ?: return DEFAULT
            return ScanColors(
                ring = pick(c.ring, DEFAULT_RING),
                contourStart = pick(c.contourStart, DEFAULT_CONTOUR_START),
                contourEnd = pick(c.contourEnd, DEFAULT_CONTOUR_END),
                warning = pick(c.warning, DEFAULT_WARNING),
            )
        }

        private fun pick(custom: String?, fallback: Int): Int {
            val parsed = custom?.let(::parseHex) ?: return fallback
            return if (contrast(parsed, CAMERA_BACKDROP) >= MIN_CONTRAST) parsed else fallback
        }

        /** #RGB, #RRGGBB or #AARRGGBB. Alpha is dropped: the guide is always opaque. */
        fun parseHex(raw: String): Int? {
            val s = raw.trim().removePrefix("#")
            val rgb = when (s.length) {
                3 -> s.map { "$it$it" }.joinToString("")
                6 -> s
                8 -> s.substring(2)
                else -> return null
            }
            val v = rgb.toLongOrNull(16) ?: return null
            return (0xFF000000L or v).toInt()
        }

        /** WCAG 2 contrast ratio between two opaque colours. */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        private fun luminance(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
    }
}
