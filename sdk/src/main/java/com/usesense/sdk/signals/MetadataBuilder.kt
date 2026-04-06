package com.usesense.sdk.signals

import com.usesense.sdk.liveness.SuspicionSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Builds the complete metadata JSON payload per spec Chapter 8.2.
 * All fields match the v4.1 unified spec.
 */
class MetadataBuilder {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(
        sessionId: String,
        source: String = "sdk",
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
        frameHashes: List<String>? = null,
        faceMeshSignals: JSONObject? = null,
        verificationPackage: JSONObject? = null,
        suspicion: SuspicionSnapshot? = null,
        inlineStepUp: JSONObject? = null,
        screenDetection: JSONObject? = null,
    ): ByteArray {
        val metadata = JSONObject()

        // Top-level fields
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

        // Frame hashes (SHA-256 per frame, required in v4.1)
        if (frameHashes != null && frameHashes.isNotEmpty()) {
            metadata.put("frame_hashes", JSONArray(frameHashes))
        }

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

        // Frame timestamps
        if (frameTimestamps != null && frameTimestamps.isNotEmpty()) {
            channelIntegrity.put("frame_timestamps", JSONArray(frameTimestamps))
        }

        // Screen detection signals
        if (screenDetection != null) {
            channelIntegrity.put("screen_detection", screenDetection)
        }

        metadata.put("channel_integrity", channelIntegrity)
        metadata.put("device_telemetry", deviceTelemetry)

        // Face mesh signals (when MediaPipe available)
        faceMeshSignals?.let {
            metadata.put("face_mesh_signals", it)
        }

        // Verification package (when geometric_coherence.dual_path_enabled)
        verificationPackage?.let {
            metadata.put("verification_package", it)
        }

        // Suspicion data (always included, even if not triggered)
        if (suspicion != null) {
            metadata.put("suspicion", JSONObject().apply {
                put("final_score", suspicion.score)
                put("triggered", suspicion.score >= 55) // default threshold
                put("snapshot", suspicion.toJson())
            })
        }

        // Inline step-up evidence (null if not triggered)
        if (inlineStepUp != null) {
            metadata.put("inline_step_up", inlineStepUp)
        }

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
