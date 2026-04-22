package com.usesense.sdk.api

/**
 * LiveSense v4 public types. Phase 1 ticket A-2.
 */
data class V4VerificationRequest(
    val sessionId: String,
    val sessionToken: String,
    val nonce: String,
    val apiBaseUrl: String,
    val environment: String = "production",
    val brandPrimaryColor: Int? = null,
    val displayName: String? = null
)

enum class V4Phase { FRAMING, ZOOM, UPLOADING, COMPLETING, COMPLETED }

interface V4VerificationCallback {
    fun onComplete(verdict: V4Verdict)
    fun onFailure(error: Throwable)
    fun onPhaseChange(phase: V4Phase)
}

/**
 * Opaque verdict from POST /v1/sessions/:id/result. Must never contain
 * pillar sub-scores.
 */
data class V4Verdict(
    val sessionId: String,
    val verdict: Decision,
    val confidence: Confidence,
    val assuranceLevelAchieved: String,
    val captureChannel: String = "android",
    val matchSenseEmbeddingId: String?,
    val timestamp: String
) {
    enum class Decision { PASS, FAIL, REVIEW }
    enum class Confidence { HIGH, MEDIUM, LOW }

    companion object {
        fun fromWire(json: org.json.JSONObject): V4Verdict {
            val verdict = when (json.optString("verdict", "fail").lowercase()) {
                "pass" -> Decision.PASS
                "review" -> Decision.REVIEW
                else -> Decision.FAIL
            }
            val confidence = when (json.optString("confidence", "low").lowercase()) {
                "high" -> Confidence.HIGH
                "medium" -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            return V4Verdict(
                sessionId = json.optString("session_id"),
                verdict = verdict,
                confidence = confidence,
                assuranceLevelAchieved = json.optString("assurance_level_achieved", "mobile_hardware"),
                captureChannel = json.optString("capture_channel", "android"),
                matchSenseEmbeddingId = json.optString("match_sense_embedding_id", null),
                timestamp = json.optString("timestamp", "")
            )
        }
    }
}
