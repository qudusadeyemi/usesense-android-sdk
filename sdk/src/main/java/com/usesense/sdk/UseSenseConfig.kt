package com.usesense.sdk

data class UseSenseConfig(
    val apiKey: String,
    val environment: UseSenseEnvironment = UseSenseEnvironment.AUTO,
    val baseUrl: String = DEFAULT_BASE_URL,
    val branding: BrandingConfig? = null,
    val googleCloudProjectNumber: Long = DEFAULT_GOOGLE_CLOUD_PROJECT_NUMBER,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.usesense.ai/v1"
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
