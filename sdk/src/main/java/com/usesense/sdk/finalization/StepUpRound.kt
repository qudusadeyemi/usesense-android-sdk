package com.usesense.sdk.finalization

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.api.models.HeadTurnStep
import com.usesense.sdk.api.models.Waypoint

/**
 * Server step-up, round 2.
 *
 * When a server Step-up rule matches the uploaded capture, the /signals
 * response asks for one more challenge in the same session. The SDK runs it
 * on the still-bound camera, uploads it with `?round=2`, then completes.
 * Server contract: docs/sdk/step-up-protocol.md in usesense-watchtower.
 */
object StepUpCapability {
    const val STEP_UP_V1 = "step_up_v1"

    /** Every capability this SDK version declares. */
    val ALL: List<String> = listOf(STEP_UP_V1)
}

/** A step-up the server asked for: the challenge to run and the round's frame budget. */
data class StepUpInstruction(
    val challenge: ChallengeSpec,
    val maxFrames: Int,
)

object StepUpParser {
    const val DEFAULT_MAX_FRAMES = 20

    /**
     * Reads the `step_up` object of a round-1 /signals response. Returns null
     * for anything this SDK can't render (another round, another challenge
     * type, a spec without steps or seed), so the session completes as before
     * and the server applies its own fallback. Never throws: the field is
     * decoded as a loose map so a shape change can't fail the upload itself.
     */
    fun parse(stepUp: Map<String, Any?>?): StepUpInstruction? {
        if (stepUp == null || num(stepUp["round"]) != 2) return null
        @Suppress("UNCHECKED_CAST")
        val c = stepUp["challenge"] as? Map<String, Any?> ?: return null
        val type = c["type"] as? String ?: return null
        val seed = (c["seed"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val total = num(c["total_duration_ms"]) ?: 0

        val spec = when (type) {
            ChallengeSpec.TYPE_HEAD_TURN -> {
                val steps = list(c["sequence"]).mapNotNull { s ->
                    val dir = s["direction"] as? String ?: return@mapNotNull null
                    val dur = num(s["duration_ms"]) ?: return@mapNotNull null
                    val idx = num(s["index"]) ?: return@mapNotNull null
                    HeadTurnStep(direction = dir, durationMs = dur, index = idx)
                }
                if (steps.isEmpty()) return null
                ChallengeSpec(
                    type = type, seed = seed,
                    totalDurationMs = if (total > 0) total else steps.sumOf { it.durationMs },
                    framesPerStep = num(c["frames_per_step"]),
                    captureFpsHint = num(c["capture_fps_hint"]),
                    sequence = steps,
                )
            }
            ChallengeSpec.TYPE_FOLLOW_DOT -> {
                val waypoints = list(c["waypoints"]).mapNotNull { w ->
                    val x = (w["x"] as? Number)?.toFloat() ?: return@mapNotNull null
                    val y = (w["y"] as? Number)?.toFloat() ?: return@mapNotNull null
                    val dur = num(w["duration_ms"]) ?: return@mapNotNull null
                    val idx = num(w["index"]) ?: return@mapNotNull null
                    Waypoint(x = x, y = y, durationMs = dur, index = idx)
                }
                if (waypoints.isEmpty()) return null
                ChallengeSpec(
                    type = type, seed = seed,
                    totalDurationMs = if (total > 0) total else waypoints.sumOf { it.durationMs },
                    framesPerStep = num(c["frames_per_step"]),
                    captureFpsHint = num(c["capture_fps_hint"]),
                    waypoints = waypoints,
                    dotSizePx = num(c["dot_size_px"]),
                )
            }
            else -> return null
        }

        @Suppress("UNCHECKED_CAST")
        val upload = stepUp["upload"] as? Map<String, Any?>
        val maxFrames = num(upload?.get("max_frames"))?.takeIf { it > 0 } ?: DEFAULT_MAX_FRAMES
        return StepUpInstruction(spec, maxFrames)
    }

    private fun num(v: Any?): Int? = (v as? Number)?.toInt()

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Map<String, Any?>> =
        (v as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
}
