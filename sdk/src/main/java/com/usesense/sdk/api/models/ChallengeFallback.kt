package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Body of POST /v1/sessions/:id/challenge-fallback. */
@JsonClass(generateAdapter = true)
data class ChallengeFallbackRequest(
    @Json(name = "reason") val reason: String = REASON_MICROPHONE_UNAVAILABLE,
) {
    companion object {
        const val REASON_MICROPHONE_UNAVAILABLE = "microphone_unavailable"
    }
}

/** The session's new policy (same shape as session create), now a Follow Dot. */
@JsonClass(generateAdapter = true)
data class ChallengeFallbackResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "policy") val policy: SessionPolicy,
)
