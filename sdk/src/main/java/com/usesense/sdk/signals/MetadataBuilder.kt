package com.usesense.sdk.signals

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Builds the metadata JSON payload per Section 7.1 of the spec.
 * All fields match the canonical TypeScript type definitions (Section 14).
 */
class MetadataBuilder {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(
        sessionId: String,
        source: String = "direct",
        challengeResponse: JSONObject?,
        channelIntegrity: JSONObject,
        deviceTelemetry: JSONObject,
        captureStartTime: Date,
        captureEndTime: Date,
        captureConfig: CaptureConfigInfo,
        framesManifest: List<FrameManifestEntry>,
        framesCaptured: Int,
        framesDropped: Int,
        avgFrameIntervalMs: Long,
        frameTimestamps: List<Long>? = null,
    ): ByteArray {
        val metadata = JSONObject()

        // Top-level fields (Section 7.1)
        metadata.put("session_id", sessionId)
        metadata.put("sdk_version", DeviceSignalCollector.SDK_VERSION)
        metadata.put("platform", "android")
        metadata.put("source", source)

        // Capture config
        val captureConfigJson = JSONObject().apply {
            put("captureDurationMs", captureConfig.captureDurationMs)
            put("targetFps", captureConfig.targetFps)
            put("maxFrames", captureConfig.maxFrames)
        }
        metadata.put("capture_config", captureConfigJson)

        // Timestamps
        val timestamps = JSONObject().apply {
            put("session_started_at_ms", captureStartTime.time)
            put("capture_started_at_ms", captureStartTime.time)
            put("capture_ended_at_ms", captureEndTime.time)
        }
        metadata.put("timestamps", timestamps)

        // Frames manifest
        val manifestArray = JSONArray()
        for (entry in framesManifest) {
            manifestArray.put(JSONObject().apply {
                put("frame_index", entry.frameIndex)
                put("capture_timestamp_ms", entry.captureTimestampMs)
                put("resolution_w", entry.resolutionW)
                put("resolution_h", entry.resolutionH)
            })
        }
        metadata.put("frames_manifest", manifestArray)

        // Challenge response (only present if challenge was issued)
        challengeResponse?.let {
            metadata.put("challenge_response", it)
        }

        // Add capture timing and frame stats to channel_integrity
        val captureDurationMs = captureEndTime.time - captureStartTime.time
        channelIntegrity.put("capture_start_time", isoFormat.format(captureStartTime))
        channelIntegrity.put("capture_end_time", isoFormat.format(captureEndTime))
        channelIntegrity.put("capture_duration_ms", captureDurationMs)
        channelIntegrity.put("frames_captured", framesCaptured)
        channelIntegrity.put("frames_dropped", framesDropped)
        channelIntegrity.put("avg_frame_interval_ms", avgFrameIntervalMs)

        // Frame timestamps for timing regularity analysis
        if (frameTimestamps != null && frameTimestamps.isNotEmpty()) {
            channelIntegrity.put("frame_timestamps", JSONArray(frameTimestamps))
        }

        // channel_integrity: the primary signal object the server reads
        metadata.put("channel_integrity", channelIntegrity)

        metadata.put("device_telemetry", deviceTelemetry)

        return metadata.toString(2).toByteArray(Charsets.UTF_8)
    }
}

data class CaptureConfigInfo(
    val captureDurationMs: Int,
    val targetFps: Int,
    val maxFrames: Int,
)

data class FrameManifestEntry(
    val frameIndex: Int,
    val captureTimestampMs: Long,
    val resolutionW: Int,
    val resolutionH: Int,
)
