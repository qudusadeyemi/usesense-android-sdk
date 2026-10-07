package com.usesense.sdk.finalization

import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.api.models.ChallengeSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server step-up (round 2): the /signals response can ask for one more
 * challenge in the same session. Contract: usesense-watchtower
 * docs/sdk/step-up-protocol.md.
 */
class StepUpRoundTest {
    private val result = UseSenseResult("session", "enrollment", null, "APPROVE", "now")

    // Moshi decodes JSON numbers in a loose map as Double.
    private val headTurn: Map<String, Any?> = mapOf(
        "type" to "head_turn",
        "seed" to "abc123",
        "total_duration_ms" to 3000.0,
        "frames_per_step" to 2.0,
        "sequence" to listOf(
            mapOf("direction" to "left", "duration_ms" to 1500.0, "index" to 0.0),
            mapOf("direction" to "right", "duration_ms" to 1500.0, "index" to 1.0),
        ),
    )

    @Test
    fun `parses a head_turn step-up with the server frame budget`() {
        val instruction = StepUpParser.parse(mapOf("round" to 2.0, "challenge" to headTurn, "upload" to mapOf("max_frames" to 18.0)))
        assertNotNull(instruction)
        assertEquals(ChallengeSpec.TYPE_HEAD_TURN, instruction!!.challenge.type)
        assertEquals("abc123", instruction.challenge.seed)
        assertEquals(2, instruction.challenge.sequence!!.size)
        assertEquals(18, instruction.maxFrames)
    }

    @Test
    fun `parses follow_dot and defaults the frame budget`() {
        val followDot = mapOf(
            "type" to "follow_dot", "seed" to "s", "total_duration_ms" to 2000.0,
            "waypoints" to listOf(mapOf("x" to 0.2, "y" to 0.8, "duration_ms" to 1000.0, "index" to 0.0)),
        )
        val instruction = StepUpParser.parse(mapOf("round" to 2.0, "challenge" to followDot))
        assertEquals(0.2f, instruction!!.challenge.waypoints!![0].x)
        assertEquals(StepUpParser.DEFAULT_MAX_FRAMES, instruction.maxFrames)
    }

    @Test
    fun `ignores what this SDK cannot render`() {
        assertNull(StepUpParser.parse(null))
        assertNull(StepUpParser.parse(mapOf("round" to 3.0, "challenge" to headTurn)))
        assertNull(StepUpParser.parse(mapOf("round" to 2.0, "challenge" to headTurn + ("type" to "speak_phrase"))))
        assertNull(StepUpParser.parse(mapOf("round" to 2.0, "challenge" to headTurn + ("seed" to ""))))
        assertNull(StepUpParser.parse(mapOf("round" to 2.0, "challenge" to headTurn + ("sequence" to emptyList<Any>()))))
        assertNull(StepUpParser.parse(mapOf("round" to 2.0, "challenge" to "not an object")))
    }

    @Test
    fun `coordinator pauses after upload when a step-up is pending`() = runTest {
        val instruction = StepUpParser.parse(mapOf("round" to 2.0, "challenge" to headTurn))!!
        var completed = false
        val updates = mutableListOf<FinalizationUpdate>()
        FinalizationCoordinator(object : FinalizationOperations {
            override suspend fun prepare() = Result.success(Unit)
            override suspend fun upload(onProgress: (Long, Long) -> Unit) = Result.success(Unit)
            override suspend fun complete(): Result<UseSenseResult> { completed = true; return Result.success(result) }
            override fun takeStepUp() = instruction
        }).run(updates::add)

        assertEquals(instruction, updates.filterIsInstance<FinalizationUpdate.StepUpRequired>().single().instruction)
        assertTrue(updates.none { it is FinalizationUpdate.Result })
        assertEquals(false, completed)
    }

    @Test
    fun `resuming at COMPLETING finishes without re-uploading`() = runTest {
        var uploads = 0
        val updates = mutableListOf<FinalizationUpdate>()
        FinalizationCoordinator(object : FinalizationOperations {
            override suspend fun prepare() = Result.success(Unit)
            override suspend fun upload(onProgress: (Long, Long) -> Unit): Result<Unit> { uploads++; return Result.success(Unit) }
            override suspend fun complete() = Result.success(result)
        }).run(FinalizationPhase.COMPLETING, updates::add)

        assertEquals(0, uploads)
        assertEquals(result, updates.filterIsInstance<FinalizationUpdate.Result>().single().result)
    }

    @Test
    fun `capabilities declare step_up_v1`() {
        assertTrue(StepUpCapability.STEP_UP_V1 in StepUpCapability.ALL)
    }
}
