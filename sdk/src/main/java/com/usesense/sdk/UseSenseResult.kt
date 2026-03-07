package com.usesense.sdk

/**
 * Redacted decision object returned to the host application.
 *
 * Security: Internal scoring details (channel_trust_score, liveness_score,
 * dedupe_risk_score, pillar_verdicts, reasons, signature, etc.) are
 * stripped before exposing to the host app. This prevents reverse-engineering
 * of backend analysis logic.
 */
data class UseSenseResult(
    val sessionId: String,
    val sessionType: String?,
    val identityId: String?,
    val decision: String,
    val timestamp: String,
) {
    val isApproved: Boolean get() = decision == DECISION_APPROVE
    val isRejected: Boolean get() = decision == DECISION_REJECT
    val isPendingReview: Boolean get() = decision == DECISION_MANUAL_REVIEW

    companion object {
        const val DECISION_APPROVE = "APPROVE"
        const val DECISION_REJECT = "REJECT"
        const val DECISION_MANUAL_REVIEW = "MANUAL_REVIEW"
    }
}
