package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Remote Enrollment ──

@JsonClass(generateAdapter = true)
data class RemoteEnrollmentData(
    @Json(name = "id") val id: String,
    @Json(name = "status") val status: String,
    @Json(name = "organization_id") val organizationId: String? = null,
    @Json(name = "external_user_id") val externalUserId: String? = null,
    @Json(name = "branding") val branding: OrgBranding? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
)

// ── Remote Session (Verification) ──

@JsonClass(generateAdapter = true)
data class RemoteSessionData(
    @Json(name = "id") val id: String,
    @Json(name = "status") val status: String,
    @Json(name = "organization_id") val organizationId: String? = null,
    @Json(name = "identity_id") val identityId: String? = null,
    @Json(name = "action_context") val actionContext: ActionContext? = null,
    @Json(name = "branding") val branding: OrgBranding? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class ActionContext(
    @Json(name = "display_text") val displayText: String,
    @Json(name = "risk_tier") val riskTier: String? = null, // "critical" | "high" | "medium" | "low"
    @Json(name = "action_type") val actionType: String? = null,
    @Json(name = "metadata") val metadata: Map<String, Any>? = null,
)

@JsonClass(generateAdapter = true)
data class OrgBranding(
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "logo_url") val logoUrl: String? = null,
    @Json(name = "primary_color") val primaryColor: String? = null,
    @Json(name = "redirect_url") val redirectUrl: String? = null,
)

// ── Hosted Page Init Session (re-uses CreateSessionResponse but different endpoint) ──

@JsonClass(generateAdapter = true)
data class HostedInitSessionResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "session_token") val sessionToken: String,
    @Json(name = "nonce") val nonce: String,
    @Json(name = "policy") val policy: SessionPolicy,
    @Json(name = "upload") val upload: UploadConfig,
)

// ── Hosted Page Complete Response ──

@JsonClass(generateAdapter = true)
data class HostedCompleteResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "status") val status: String? = null,
    @Json(name = "decision") val decision: String? = null,
)

// ── Dispute Response ──

@JsonClass(generateAdapter = true)
data class DisputeResponse(
    @Json(name = "success") val success: Boolean = true,
)

// ── Wrapper for /data endpoint ──

@JsonClass(generateAdapter = true)
data class RemoteEnrollmentDataWrapper(
    @Json(name = "enrollment") val enrollment: RemoteEnrollmentData? = null,
    @Json(name = "branding") val branding: OrgBranding? = null,
)

@JsonClass(generateAdapter = true)
data class RemoteSessionDataWrapper(
    @Json(name = "session") val session: RemoteSessionData? = null,
    @Json(name = "branding") val branding: OrgBranding? = null,
    @Json(name = "action_context") val actionContext: ActionContext? = null,
)
