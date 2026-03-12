package com.usesense.sdk.challenge

import com.usesense.sdk.api.models.ChallengeSpec
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
        waypointFrames.getOrPut(currentStepIndex) { mutableListOf() }.add(frameIndex)
        frameTimestamps.add(timestampMs)
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
