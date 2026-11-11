package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.challenge.ChallengeResponseBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed loop and step_events (v2, first move) from a synthetic pose
 * stream: one processed frame + one tick every 50 ms. Android's signs: a turn
 * to the person's left reads yaw > 0, up reads pitch < 0 (both measured on a
 * Galaxy A03).
 */
class ExtraCheckStepEventsTest {
    private data class Pose(val yaw: Double, val pitch: Double)

    /** Feed [pose] (a function of time) from [from] to [to]; returns every event. */
    private fun ExtraCheckRunner.feed(
        from: Long,
        to: Long,
        gate: Boolean = true,
        turnOnly: Boolean = false,
        untilStep: Int? = null,
        pose: (Long) -> Pose?,
    ): List<ExtraCheckEvent> {
        val events = mutableListOf<ExtraCheckEvent>()
        var t = from
        while (t < to && !completed && (untilStep == null || stepIndex < untilStep)) {
            t += 50
            val p = pose(t)
            if (p != null) observePose(t, p.yaw, p.pitch)
            events += tick(t, ExtraCheckInput(gatePasses = gate, yaw = p?.yaw, pitch = p?.pitch, turnOnlyFailing = turnOnly))
        }
        return events
    }

    /** Linear from [a] at [t0] to [b] at [t1], flat outside. */
    private fun ramp(
        t: Long,
        t0: Long,
        t1: Long,
        a: Double,
        b: Double,
    ): Double =
        when {
            t <= t0 -> a
            t >= t1 -> b
            else -> a + (b - a) * (t - t0) / (t1 - t0)
        }

    private val straight = Pose(0.0, 0.0)

    @Test
    fun `a head turn ends on reach plus hold and logs reaction and first move toward the target`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "left")))
        r.start(0)
        // Straight until 500 ms, then 2 degrees per 50 ms to the left (yaw > 0), up to 20.
        val events = r.feed(0, 3000) { t -> Pose(ramp(t, 500, 1000, 0.0, 20.0), 0.0) }
        assertEquals(listOf(ExtraCheckEvent.Completed), events)
        val e = r.stepLog.single()
        assertEquals(0L, e.promptedAt) // the runner's start
        assertEquals(900L, e.reachedAt) // yaw 16 >= 15 at 900 ms
        assertEquals(1450L, e.endedAt) // the 600 ms hold (counted per tick from the reaching tick)
        assertEquals(true, e.reached)
        assertFalse(e.hinted)
        // First pose more than 4 degrees from the prompt's: yaw 6 at 650 ms, toward the target.
        assertEquals(6.0, e.firstMoveDeg!!, 1e-9)
        assertEquals(650L, e.firstMoveMs)
        assertEquals(0.0, e.maxAwayDeg!!, 1e-9)
    }

    @Test
    fun `a step that moves the wrong way first logs a negative first move and how far away it went`() {
        val steps = listOf(ExtraCheckStep(2000, direction = "left"), ExtraCheckStep(2000, direction = "right"))
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, steps)
        r.start(0)
        // Left (yaw 20) reached and held: step 0 ends at 1450 with the head at yaw 20.
        r.feed(0, 1450) { t -> Pose(ramp(t, 500, 1000, 0.0, 20.0), 0.0) }
        assertEquals(1, r.stepIndex)
        // Right asked: first further LEFT to yaw 28 (away), then across to -20 (right).
        r.feed(1450, 6000) { t ->
            Pose(if (t <= 1850) ramp(t, 1450, 1850, 20.0, 28.0) else ramp(t, 1850, 2850, 28.0, -20.0), 0.0)
        }
        assertTrue(r.completed)
        val e = r.stepLog[1]
        assertEquals(1450L, e.promptedAt)
        assertEquals(true, e.reached)
        // yaw 25 at 1700 ms: 5 degrees away from the right.
        assertEquals(-5.0, e.firstMoveDeg!!, 1e-9)
        assertEquals(250L, e.firstMoveMs)
        assertEquals(8.0, e.maxAwayDeg!!, 1e-9)
        // Reached when yaw <= -15: 28 - 48 * (t - 1850) / 1000 <= -15 -> t >= 2745.8 -> the 2750 ms tick.
        assertEquals(2750L, e.reachedAt)
        assertEquals(1300L, e.reachedAt!! - e.promptedAt)
    }

    @Test
    fun `up and down are judged on pitch`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(1500, direction = "down")))
        r.start(0)
        // Level at the prompt, a small rise first (away), then down to -15.
        r.feed(0, 4000) { t -> Pose(0.0, -(if (t <= 500) ramp(t, 100, 500, 0.0, 5.0) else ramp(t, 500, 1100, 5.0, -15.0))) }
        val e = r.stepLog.single()
        assertEquals(true, e.reached)
        assertTrue("moved away first", e.firstMoveDeg!! < 0)
        assertEquals(5.0, e.maxAwayDeg!!, 1e-9)
    }

    @Test
    fun `not there after 4 s gives the one hint, the same again moves on as timed out`() {
        val steps = listOf(ExtraCheckStep(2000, direction = "left"), ExtraCheckStep(2000, direction = "up"))
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, steps)
        r.start(0)
        val first = r.feed(0, 4050) { straight }
        assertEquals(listOf(ExtraCheckEvent.Step(0, retry = true)), first)
        assertTrue(r.retrying)
        val second = r.feed(4050, 8050) { straight }
        assertEquals(listOf(ExtraCheckEvent.Step(1, retry = false)), second)
        val e = r.stepLog.single()
        assertEquals(false, e.reached)
        assertTrue(e.hinted)
        assertNull(e.reachedAt)
        assertEquals(8000L, e.endedAt - e.promptedAt)
        assertNull("never moved 4 degrees", e.firstMoveDeg)
        assertEquals(0.0, e.maxAwayDeg!!, 1e-9)
        // A timed-out step leaves its ring segment empty.
        assertEquals(0f, r.progress, 0.001f)
        // The one hint is spent: the next step times out after 4 s with no hint.
        val third = r.feed(8050, 12_100) { straight }
        assertEquals(listOf(ExtraCheckEvent.Completed), third)
        assertFalse(r.stepLog[1].hinted)
        assertEquals(4000L, r.stepLog[1].endedAt - r.stepLog[1].promptedAt)
    }

    @Test
    fun `the face leaving the oval stops the step clock`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "left")))
        r.start(0)
        r.feed(0, 1000) { straight }
        r.feed(1000, 20_000, gate = false) { null }
        assertTrue(r.paused)
        assertTrue(r.stepLog.isEmpty())
        r.feed(20_000, 22_000) { Pose(20.0, 0.0) }
        assertEquals(true, r.stepLog.single().reached)
    }

    @Test
    fun `a held big turn that fails the gate's centring doesn't pause the check`() {
        // A03 run: a right turn of 23 to 34 degrees failed CENTRE and reset every hold.
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "right")))
        r.start(0)
        r.feed(0, 300) { straight }
        val events = r.feed(300, 3000, gate = false, turnOnly = true) { Pose(-30.0, 0.0) }
        assertFalse(ExtraCheckEvent.Paused in events)
        assertEquals(true, r.stepLog.single().reached)
    }

    @Test
    fun `a turn-caused gate failure still pauses when the head isn't on target`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "right")))
        r.start(0)
        val events = r.feed(0, 1000, gate = false, turnOnly = true) { Pose(30.0, 0.0) } // the wrong way
        assertTrue(ExtraCheckEvent.Paused in events)
    }

    @Test
    fun `any other gate failure pauses even on target`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "right")))
        r.start(0)
        val events = r.feed(0, 1000, gate = false, turnOnly = false) { Pose(-30.0, 0.0) } // e.g. face covered
        assertTrue(ExtraCheckEvent.Paused in events)
        assertTrue(r.stepLog.isEmpty())
    }

    @Test
    fun `up is pitch below zero on Android`() {
        assertEquals(true, ExtraCheckRunner.atTarget("up", 0.0, -25.0))
        assertEquals(false, ExtraCheckRunner.atTarget("up", 0.0, 25.0))
        assertEquals(true, ExtraCheckRunner.atTarget("down", 0.0, 30.9))
    }

    @Test
    fun `frames bank only on target for a head turn`() {
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, listOf(ExtraCheckStep(2000, direction = "right")))
        r.start(0)
        r.feed(0, 300) { straight }
        assertFalse(r.banksNow)
        r.feed(300, 400) { Pose(-20.0, 0.0) }
        assertTrue(r.banksNow)
    }

    private val dots =
        listOf(
            ExtraCheckStep(1500, x = 0.5f, y = 0.5f),
            // Right.
            ExtraCheckStep(1500, x = 0.85f, y = 0.45f),
            // Up.
            ExtraCheckStep(1500, x = 0.8f, y = 0.1f),
        )

    @Test
    fun `a follow dot waypoint can't be reached before the glide ends, and waypoint 0 has no first move`() {
        val r = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, dots)
        r.start(0)
        // Straight for waypoint 0 (the reference).
        r.feed(0, 2000, untilStep = 1) { straight }
        assertEquals(1, r.stepIndex)
        val w0 = r.stepLog[0]
        assertTrue("the glide comes first", w0.reachedAt!! - w0.promptedAt >= ExtraCheckRunner.DOT_GLIDE_MS)
        assertNull(w0.firstMoveDeg)
        assertNull(w0.firstMoveMs)
        assertNull(w0.maxAwayDeg)
        // Waypoint 1 (right): the head snaps to the person's right (yaw < 0) at once.
        val p1 = r.stepLog[0].endedAt
        r.feed(p1, p1 + 3000, untilStep = 2) { t -> if (t < p1 + 100) straight else Pose(-15.0, 0.0) }
        val w1 = r.stepLog[1]
        assertEquals(true, w1.reached)
        assertTrue("reached only after the glide", w1.reachedAt!! - w1.promptedAt >= ExtraCheckRunner.DOT_GLIDE_MS)
        assertEquals(15.0, w1.firstMoveDeg!!, 1e-9)
        assertEquals(100L, w1.firstMoveMs)
        // Waypoint 2 (up, judged on pitch from the pose held at waypoint 1): the head dips first, then rises.
        val p2 = w1.endedAt
        r.feed(p2, p2 + 6000) { t ->
            Pose(-15.0, -(if (t <= p2 + 300) ramp(t, p2, p2 + 300, 0.0, -6.0) else ramp(t, p2 + 300, p2 + 900, -6.0, 12.0)))
        }
        assertTrue(r.completed)
        val w2 = r.stepLog[2]
        assertEquals(true, w2.reached)
        assertTrue("dipped first: away from the dot", w2.firstMoveDeg!! < 0)
        assertEquals(6.0, w2.maxAwayDeg!!, 1e-9)
    }

    @Test
    fun `a follow dot move the head doesn't follow times out after the hint`() {
        val r = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, dots.take(2))
        r.start(0)
        r.feed(0, 2000, untilStep = 1) { straight }
        // The dot goes right; the head turns left (yaw > 0) and stays there.
        r.feed(r.stepLog[0].endedAt, 20_000) { Pose(12.0, 0.0) }
        val w1 = r.stepLog[1]
        assertEquals(false, w1.reached)
        assertTrue(w1.hinted)
        assertEquals(-12.0, w1.firstMoveDeg!!, 1e-9)
        assertEquals(12.0, w1.maxAwayDeg!!, 1e-9)
    }

    @Test
    fun `no pose from the tracker runs each step for the server's time`() {
        val r = ExtraCheckRunner(ExtraCheckKind.FOLLOW_DOT, dots)
        r.start(0)
        r.feed(0, 10_000) { null }
        assertTrue(r.completed)
        assertEquals(listOf<Boolean?>(null, null, null), r.stepLog.map { it.reached })
        assertEquals(1500L, r.stepLog[1].endedAt - r.stepLog[1].promptedAt)
        assertTrue(r.banksNow)
    }

    @Test
    fun `step events go on the wire in epoch ms with the v2 fields`() {
        val steps = listOf(ExtraCheckStep(2000, direction = "left"), ExtraCheckStep(2000, direction = "up"))
        val r = ExtraCheckRunner(ExtraCheckKind.HEAD_TURN, steps)
        r.start(0)
        r.feed(0, 1450) { t -> Pose(ramp(t, 500, 1000, 0.0, 20.0), 0.0) }
        r.feed(1450, 20_000) { Pose(20.0, 0.0) } // never looks up: hint, then timed out
        val spec = ChallengeSpec(type = ChallengeSpec.TYPE_HEAD_TURN, seed = "s", totalDurationMs = 4000)
        val builder = ChallengeResponseBuilder(spec)
        val offset = 1_700_000_000_000L
        builder.setStepEvents(r.stepLog, offset)

        val events = builder.buildAsMap()["step_events"] as List<*>
        val reached = events[0] as Map<*, *>
        assertEquals(0, reached["index"])
        assertEquals(offset, reached["prompted_at_ms"])
        assertEquals(offset + 900, reached["reached_at_ms"])
        assertEquals(900L, reached["reaction_ms"])
        assertEquals(offset + 1450, reached["ended_at_ms"])
        assertEquals("reached", reached["outcome"])
        assertEquals(false, reached["hinted"])
        assertEquals(6.0, reached["first_move_deg"])
        assertEquals(650L, reached["first_move_ms"])
        assertEquals(0.0, reached["max_away_deg"])

        val missed = events[1] as Map<*, *>
        assertEquals("timed_out", missed["outcome"])
        assertEquals(true, missed["hinted"])
        assertFalse(missed.containsKey("reached_at_ms"))
        assertFalse(missed.containsKey("reaction_ms"))
        assertFalse("never moved 4 degrees", missed.containsKey("first_move_deg"))

        // And in the JSON the session uploads.
        val json = builder.build().getJSONArray("step_events")
        assertEquals(2, json.length())
        assertEquals("reached", json.getJSONObject(0).getString("outcome"))
        assertEquals(6.0, json.getJSONObject(0).getDouble("first_move_deg"), 0.0)
    }

    @Test
    fun `no step events, no key`() {
        val spec = ChallengeSpec(type = ChallengeSpec.TYPE_FOLLOW_DOT, seed = "s", totalDurationMs = 4000)
        assertFalse(ChallengeResponseBuilder(spec).buildAsMap().containsKey("step_events"))
    }

    @Test
    fun `an unbanked frame keeps its timestamp but is no step's evidence`() {
        val spec = ChallengeSpec(type = ChallengeSpec.TYPE_HEAD_TURN, seed = "s", totalDurationMs = 4000)
        val b = ChallengeResponseBuilder(spec)
        b.setCurrentStep(0)
        b.bankFrames = false
        b.recordFrame(0, 10L)
        b.bankFrames = true
        b.recordFrame(1, 20L)
        val m = b.buildAsMap()
        assertEquals(listOf(10L, 20L), m["frame_timestamps"])
        assertEquals(mapOf("0" to listOf(1)), m["step_frames"])
    }
}
