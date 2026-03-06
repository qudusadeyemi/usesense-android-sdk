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

        val json = builder.build()
        assertEquals("follow_dot", json.getString("type"))
        assertEquals("test_seed_123", json.getString("seed"))
        assertTrue(json.getBoolean("completed"))
        assertTrue(json.has("waypoint_frames"))
        assertFalse(json.has("step_frames"))

        val wpFrames = json.getJSONObject("waypoint_frames")
        assertEquals(2, wpFrames.getJSONArray("0").length())
        assertEquals(2, wpFrames.getJSONArray("1").length())
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

        val json = builder.build()
        assertEquals("head_turn", json.getString("type"))
        assertTrue(json.has("step_frames"))
        assertFalse(json.has("waypoint_frames"))
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
        val json = builder.build()
        assertEquals(seed, json.getString("seed"))
    }

    @Test
    fun `frame_timestamps are recorded`() {
        val spec = ChallengeSpec(type = "follow_dot", seed = "s", totalDurationMs = 1000)
        val builder = ChallengeResponseBuilder(spec)
        builder.setCurrentStep(0)
        builder.recordFrame(0, 0L)
        builder.recordFrame(1, 100L)
        builder.recordFrame(2, 200L)

        val json = builder.build()
        val timestamps = json.getJSONArray("frame_timestamps")
        assertEquals(3, timestamps.length())
        assertEquals(0L, timestamps.getLong(0))
        assertEquals(100L, timestamps.getLong(1))
        assertEquals(200L, timestamps.getLong(2))
    }
}
