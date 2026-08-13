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
 * SDK-level branding overrides. Inheritance rule (Section 2):
 *   SDK-level branding > Organization settings > UseSense defaults
 *
 * Null values inherit from server-side org settings.
 */
data class BrandingConfig(
    val displayName: String? = null,   // null = inherit from org
    val logoUrl: String? = null,       // null = inherit from org
    val primaryColor: String? = null,  // null = inherit from org (#4f46e5 default)
    val redirectUrl: String? = null,   // null = inherit from org
    val buttonRadius: Int = 12,
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
 * Effective branding after merging SDK overrides with server org settings.
 */
data class EffectiveBranding(
    val displayName: String = "UseSense",
    val logoUrl: String? = null,
    val primaryColor: String = BrandingConfig.DEFAULT_PRIMARY_COLOR,
    val redirectUrl: String? = null,
) {
    companion object {
        fun merge(sdk: BrandingConfig?, server: ServerBranding?): EffectiveBranding {
            return EffectiveBranding(
                displayName = sdk?.displayName ?: server?.displayName ?: "UseSense",
                logoUrl = sdk?.logoUrl ?: server?.logoUrl,
                primaryColor = sdk?.primaryColor ?: server?.primaryColor ?: BrandingConfig.DEFAULT_PRIMARY_COLOR,
                redirectUrl = sdk?.redirectUrl ?: server?.redirectUrl,
            )
        }
    }
}

/**
 * Branding fields received from the server's org settings.
 */
data class ServerBranding(
    val displayName: String? = null,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
    val redirectUrl: String? = null,
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
