package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UploadSignalsResponse(
    @Json(name = "received") val received: Boolean,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "frames_count") val framesCount: Int,
    @Json(name = "audio_received") val audioReceived: Boolean = false,
    @Json(name = "metadata_received") val metadataReceived: Boolean = false,
    @Json(name = "total_size_bytes") val totalSizeBytes: Long = 0,
)
