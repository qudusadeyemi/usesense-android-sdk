package com.usesense.sdk.challenge

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.api.models.Waypoint
import org.junit.Assert.*
import org.junit.Test

class ChallengeResponseBuilderTest {

    @Test
    fun `follow_dot response includes waypoint_frames`() {
        val spec = ChallengeSpec(
            type = "follow_dot",
            seed = "test_seed_123",
            totalDurationMs = 6000,
            waypoints = listOf(
                Waypoint(0.5f, 0.5f, 1500, 0),
                Waypoint(0.8f, 0.2f, 1500, 1),
            ),
        )

        val builder = ChallengeResponseBuilder(spec)
        builder.markStarted()
        builder.setCurrentStep(0)
        builder.recordFrame(0, 0L)
        builder.recordFrame(1, 100L)
        builder.setCurrentStep(1)
        builder.recordFrame(2, 1600L)
        builder.recordFrame(3, 1700L)
        builder.markCompleted()

        val result = builder.buildAsMap()
        assertEquals("follow_dot", result["type"])
        assertEquals("test_seed_123", result["seed"])
        assertEquals(true, result["completed"])
        assertTrue(result.containsKey("waypoint_frames"))
        assertFalse(result.containsKey("step_frames"))

        @Suppress("UNCHECKED_CAST")
        val wpFrames = result["waypoint_frames"] as Map<String, List<Int>>
        assertEquals(2, wpFrames["0"]!!.size)
        assertEquals(2, wpFrames["1"]!!.size)
    }

    @Test
    fun `head_turn response includes step_frames`() {
        val spec = ChallengeSpec(
            type = "head_turn",
            seed = "head_seed_456",
            totalDurationMs = 5500,
        )

        val builder = ChallengeResponseBuilder(spec)
        builder.markStarted()
        builder.setCurrentStep(0)
        builder.recordFrame(0, 0L)
        builder.markCompleted()

        val result = builder.buildAsMap()
        assertEquals("head_turn", result["type"])
        assertTrue(result.containsKey("step_frames"))
        assertFalse(result.containsKey("waypoint_frames"))
    }

    @Test
    fun `seed is echoed back exactly`() {
        val seed = "exact_seed_value_789"
        val spec = ChallengeSpec(
            type = "follow_dot",
            seed = seed,
            totalDurationMs = 1500,
        )

        val builder = ChallengeResponseBuilder(spec)
        val result = builder.buildAsMap()
        assertEquals(seed, result["seed"])
    }

    @Test
    fun `frame_timestamps are recorded`() {
        val spec = ChallengeSpec(type = "follow_dot", seed = "s", totalDurationMs = 1000)
        val builder = ChallengeResponseBuilder(spec)
        builder.setCurrentStep(0)
        builder.recordFrame(0, 0L)
        builder.recordFrame(1, 100L)
        builder.recordFrame(2, 200L)

        val result = builder.buildAsMap()
        @Suppress("UNCHECKED_CAST")
        val timestamps = result["frame_timestamps"] as List<Long>
        assertEquals(3, timestamps.size)
        assertEquals(0L, timestamps[0])
        assertEquals(100L, timestamps[1])
        assertEquals(200L, timestamps[2])
    }
}
