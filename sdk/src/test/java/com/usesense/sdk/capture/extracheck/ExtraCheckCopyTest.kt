package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The storyboard's words for each extra-check state. */
class ExtraCheckCopyTest {
    private fun steps(vararg d: String) = d.map { ExtraCheckStep(1000, direction = it) }

    @Test
    fun `the sheet sentence follows the challenge`() {
        assertEquals(R.string.usesense_xc_sheet_head_left_right, ExtraCheckCopy.sheetBody(ExtraCheckKind.HEAD_TURN, steps("left", "right")))
        assertEquals(
            R.string.usesense_xc_sheet_head_right_left,
            ExtraCheckCopy.sheetBody(ExtraCheckKind.HEAD_TURN, steps("right", "left", "right")),
        )
        assertEquals(R.string.usesense_xc_sheet_head_left, ExtraCheckCopy.sheetBody(ExtraCheckKind.HEAD_TURN, steps("left", "center")))
        assertEquals(R.string.usesense_xc_sheet_head_other, ExtraCheckCopy.sheetBody(ExtraCheckKind.HEAD_TURN, steps("left", "up")))
        assertEquals(R.string.usesense_xc_sheet_dot, ExtraCheckCopy.sheetBody(ExtraCheckKind.FOLLOW_DOT, emptyList()))
        assertEquals(R.string.usesense_xc_sheet_digits, ExtraCheckCopy.sheetBody(ExtraCheckKind.SPEAK_DIGITS, emptyList()))
    }

    private fun runner(
        kind: ExtraCheckKind,
        steps: List<ExtraCheckStep>,
    ) = ExtraCheckRunner(kind, steps).also { it.start(0) }

    private fun ExtraCheckRunner.at(
        ms: Long,
        input: ExtraCheckInput,
    ): ExtraCheckRunner {
        var t = 0L
        while (t < ms) {
            t += 50
            tick(t, input)
        }
        return this
    }

    @Test
    fun `head turn says the side, then now to the other, and what to fix`() {
        val left = ExtraCheckInput(true, turn = -0.5f)
        val r = runner(ExtraCheckKind.HEAD_TURN, steps("left", "right"))
        assertEquals(Guidance(R.string.usesense_xc_turn_left, R.string.usesense_xc_slowly), ExtraCheckCopy.guidance(r))
        r.at(1050, left)
        assertEquals(Guidance(R.string.usesense_xc_now_right), ExtraCheckCopy.guidance(r))
        val away = runner(ExtraCheckKind.HEAD_TURN, steps("left", "right")).at(500, ExtraCheckInput(false))
        assertEquals(Guidance(R.string.usesense_hint_centre, R.string.usesense_xc_paused_turn_left), ExtraCheckCopy.guidance(away))
        val missed = runner(ExtraCheckKind.HEAD_TURN, steps("left", "right")).at(1050, ExtraCheckInput(true, turn = 0f))
        assertEquals(Guidance(R.string.usesense_xc_again, R.string.usesense_xc_further_left), ExtraCheckCopy.guidance(missed))
    }

    @Test
    fun `follow dot and digits copy`() {
        val dot = runner(ExtraCheckKind.FOLLOW_DOT, listOf(ExtraCheckStep(1000), ExtraCheckStep(1000)))
        assertEquals(Guidance(R.string.usesense_xc_follow_dot), ExtraCheckCopy.guidance(dot))
        dot.at(1050, ExtraCheckInput(true))
        assertEquals(Guidance(R.string.usesense_xc_keep_following), ExtraCheckCopy.guidance(dot))
        dot.at(1500, ExtraCheckInput(false))
        assertEquals(Guidance(R.string.usesense_hint_show_face, R.string.usesense_xc_dot_wait), ExtraCheckCopy.guidance(dot))

        val digits = runner(ExtraCheckKind.SPEAK_DIGITS, listOf(ExtraCheckStep(4000)))
        assertEquals(Guidance(R.string.usesense_xc_say_numbers), ExtraCheckCopy.guidance(digits))
        digits.at(2100, ExtraCheckInput(true, micLevel = 0f))
        assertEquals(Guidance(R.string.usesense_xc_cant_hear, R.string.usesense_xc_louder), ExtraCheckCopy.guidance(digits))
        digits.at(20_000, ExtraCheckInput(true, micLevel = 0.8f))
        assertEquals(Guidance(R.string.usesense_xc_done), ExtraCheckCopy.guidance(digits))
    }

    @Test
    fun `digits show spaced`() {
        assertEquals("4 7 2 9", ExtraCheckCopy.digitsText("4729"))
        assertEquals("3 8 1 7 0 2", ExtraCheckCopy.digitsText("3 8 1 7 0 2"))
        assertEquals("My voice", ExtraCheckCopy.digitsText("My voice"))
        assertEquals("", ExtraCheckCopy.digitsText(null))
    }
}
