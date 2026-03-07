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
        webIntegrity: JSONObject?,
        androidIntegrity: JSONObject,
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

        // web_integrity: null on Android (used by web SDK only)
        metadata.put("web_integrity", JSONObject.NULL)

        // android_integrity: Android-specific device attestation with Play Integrity token
        metadata.put("android_integrity", androidIntegrity)

        // Device telemetry with capture timing
        deviceTelemetry.put("capture_start_time", isoFormat.format(captureStartTime))
        deviceTelemetry.put("capture_end_time", isoFormat.format(captureEndTime))
        deviceTelemetry.put("capture_duration_ms", captureEndTime.time - captureStartTime.time)
        deviceTelemetry.put("frames_captured", framesCaptured)
        deviceTelemetry.put("frames_dropped", framesDropped)
        deviceTelemetry.put("avg_frame_interval_ms", avgFrameIntervalMs)
        metadata.put("device_telemetry", deviceTelemetry)

        // webauthn_data: not applicable on Android
        metadata.put("webauthn_data", JSONObject.NULL)

        return metadata.toString(2).toByteArray(Charsets.UTF_8)
    }
}
