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
        webIntegrity: JSONObject,
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

        // Augment web_integrity with capture timing
        webIntegrity.put("capture_start_time", isoFormat.format(captureStartTime))
        webIntegrity.put("capture_end_time", isoFormat.format(captureEndTime))
        webIntegrity.put("capture_duration_ms", captureEndTime.time - captureStartTime.time)
        webIntegrity.put("frames_captured", framesCaptured)
        webIntegrity.put("frames_dropped", framesDropped)
        webIntegrity.put("avg_frame_interval_ms", avgFrameIntervalMs)

        metadata.put("web_integrity", webIntegrity)
        metadata.put("device_telemetry", deviceTelemetry)

        return metadata.toString(2).toByteArray(Charsets.UTF_8)
    }
}
