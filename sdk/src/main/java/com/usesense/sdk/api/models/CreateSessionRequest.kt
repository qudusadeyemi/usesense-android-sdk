package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateSessionRequest(
    @Json(name = "session_type") val sessionType: String,
    @Json(name = "platform") val platform: String = "android",
    @Json(name = "identity_id") val identityId: String? = null,
    @Json(name = "external_user_id") val externalUserId: String? = null,
    @Json(name = "metadata") val metadata: Map<String, Any>? = null,
    /** What this SDK can do, e.g. run a server step-up round. */
    @Json(name = "capabilities") val capabilities: List<String> = com.usesense.sdk.finalization.StepUpCapability.ALL,
)
