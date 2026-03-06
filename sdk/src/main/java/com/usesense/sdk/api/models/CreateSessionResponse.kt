package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateSessionResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "session_token") val sessionToken: String,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "nonce") val nonce: String,
    @Json(name = "policy") val policy: SessionPolicy,
    @Json(name = "upload") val upload: UploadConfig,
)

@JsonClass(generateAdapter = true)
data class SessionPolicy(
    @Json(name = "requires_audio") val requiresAudio: Boolean = false,
    @Json(name = "requires_stepup") val requiresStepup: Boolean = false,
    @Json(name = "challenge_type") val challengeType: String? = null,
    @Json(name = "challenge") val challenge: ChallengeSpec? = null,
    @Json(name = "audio_challenge") val audioChallenge: ChallengeSpec? = null,
    @Json(name = "policy_source") val policySource: String? = null,
)

@JsonClass(generateAdapter = true)
data class UploadConfig(
    @Json(name = "max_frames") val maxFrames: Int,
    @Json(name = "target_fps") val targetFps: Int,
    @Json(name = "capture_duration_ms") val captureDurationMs: Int,
)
