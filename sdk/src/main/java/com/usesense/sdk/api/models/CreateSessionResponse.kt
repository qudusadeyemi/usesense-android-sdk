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
    @Json(name = "geometric_coherence") val geometricCoherence: GeometricCoherenceConfig? = null,
)

@JsonClass(generateAdapter = true)
data class SessionPolicy(
    @Json(name = "requires_audio") val requiresAudio: Boolean = false,
    @Json(name = "requires_stepup") val requiresStepup: Boolean = false,
    @Json(name = "challenge_type") val challengeType: String? = null,
    @Json(name = "challenge") val challenge: ChallengeSpec? = null,
    @Json(name = "audio_challenge") val audioChallenge: ChallengeSpec? = null,
    @Json(name = "policy_source") val policySource: String? = null,
    @Json(name = "inline_step_up") val inlineStepUp: InlineStepUpConfig? = null,
    @Json(name = "geometricCoherence") val geometricCoherencePolicy: GeometricCoherencePolicyConfig? = null,
    @Json(name = "screenIllumination") val screenIllumination: ScreenIlluminationConfig? = null,
    @Json(name = "meshIntegrity") val meshIntegrity: MeshIntegrityConfig? = null,
)

@JsonClass(generateAdapter = true)
data class UploadConfig(
    @Json(name = "max_frames") val maxFrames: Int,
    @Json(name = "target_fps") val targetFps: Int,
    @Json(name = "capture_duration_ms") val captureDurationMs: Int,
)

@JsonClass(generateAdapter = true)
data class GeometricCoherenceConfig(
    @Json(name = "dual_path_enabled") val dualPathEnabled: Boolean = true,
    @Json(name = "screen_illumination_enabled") val screenIlluminationEnabled: Boolean = true,
    @Json(name = "on_device_3dmm_required") val onDevice3dmmRequired: Boolean = false,
    @Json(name = "mesh_binding_challenge") val meshBindingChallenge: String = "",
)

@JsonClass(generateAdapter = true)
data class InlineStepUpConfig(
    @Json(name = "enabled") val enabled: Boolean = true,
    @Json(name = "suspicion_threshold") val suspicionThreshold: Int = 55,
    @Json(name = "preferred_challenge") val preferredChallenge: String = "auto",
)

@JsonClass(generateAdapter = true)
data class GeometricCoherencePolicyConfig(
    @Json(name = "hardGateFloor") val hardGateFloor: Int = 20,
    @Json(name = "passiveConfidenceThreshold") val passiveConfidenceThreshold: Int = 85,
    @Json(name = "frameIntegrityAction") val frameIntegrityAction: String = "hard_reject",
    @Json(name = "attestationFailureAction") val attestationFailureAction: String = "score_signal",
)

@JsonClass(generateAdapter = true)
data class ScreenIlluminationConfig(
    @Json(name = "enabled") val enabled: Boolean = true,
    @Json(name = "gcScoreThreshold") val gcScoreThreshold: Int = 70,
    @Json(name = "livesenseThreshold") val livesenseThreshold: Int = 75,
    @Json(name = "triggerOnHighRisk") val triggerOnHighRisk: Boolean = true,
    @Json(name = "alwaysOnMobile") val alwaysOnMobile: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class MeshIntegrityConfig(
    @Json(name = "absenceAction") val absenceAction: String = "score_penalty",
    @Json(name = "failureAction") val failureAction: String = "hard_reject",
    @Json(name = "requiredTiers") val requiredTiers: List<String> = listOf("schema", "sanity", "variance", "binding"),
)
