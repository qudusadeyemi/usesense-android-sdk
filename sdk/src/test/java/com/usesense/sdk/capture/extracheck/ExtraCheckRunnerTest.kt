package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.api.models.HeadTurnStep
import com.usesense.sdk.api.models.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extra check's clock (storyboard flows 4 and 5, decisions on pause and retry). */
class ExtraCheckRunnerTest {
    private val leftRight = listOf(ExtraCheckStep(2000, direction = "left"), ExtraCheckStep(2000, direction = "right"))
    private val turnedLeft = ExtraCheckInput(faceInOval = true, turn = -0.4f)
    private val turnedRight = ExtraCheckInput(faceInOval = true, turn = 0.4f)
    private val still = ExtraCheckInput(faceInOval = true, turn = 0f)
    private val away = ExtraCheckInput(faceInOval = false)

    /** Tick every 50 ms from [from] up to [to]; returns every event. */
    private fun ExtraCheckRunner.run(
        from: Long,
        to: Long,
        input: ExtraCheckInput,
    ): List<ExtraCheckEvent> {
        val events = mutableListOf<ExtraCheckEvent>()
        var t = from
        while (t < to) {
            t += 50
            events += tick(t, input)
        }
        return events
    }

    @Test
    fun `a head turn done right runs each step once and completes`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, leftRight)
        assertEquals(listOf(ExtraCheckEvent.Step(0, retry = false)), r.start(0))
        val events = r.run(0, 2000, turnedLeft) + r.run(2000, 4000, turnedRight)
        assertEquals(listOf(ExtraCheckEvent.Step(1, retry = false), ExtraCheckEvent.Completed), events)
        assertTrue(r.completed)
        assertFalse(r.retryUsed)
        assertEquals(1f, r.progress, 0f)
    }

    @Test
    fun `a missed turn gets one retry with the hint, then the server decides`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, leftRight)
        r.start(0)
        val first = r.run(0, 2000, still)
        assertEquals(listOf(ExtraCheckEvent.Step(0, retry = true)), first)
        assertTrue(r.retrying)
        // The retry adds its step to the plan: the ring steps back a little, never past full.
        assertEquals(0.5f / 1.5f * 1.0f, r.progress, 0.02f)
        // Missed again: no second retry, on to the next step.
        val second = r.run(2000, 4000, still)
        assertEquals(listOf(ExtraCheckEvent.Step(1, retry = false)), second)
        assertFalse(r.retrying)
        // The right turn is missed too, but the one retry is spent.
        val third = r.run(4000, 6000, still)
        assertEquals(listOf(ExtraCheckEvent.Completed), third)
    }

    @Test
    fun `a turn to the wrong side counts as missed`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, leftRight)
        r.start(0)
        assertEquals(listOf(ExtraCheckEvent.Step(0, retry = true)), r.run(0, 2000, turnedRight))
    }

    @Test
    fun `up, down and centre steps are left to the server`() {
        val steps = listOf(ExtraCheckStep(1000, direction = "up"), ExtraCheckStep(1000, direction = "center"))
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, steps)
        r.start(0)
        assertEquals(listOf(ExtraCheckEvent.Step(1, retry = false), ExtraCheckEvent.Completed), r.run(0, 2000, still))
    }

    @Test
    fun `the face leaving the oval pauses the check and time stops`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, leftRight)
        r.start(0)
        r.run(0, 1000, turnedLeft)
        val progress = r.progress
        val out = r.run(1000, 6000, away)
        assertEquals(listOf(ExtraCheckEvent.Paused), out)
        assertTrue(r.paused)
        assertEquals(0, r.stepIndex)
        // Only the 300 ms before the pause counted.
        assertEquals(progress + 300f / 4000f, r.progress, 0.02f)
        val back = r.run(6000, 6400, turnedLeft)
        assertEquals(listOf(ExtraCheckEvent.Resumed), back)
        assertFalse(r.paused)
        // It never fails on the device: it carries on and completes.
        val rest = r.run(6400, 12000, turnedLeft) + r.run(12000, 14000, turnedRight)
        assertTrue(ExtraCheckEvent.Completed in rest)
    }

    @Test
    fun `a face lost for one frame does not pause`() {
        val r = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, listOf(ExtraCheckStep(1500)))
        r.start(0)
        r.run(0, 500, still)
        assertTrue(r.run(500, 700, away).isEmpty())
        assertFalse(r.paused)
    }

    @Test
    fun `the dot waits while the face is away`() {
        val dots = listOf(ExtraCheckStep(1000, x = 0.2f, y = 0.2f), ExtraCheckStep(1000, x = 0.8f, y = 0.8f))
        val r = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, dots)
        r.start(0)
        r.run(0, 500, still)
        r.run(500, 20_000, away)
        assertEquals(0, r.stepIndex)
        assertFalse(r.completed)
        val events = r.run(20_000, 21_000, still)
        assertEquals(listOf(ExtraCheckEvent.Resumed, ExtraCheckEvent.Step(1, retry = false)), events)
        // No retry for Follow Dot.
        assertFalse(r.retryUsed)
    }

    @Test
    fun `speak digits says we can't hear you after 2 s of a flat meter`() {
        val r = ExtraCheckRunner(ExtraCheckKind.SPEAK_DIGITS, listOf(ExtraCheckStep(4000)))
        r.start(0)
        val quiet = r.run(0, 2100, ExtraCheckInput(faceInOval = true, micLevel = 0.02f))
        assertEquals(listOf(ExtraCheckEvent.Quiet), quiet)
        assertTrue(r.quiet)
        val heard = r.run(2100, 2200, ExtraCheckInput(faceInOval = true, micLevel = 0.6f))
        assertEquals(listOf(ExtraCheckEvent.Heard), heard)
        assertFalse(r.quiet)
    }

    @Test
    fun `a silent microphone holds the digits at most 6 s, then it completes`() {
        val r = ExtraCheckRunner(ExtraCheckKind.SPEAK_DIGITS, listOf(ExtraCheckStep(4000)))
        r.start(0)
        val events = r.run(0, 9_900, ExtraCheckInput(faceInOval = true, micLevel = 0f))
        assertFalse(ExtraCheckEvent.Completed in events)
        val later = r.run(9_900, 10_200, ExtraCheckInput(faceInOval = true, micLevel = 0f))
        assertTrue(ExtraCheckEvent.Completed in later)
        assertFalse("done is never shown amber", r.quiet)
    }

    @Test
    fun `hold freezes time while a dialog is open`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, leftRight)
        r.start(0)
        r.run(0, 1000, turnedLeft)
        val before = r.progress
        r.hold(30_000)
        r.tick(30_050, turnedLeft)
        assertEquals(before + 50f / 4000f, r.progress, 0.001f)
    }

    @Test
    fun `a stalled tick can't jump the timer`() {
        val r = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, listOf(ExtraCheckStep(1000), ExtraCheckStep(1000)))
        r.start(0)
        r.tick(10_000, still)
        assertEquals(ExtraCheckRunner.MAX_TICK_MS / 2000f, r.progress, 0.001f)
    }

    @Test
    fun `steps come from the spec in index order, and an empty spec still runs`() {
        val head =
            ChallengeSpec(
                type = "head_turn",
                seed = "s",
                totalDurationMs = 3000,
                sequence = listOf(HeadTurnStep("right", 1500, 1), HeadTurnStep("left", 1500, 0)),
            )
        assertEquals(listOf("left", "right"), ExtraCheckRunner.stepsFor(head).map { it.direction })
        val dot =
            ChallengeSpec(
                type = "follow_dot",
                seed = "s",
                totalDurationMs = 1000,
                waypoints = listOf(Waypoint(0.1f, 0.9f, 1000, 0)),
            )
        assertEquals(0.9f, ExtraCheckRunner.stepsFor(dot).single().y)
        val digits = ChallengeSpec(type = "speak_phrase", seed = "s", totalDurationMs = 5000, phrase = "4 7 2 9 1")
        assertEquals(5000L, ExtraCheckRunner.stepsFor(digits).single().durationMs)
        val empty = ChallengeSpec(type = "head_turn", seed = "s", totalDurationMs = 2500)
        assertEquals(2500L, ExtraCheckRunner.stepsFor(empty).single().durationMs)
        assertEquals(null, ExtraCheckKind.of("screen_illumination"))
    }
}
