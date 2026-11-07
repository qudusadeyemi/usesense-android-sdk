package com.usesense.sdk

data class UseSenseConfig(
    val apiKey: String,
    val environment: UseSenseEnvironment = UseSenseEnvironment.AUTO,
    val baseUrl: String = DEFAULT_BASE_URL,
    val branding: BrandingConfig? = null,
    val googleCloudProjectNumber: Long = DEFAULT_GOOGLE_CLOUD_PROJECT_NUMBER,
    /**
     * Opt in to the on-device antispoof classifier. When enabled the SDK loads
     * the bundled TFLite model, runs inference against each captured face frame,
     * and attaches per-frame spoof probabilities to the metadata upload under
     * signals.deep_classifier_on_device. When disabled (default in v4.2), the
     * watchtower backend runs the classifier server-side. See
     * docs/sdk-specs/antispoof-classifier-sdk-spec.md for the rollout plan.
     */
    val antispoofOnDeviceEnabled: Boolean = false,

    /**
     * Opt the session into the LiveSense v4 capture flow. When true the SDK:
     *   - sends `x-usesense-sdk-version: v4` on session creation
     *   - inserts a constitutive zoom-motion phase between baseline and the
     *     active challenge (additive, not a replacement)
     *   - tags every captured frame with its capture phase so the server's
     *     SfM perspective validator can filter to the zoom subset
     * Defaults to false. The org must also have `livesense_v4_enabled` in its
     * features map; the server returns 400 v4_not_enabled otherwise.
     */
    val liveSenseV4Enabled: Boolean = false,
) {
    companion object {
        /**
         * API root, WITHOUT a version segment. Each path in UseSenseApiService
         * carries its own prefix (`v1/sessions/...`, and un-versioned
         * `remote-session/...`), so a version here produced `/v1/v1/...`.
         * A base that still ends in `/v1` is normalised by
         * `UseSenseApiClient.normalizeBaseUrl`, so existing configuration that
         * followed the old documentation keeps working.
         */
        const val DEFAULT_BASE_URL = "https://api.usesense.ai"
        const val DEFAULT_GOOGLE_CLOUD_PROJECT_NUMBER = 338813814736L
    }
}

/**
 * SDK-level branding. Precedence (docs/branding-precedence.md, agreed
 * 2026-11-07), resolved field by field:
 *   code (this config / per-launch options) > dashboard (Flow, then brand/org)
 *   > built-in defaults.
 *
 * Every field defaults to null = "not set": it never hides a dashboard value.
 */
data class BrandingConfig(
    val displayName: String? = null,   // null = inherit from org
    val logoUrl: String? = null,       // null = inherit from org
    val primaryColor: String? = null,  // null = inherit from the dashboard, then the built-in default
    val redirectUrl: String? = null,   // null = inherit from org
    /** Button corner radius in dp. Null (default) = inherit; was a fixed 12 before 4.10.1. */
    val buttonRadius: Int? = null,
    val fontFamily: String? = null,
    /**
     * Full white-label appearance for the Flows runner (Phase 1c). When set this
     * takes precedence over server branding and the legacy primaryColor/buttonRadius
     * fields above. Mirrors the web SDK's `appearance` run option. Null = inherit
     * (server appearance, then legacy fields, then built-in hosted-page tokens).
     */
    val appearance: com.usesense.sdk.flows.FlowAppearance? = null,
    /**
     * Full white-label copy/messaging overrides for the Flows runner (Phase 2).
     * When set this takes precedence over server-delivered copy. Mirrors the web
     * SDK's `copy` run option. Null = inherit (server copy, then built-in
     * hosted-page strings). Presentation only; never affects capture/scoring.
     */
    val copy: com.usesense.sdk.flows.FlowCopy? = null,
) {
    companion object {
        const val DEFAULT_PRIMARY_COLOR = "#4F7CFF"
    }
}

/**
 * Effective branding after resolving SDK branding over the dashboard's. Kept
 * for compatibility; the SDK resolves through its internal BrandingResolver.
 * [displayName] is null when neither code nor the dashboard set one (there is
 * no invented default name).
 */
data class EffectiveBranding(
    val displayName: String? = null,
    val logoUrl: String? = null,
    val primaryColor: String = BrandingConfig.DEFAULT_PRIMARY_COLOR,
    val redirectUrl: String? = null,
) {
    companion object {
        fun merge(sdk: BrandingConfig?, server: ServerBranding?): EffectiveBranding {
            val r = com.usesense.sdk.internal.BrandingResolver.resolve(sdk, server)
            return EffectiveBranding(
                displayName = r.displayName.value,
                logoUrl = r.logoUrl.value,
                primaryColor = r.primaryColor.value,
                redirectUrl = r.redirectUrl.value,
            )
        }
    }
}

/**
 * Branding delivered by the server (the dashboard's Flow / brand / org
 * settings). Every field is null when the dashboard never configured it.
 */
data class ServerBranding(
    val displayName: String? = null,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
    val redirectUrl: String? = null,
    val appearance: com.usesense.sdk.flows.FlowAppearance? = null,
    val copy: com.usesense.sdk.flows.FlowCopy? = null,
)

enum class UseSenseEnvironment {
    SANDBOX,
    PRODUCTION,
    AUTO;

    companion object {
        fun fromApiKey(apiKey: String): UseSenseEnvironment {
            return when {
                apiKey.startsWith("sk_prod_") -> PRODUCTION
                apiKey.startsWith("pk_prod_") -> PRODUCTION
                apiKey.startsWith("sk_sandbox_") -> SANDBOX
                apiKey.startsWith("pk_sandbox_") -> SANDBOX
                apiKey.startsWith("dk_") -> SANDBOX
                else -> PRODUCTION
            }
        }
    }
}

enum class SessionType(val value: String) {
    ENROLLMENT("enrollment"),
    AUTHENTICATION("authentication");
}

data class VerificationRequest(
    val sessionType: SessionType,
    val externalUserId: String? = null,
    val identityId: String? = null,
    val metadata: Map<String, Any>? = null,
)
