package com.usesense.sdk.signals

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MetadataBuilder {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(
        challengeResponse: JSONObject?,
        channelIntegrity: JSONObject,
        deviceTelemetry: JSONObject,
        captureStartTime: Date,
        captureEndTime: Date,
        framesCaptured: Int,
        framesDropped: Int,
        avgFrameIntervalMs: Long,
    ): ByteArray {
        val metadata = JSONObject()

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

        // channel_integrity: the primary signal object the server reads
        metadata.put("channel_integrity", channelIntegrity)

        metadata.put("device_telemetry", deviceTelemetry)

        return metadata.toString(2).toByteArray(Charsets.UTF_8)
    }
}
