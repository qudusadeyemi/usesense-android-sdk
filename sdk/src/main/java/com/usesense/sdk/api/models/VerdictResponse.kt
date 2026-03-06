package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VerdictResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "organization_id") val organizationId: String? = null,
    @Json(name = "session_type") val sessionType: String,
    @Json(name = "identity_id") val identityId: String? = null,
    @Json(name = "decision") val decision: String,
    @Json(name = "channel_trust_score") val channelTrustScore: Int = 0,
    @Json(name = "liveness_score") val livenessScore: Int = 0,
    @Json(name = "dedupe_risk_score") val dedupeRiskScore: Int = 0,
    @Json(name = "pillar_verdicts") val pillarVerdicts: PillarVerdicts? = null,
    @Json(name = "verdict_metadata") val verdictMetadata: VerdictMetadata? = null,
    @Json(name = "reasons") val reasons: List<String> = emptyList(),
    @Json(name = "timestamp") val timestamp: String = "",
    @Json(name = "signature") val signature: String = "",
)

@JsonClass(generateAdapter = true)
data class PillarVerdicts(
    @Json(name = "deepsense") val deepsense: PillarScore? = null,
    @Json(name = "livesense") val livesense: PillarScore? = null,
    @Json(name = "dedupe") val dedupe: PillarScore? = null,
)

@JsonClass(generateAdapter = true)
data class PillarScore(
    @Json(name = "score") val score: Int = 0,
    @Json(name = "verdict") val verdict: String = "",
)

@JsonClass(generateAdapter = true)
data class VerdictMetadata(
    @Json(name = "source") val source: String = "",
    @Json(name = "logic") val logic: String = "",
)

@JsonClass(generateAdapter = true)
data class SessionStatusResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "status") val status: String,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "decision") val decision: String? = null,
)

@JsonClass(generateAdapter = true)
data class ErrorResponse(
    @Json(name = "error") val error: ErrorDetail,
)

@JsonClass(generateAdapter = true)
data class ErrorDetail(
    @Json(name = "code") val code: String,
    @Json(name = "message") val message: String,
    @Json(name = "details") val details: Any? = null,
    @Json(name = "request_id") val requestId: String? = null,
)
