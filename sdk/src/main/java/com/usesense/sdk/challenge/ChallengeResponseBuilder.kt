package com.usesense.sdk.challenge

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.capture.extracheck.ExtraCheckRunner
import com.usesense.sdk.capture.extracheck.ExtraCheckStepLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ChallengeResponseBuilder(private val spec: ChallengeSpec) {

    private val waypointFrames = mutableMapOf<Int, MutableList<Int>>()
    private val frameTimestamps = mutableListOf<Long>()
    private var startedAt: String? = null
    private var completedAt: String? = null
    private var completed = false
    private var currentStepIndex = 0

    /** Guided Head Turn / Follow Dot: per-step timing and first move (`step_events`). */
    private var stepEvents: List<Map<String, Any>> = emptyList()

    /**
     * Frames taken now are evidence for the current step (the guided runner's
     * `banksNow`). False: the frame keeps its timestamp but isn't any step's
     * evidence, as iOS. Set from the main thread, read on the capture thread.
     */
    @Volatile
    var bankFrames = true

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun markStarted() {
        startedAt = isoFormat.format(Date())
    }

    fun markCompleted() {
        completedAt = isoFormat.format(Date())
        completed = true
    }

    fun setCurrentStep(stepIndex: Int) {
        currentStepIndex = stepIndex
    }

    fun recordFrame(frameIndex: Int, timestampMs: Long) {
        frameTimestamps.add(timestampMs)
        if (!bankFrames) return
        waypointFrames.getOrPut(currentStepIndex) { mutableListOf() }.add(frameIndex)
    }

    /**
     * Per-step timing from the guided challenge (same shape and meaning as
     * iOS and Web). [epochOffsetMs] turns the runner's clock into epoch ms
     * (wall clock minus the runner's clock, read once).
     */
    internal fun setStepEvents(log: List<ExtraCheckStepLog>, epochOffsetMs: Long) {
        stepEvents = log.map { stepEvent(it, epochOffsetMs) }
    }

    internal companion object {
        fun stepEvent(e: ExtraCheckStepLog, epochOffsetMs: Long): Map<String, Any> {
            val d = linkedMapOf<String, Any>(
                "index" to e.index,
                "prompted_at_ms" to e.promptedAt + epochOffsetMs,
                "ended_at_ms" to e.endedAt + epochOffsetMs,
                "hinted" to e.hinted,
            )
            e.reachedAt?.let {
                d["reached_at_ms"] = it + epochOffsetMs
                d["reaction_ms"] = it - e.promptedAt
            }
            d["outcome"] = when (e.reached) {
                true -> "reached"
                false -> "timed_out"
                null -> "timed"
            }
            // step_events v2: the first move, toward the target (+) or away (-).
            if (e.firstMoveDeg != null && e.firstMoveMs != null) {
                d["first_move_deg"] = ExtraCheckRunner.roundDeg(e.firstMoveDeg)
                d["first_move_ms"] = e.firstMoveMs
            }
            e.maxAwayDeg?.let { d["max_away_deg"] = ExtraCheckRunner.roundDeg(it) }
            return d
        }
    }

    fun buildAsMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["type"] = spec.type
        map["seed"] = spec.seed
        map["completed"] = completed

        val framesKey = when (spec.type) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> "waypoint_frames"
            ChallengeSpec.TYPE_HEAD_TURN -> "step_frames"
            else -> null
        }

        framesKey?.let { key ->
            val framesMap = mutableMapOf<String, List<Int>>()
            waypointFrames.forEach { (stepIdx, frameList) ->
                framesMap[stepIdx.toString()] = frameList.toList()
            }
            map[key] = framesMap
        }

        startedAt?.let { map["started_at"] = it }
        completedAt?.let { map["completed_at"] = it }
        map["frame_timestamps"] = frameTimestamps.toList()
        if (stepEvents.isNotEmpty()) map["step_events"] = stepEvents

        return map
    }

    fun build(): JSONObject {
        val data = buildAsMap()
        val json = JSONObject()
        for ((key, value) in data) {
            when (value) {
                is Map<*, *> -> {
                    val inner = JSONObject()
                    @Suppress("UNCHECKED_CAST")
                    for ((k, v) in value as Map<String, Any>) {
                        inner.put(k, if (v is List<*>) JSONArray(v) else v)
                    }
                    json.put(key, inner)
                }
                is List<*> -> json.put(key, JSONArray(value))
                else -> json.put(key, value)
            }
        }
        return json
    }
}
