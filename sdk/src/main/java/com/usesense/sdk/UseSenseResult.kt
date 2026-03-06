package com.usesense.sdk

import org.json.JSONObject

data class UseSenseResult(
    val sessionId: String,
    val sessionType: String,
    val identityId: String?,
    val decision: String,
    val channelTrustScore: Int,
    val livenessScore: Int,
    val dedupeRiskScore: Int,
    val reasons: List<String>,
    val timestamp: String,
    val signature: String,
    val rawResponse: JSONObject? = null,
) {
    val isApproved: Boolean get() = decision == DECISION_APPROVE
    val isRejected: Boolean get() = decision == DECISION_REJECT
    val isPendingReview: Boolean get() = decision == DECISION_MANUAL_REVIEW

    companion object {
        const val DECISION_APPROVE = "APPROVE"
        const val DECISION_REJECT = "REJECT"
        const val DECISION_MANUAL_REVIEW = "MANUAL_REVIEW"
        const val DECISION_ERROR = "ERROR"
    }
}
