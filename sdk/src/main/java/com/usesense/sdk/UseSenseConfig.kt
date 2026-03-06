package com.usesense.sdk

data class UseSenseConfig(
    val apiKey: String,
    val environment: UseSenseEnvironment = UseSenseEnvironment.AUTO,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.usesense.ai"
    }
}

enum class UseSenseEnvironment {
    SANDBOX,
    PRODUCTION,
    AUTO;

    companion object {
        fun fromApiKey(apiKey: String): UseSenseEnvironment {
            return when {
                apiKey.startsWith("sk_") -> SANDBOX
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
