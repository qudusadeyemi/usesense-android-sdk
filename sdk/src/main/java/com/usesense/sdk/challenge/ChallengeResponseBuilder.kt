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

    fun build(): JSONObject {
        val json = JSONObject()
        json.put("type", spec.type)
        json.put("seed", spec.seed)
        json.put("completed", completed)

        val framesKey = when (spec.type) {
            ChallengeSpec.TYPE_FOLLOW_DOT -> "waypoint_frames"
            ChallengeSpec.TYPE_HEAD_TURN -> "step_frames"
            else -> null
        }

        framesKey?.let { key ->
            val framesObj = JSONObject()
            waypointFrames.forEach { (stepIdx, frameList) ->
                framesObj.put(stepIdx.toString(), JSONArray(frameList))
            }
            json.put(key, framesObj)
        }

        startedAt?.let { json.put("started_at", it) }
        completedAt?.let { json.put("completed_at", it) }
        json.put("frame_timestamps", JSONArray(frameTimestamps))

        return json
    }
}
