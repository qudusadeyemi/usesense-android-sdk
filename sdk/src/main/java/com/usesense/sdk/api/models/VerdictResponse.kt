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
    @Json(name = "matrix_decision") val matrixDecision: String? = null,
    @Json(name = "channel_trust_score") val channelTrustScore: Int = 0,
    @Json(name = "liveness_score") val livenessScore: Int = 0,
    @Json(name = "dedupe_risk_score") val dedupeRiskScore: Int = 0,
    @Json(name = "pillar_verdicts") val pillarVerdicts: PillarVerdicts? = null,
    @Json(name = "verdict_metadata") val verdictMetadata: VerdictMetadata? = null,
    @Json(name = "reasons") val reasons: List<String> = emptyList(),
    @Json(name = "timestamp") val timestamp: String = "",
    @Json(name = "geometric_coherence") val geometricCoherence: GCScoreResult? = null,
    @Json(name = "sensei_step_up") val senseiStepUp: SenseiStepUpResult? = null,
    @Json(name = "inline_step_up") val inlineStepUp: InlineStepUpResult? = null,
    @Json(name = "challenge_validation") val challengeValidation: ChallengeValidationResult? = null,
    @Json(name = "dedupe_analysis") val dedupeAnalysis: DedupeAnalysisResult? = null,
    @Json(name = "blocklist_hit") val blocklistHit: Any? = null,
    @Json(name = "attestation_hard_gate") val attestationHardGate: Any? = null,
    @Json(name = "debug") val debug: DebugInfo? = null,
)

@JsonClass(generateAdapter = true)
data class PillarVerdicts(
    @Json(name = "channel_trust") val channelTrust: String? = null,
    @Json(name = "liveness") val liveness: String? = null,
    @Json(name = "dedupe") val dedupe: String? = null,
)

@JsonClass(generateAdapter = true)
data class VerdictMetadata(
    @Json(name = "source") val source: String = "",
    @Json(name = "logic") val logic: String = "",
    @Json(name = "hard_gate_tripped") val hardGateTripped: Boolean = false,
    @Json(name = "risk_band") val riskBand: String? = null,
    @Json(name = "ruleOverride") val ruleOverride: String? = null,
)

@JsonClass(generateAdapter = true)
data class GCScoreResult(
    @Json(name = "score") val score: Int = 0,
    @Json(name = "sub_signals") val subSignals: GCSubSignals? = null,
    @Json(name = "flags") val flags: GCFlags? = null,
)

@JsonClass(generateAdapter = true)
data class GCSubSignals(
    @Json(name = "depth_plausibility") val depthPlausibility: Int? = null,
    @Json(name = "cross_frame_consistency") val crossFrameConsistency: Int? = null,
    @Json(name = "dual_path_comparison") val dualPathComparison: Int? = null,
    @Json(name = "material_classification") val materialClassification: String? = null,
)

@JsonClass(generateAdapter = true)
data class GCFlags(
    @Json(name = "hard_rejection") val hardRejection: Boolean = false,
    @Json(name = "hard_rejection_reason") val hardRejectionReason: String? = null,
    @Json(name = "frame_integrity_failure") val frameIntegrityFailure: Boolean = false,
    @Json(name = "attestation_failure") val attestationFailure: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class SenseiStepUpResult(
    @Json(name = "step_up_triggered") val stepUpTriggered: Boolean = false,
    @Json(name = "passive_confidence") val passiveConfidence: String? = null,
    @Json(name = "illumination") val illumination: Any? = null,
    @Json(name = "challenge_escalation") val challengeEscalation: Any? = null,
    @Json(name = "hard_reject") val hardReject: Boolean = false,
    @Json(name = "hard_reject_reason") val hardRejectReason: String? = null,
    @Json(name = "reasoning") val reasoning: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class InlineStepUpResult(
    @Json(name = "triggered") val triggered: Boolean = false,
    @Json(name = "suspicion_score") val suspicionScore: Int? = null,
    @Json(name = "passed") val passed: Boolean = false,
    @Json(name = "hard_reject") val hardReject: Boolean = false,
    @Json(name = "flash_reflection") val flashReflection: Any? = null,
    @Json(name = "rmas") val rmas: Any? = null,
)

@JsonClass(generateAdapter = true)
data class ChallengeValidationResult(
    @Json(name = "challengeType") val challengeType: String? = null,
    @Json(name = "verdict") val verdict: String? = null,
    @Json(name = "overallScore") val overallScore: Int = 0,
    @Json(name = "stepsCompliant") val stepsCompliant: Int = 0,
    @Json(name = "stepsTotal") val stepsTotal: Int = 0,
)

@JsonClass(generateAdapter = true)
data class DedupeAnalysisResult(
    @Json(name = "duplicateSearchPerformed") val duplicateSearchPerformed: Boolean = false,
    @Json(name = "highestDuplicateSimilarity") val highestDuplicateSimilarity: Int = 0,
    @Json(name = "crossIdentityRisk") val crossIdentityRisk: Int = 0,
    @Json(name = "duplicateMatches") val duplicateMatches: List<Any> = emptyList(),
    @Json(name = "reference_match") val referenceMatch: ReferenceMatchResult? = null,
)

@JsonClass(generateAdapter = true)
data class ReferenceMatchResult(
    @Json(name = "similarity") val similarity: Double = 0.0,
    @Json(name = "threshold") val threshold: Int = 80,
    @Json(name = "matched") val matched: Boolean = false,
    @Json(name = "reference_data") val referenceData: String? = null,
)

@JsonClass(generateAdapter = true)
data class DebugInfo(
    @Json(name = "frames_analyzed") val framesAnalyzed: Int = 0,
    @Json(name = "environment") val environment: String? = null,
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
