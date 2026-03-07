package com.usesense.sdk

data class UseSenseConfig(
    val apiKey: String,
    val environment: UseSenseEnvironment = UseSenseEnvironment.AUTO,
    val baseUrl: String = DEFAULT_BASE_URL,
    val gatewayKey: String? = null,
    val branding: BrandingConfig? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.usesense.ai/functions/v1/make-server-fc4cf30d"
    }
}

data class BrandingConfig(
    val logoUrl: String? = null,
    val primaryColor: String = "#4F63F5",
    val buttonRadius: Int = 12,
    val fontFamily: String? = null,
)

enum class UseSenseEnvironment {
    SANDBOX,
    PRODUCTION,
    AUTO;

    companion object {
        fun fromApiKey(apiKey: String): UseSenseEnvironment {
            return when {
                apiKey.startsWith("sk_") -> SANDBOX
                apiKey.startsWith("dk_") -> SANDBOX
                apiKey.startsWith("pk_") -> PRODUCTION
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
