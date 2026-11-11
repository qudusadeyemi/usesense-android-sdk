package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.R
import com.usesense.sdk.api.models.HeadTurnStep
import com.usesense.sdk.capture.scan.GateHint

/** A guidance line: title and optional second line, as string resources. */
internal data class Guidance(val title: Int, val detail: Int? = null)

/** The extra check's words (storyboard copy). Pure, so it is unit tested. */
internal object ExtraCheckCopy {
    /** The sentence on the "One more quick check" sheet. */
    fun sheetBody(
        kind: ExtraCheckKind,
        steps: List<ExtraCheckStep>,
    ): Int =
        when (kind) {
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
    /**
     * [pausedBy]: the gate check holding a paused movement (the scan's
     * guidance names it, the second line says the movement waits).
     */
    fun guidance(
        runner: ExtraCheckRunner,
        words: Boolean = false,
        pausedBy: GateHint? = null,
    ): Guidance {
        if (runner.completed) return Guidance(R.string.usesense_xc_done)
        val kind = runner.kind
        if (runner.paused) {
            val (fallback, detail) =
                when (kind) {
                    ExtraCheckKind.HEAD_TURN -> R.string.usesense_hint_centre to R.string.usesense_xc_paused_turn_detail
                    ExtraCheckKind.FOLLOW_DOT -> R.string.usesense_hint_show_face to R.string.usesense_xc_dot_wait
                    ExtraCheckKind.SPEAK_DIGITS -> R.string.usesense_hint_show_face to R.string.usesense_xc_paused_digits_detail
                }
            return Guidance(pausedBy?.let(::hintTitle) ?: fallback, detail)
        }
        return when (kind) {
            ExtraCheckKind.HEAD_TURN -> headTurn(runner)
            ExtraCheckKind.FOLLOW_DOT ->
                Guidance(
                    if (runner.stepIndex == 0) R.string.usesense_xc_follow_dot else R.string.usesense_xc_keep_following,
                )
            ExtraCheckKind.SPEAK_DIGITS ->
                if (runner.quiet) {
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
            val title =
                when (dir) {
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

    /** The scan's words for a failing gate check (the light checks never pause, so they have none). */
    fun hintTitle(hint: GateHint): Int? =
        when (hint) {
            GateHint.SHOW_FACE, GateHint.DARK_NO_FACE, GateHint.FILLING_NO_FACE -> R.string.usesense_hint_show_face
            GateHint.ONE_PERSON -> R.string.usesense_hint_one_person
            GateHint.FACE_COVERED -> R.string.usesense_hint_face_covered
            GateHint.CENTRE -> R.string.usesense_hint_centre
            GateHint.MOVE_CLOSER -> R.string.usesense_hint_move_closer
            GateHint.MOVE_BACK, GateHint.TOO_BIG -> R.string.usesense_hint_move_back
            GateHint.LOOK_STRAIGHT -> R.string.usesense_hint_look_straight
            GateHint.HOLD_STEADY -> R.string.usesense_hint_hold_steady
            GateHint.EYES_OPEN -> R.string.usesense_hint_eyes_open
            GateHint.HOLD_STILL -> R.string.usesense_hint_hold_still
            GateHint.BRIGHTENING, GateHint.FACE_LIGHT, GateHint.TOO_BRIGHT -> null
        }

    /** The digits as the pill shows them: "4 7 2 9" (the server already spaces them). */
    fun digitsText(phrase: String?): String {
        val p = phrase?.trim().orEmpty()
        if (p.isEmpty()) return ""
        return if (p.none { it.isLetter() }) p.filter { it.isDigit() }.toList().joinToString(" ") else p
    }
}
