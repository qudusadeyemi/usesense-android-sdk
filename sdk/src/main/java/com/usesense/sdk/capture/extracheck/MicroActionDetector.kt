package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.R
import kotlin.math.hypot

/**
 * Sees one RMAS micro-action (on-device attack detection, the inline
 * step-up) in the gate's live face mesh. The first frame is the baseline;
 * the action counts once a later frame moves far enough from it.
 * Pure, so it is unit tested.
 */
internal class MicroActionDetector(val action: String) {
    private var baseline: Float? = null
    var detected = false
        private set

    /**
     * One gate frame: [points] are x, y, z triples (image pixels), [turn] the
     * head turn in the mirrored view (ExtraCheckFace). True once the action is seen.
     */
    fun feed(
        points: FloatArray?,
        blinkLeft: Float?,
        blinkRight: Float?,
        turn: Float?,
    ): Boolean {
        if (detected) return true
        detected =
            when (action) {
                BLINK -> blinkLeft != null && blinkRight != null && blinkLeft >= BLINK_CLOSED && blinkRight >= BLINK_CLOSED
                TURN_LEFT -> turn != null && turn <= -ExtraCheckRunner.TURN_ENOUGH
                TURN_RIGHT -> turn != null && turn >= ExtraCheckRunner.TURN_ENOUGH
                else -> {
                    val v = measure(points) ?: return false
                    val base = baseline ?: run { baseline = v; return false }
                    when (action) {
                        SMILE -> v >= base * (1f + SMILE_WIDER)
                        RAISE_EYEBROWS -> v >= base * (1f + BROWS_HIGHER)
                        OPEN_MOUTH -> v >= base + MOUTH_OPENER
                        else -> false
                    }
                }
            }
        return detected
    }

    /** Smile: mouth width / face width. Brows: brow-to-eye / face height. Mouth: lip gap / face height. */
    private fun measure(p: FloatArray?): Float? {
        if (p == null || p.size < 455 * 3) return null
        fun d(
            a: Int,
            b: Int,
        ) = hypot(p[a * 3] - p[b * 3], p[a * 3 + 1] - p[b * 3 + 1])
        val faceH = d(10, 152).takeIf { it > 1f } ?: return null
        val faceW = d(234, 454).takeIf { it > 1f } ?: return null
        return when (action) {
            SMILE -> d(61, 291) / faceW
            RAISE_EYEBROWS -> (d(105, 159) + d(334, 386)) / 2f / faceH
            OPEN_MOUTH -> d(13, 14) / faceH
            else -> null
        }
    }

    companion object {
        const val BLINK = "blink"
        const val SMILE = "smile"
        const val RAISE_EYEBROWS = "raise_eyebrows"
        const val OPEN_MOUTH = "open_mouth"
        const val TURN_LEFT = "turn_left"
        const val TURN_RIGHT = "turn_right"

        /** Blendshape eyeBlink at which an eye counts as closed (the gate fails eyes-open at 0.6). */
        const val BLINK_CLOSED = 0.5f
        const val SMILE_WIDER = 0.12f
        const val BROWS_HIGHER = 0.10f
        /** Lip gap growth, in face heights. */
        const val MOUTH_OPENER = 0.06f

        /** The guided prompt for an action (plain guidance text under the oval). */
        fun prompt(action: String): Int =
            when (action) {
                BLINK -> R.string.usesense_inline_blink
                SMILE -> R.string.usesense_inline_smile
                RAISE_EYEBROWS -> R.string.usesense_inline_eyebrows
                OPEN_MOUTH -> R.string.usesense_inline_mouth
                TURN_LEFT -> R.string.usesense_xc_turn_left
                TURN_RIGHT -> R.string.usesense_xc_turn_right
                else -> R.string.usesense_hint_hold_still
            }
    }
}
