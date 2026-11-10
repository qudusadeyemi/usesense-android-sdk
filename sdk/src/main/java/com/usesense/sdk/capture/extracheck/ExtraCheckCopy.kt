package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.R
import com.usesense.sdk.api.models.HeadTurnStep

/** A guidance line: title and optional second line, as string resources. */
internal data class Guidance(val title: Int, val detail: Int? = null)

/** The extra check's words (storyboard copy). Pure, so it is unit tested. */
internal object ExtraCheckCopy {

    /** The sentence on the "One more quick check" sheet. */
    fun sheetBody(kind: ExtraCheckKind, steps: List<ExtraCheckStep>): Int = when (kind) {
        ExtraCheckKind.FOLLOW_DOT -> R.string.usesense_xc_sheet_dot
        ExtraCheckKind.SPEAK_DIGITS -> R.string.usesense_xc_sheet_digits
        ExtraCheckKind.HEAD_TURN -> {
            val sides = steps.mapNotNull { it.direction }
            val lr = sides.filter { it == HeadTurnStep.LEFT || it == HeadTurnStep.RIGHT }.distinct()
            when {
                sides.any { it == HeadTurnStep.UP || it == HeadTurnStep.DOWN } -> R.string.usesense_xc_sheet_head_other
                lr == listOf(HeadTurnStep.LEFT, HeadTurnStep.RIGHT) -> R.string.usesense_xc_sheet_head_left_right
                lr == listOf(HeadTurnStep.RIGHT, HeadTurnStep.LEFT) -> R.string.usesense_xc_sheet_head_right_left
                lr == listOf(HeadTurnStep.LEFT) -> R.string.usesense_xc_sheet_head_left
                lr == listOf(HeadTurnStep.RIGHT) -> R.string.usesense_xc_sheet_head_right
                else -> R.string.usesense_xc_sheet_head_other
            }
        }
    }

    /** What the guidance says right now. [words]: a Speak phrase that isn't digits. */
    fun guidance(runner: ExtraCheckRunner, words: Boolean = false): Guidance {
        if (runner.completed) return Guidance(R.string.usesense_xc_done)
        val kind = runner.kind
        if (runner.paused) {
            return when (kind) {
                ExtraCheckKind.HEAD_TURN -> Guidance(R.string.usesense_hint_centre, R.string.usesense_xc_paused_turn_detail)
                ExtraCheckKind.FOLLOW_DOT -> Guidance(R.string.usesense_hint_show_face, R.string.usesense_xc_dot_wait)
                ExtraCheckKind.SPEAK_DIGITS -> Guidance(R.string.usesense_hint_show_face, R.string.usesense_xc_paused_digits_detail)
            }
        }
        return when (kind) {
            ExtraCheckKind.HEAD_TURN -> headTurn(runner)
            ExtraCheckKind.FOLLOW_DOT -> Guidance(
                if (runner.stepIndex == 0) R.string.usesense_xc_follow_dot else R.string.usesense_xc_keep_following,
            )
            ExtraCheckKind.SPEAK_DIGITS -> if (runner.quiet) {
                Guidance(R.string.usesense_xc_cant_hear, R.string.usesense_xc_louder)
            } else {
                Guidance(if (words) R.string.usesense_xc_say_words else R.string.usesense_xc_say_numbers)
            }
        }
    }

    private fun headTurn(runner: ExtraCheckRunner): Guidance {
        val dir = runner.currentStep?.direction
        if (runner.retrying) {
            return Guidance(
                R.string.usesense_xc_again,
                when (dir) {
                    HeadTurnStep.RIGHT -> R.string.usesense_xc_further_right
                    else -> R.string.usesense_xc_further_left
                },
            )
        }
        return if (runner.stepIndex == 0) {
            val title = when (dir) {
                HeadTurnStep.RIGHT -> R.string.usesense_xc_turn_right
                HeadTurnStep.UP -> R.string.usesense_xc_turn_up
                HeadTurnStep.DOWN -> R.string.usesense_xc_turn_down
                HeadTurnStep.CENTER -> R.string.usesense_xc_turn_centre
                else -> R.string.usesense_xc_turn_left
            }
            Guidance(title, if (dir == HeadTurnStep.CENTER) null else R.string.usesense_xc_slowly)
        } else {
            Guidance(
                when (dir) {
                    HeadTurnStep.RIGHT -> R.string.usesense_xc_now_right
                    HeadTurnStep.UP -> R.string.usesense_xc_now_up
                    HeadTurnStep.DOWN -> R.string.usesense_xc_now_down
                    HeadTurnStep.CENTER -> R.string.usesense_xc_now_centre
                    else -> R.string.usesense_xc_now_left
                },
            )
        }
    }

    /** The digits as the pill shows them: "4 7 2 9" (the server already spaces them). */
    fun digitsText(phrase: String?): String {
        val p = phrase?.trim().orEmpty()
        if (p.isEmpty()) return ""
        return if (p.none { it.isLetter() }) p.filter { it.isDigit() }.toList().joinToString(" ") else p
    }
}
