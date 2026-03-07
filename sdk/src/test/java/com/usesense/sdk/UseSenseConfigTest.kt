package com.usesense.sdk

import org.junit.Assert.*
import org.junit.Test

class UseSenseConfigTest {

    @Test
    fun `sandbox detected from sk_ prefix`() {
        assertEquals(
            UseSenseEnvironment.SANDBOX,
            UseSenseEnvironment.fromApiKey("sk_test_abc123")
        )
    }

    @Test
    fun `production detected from pk_ prefix`() {
        assertEquals(
            UseSenseEnvironment.PRODUCTION,
            UseSenseEnvironment.fromApiKey("pk_live_abc123")
        )
    }

    @Test
    fun `sandbox detected from dk_ prefix`() {
        assertEquals(
            UseSenseEnvironment.SANDBOX,
            UseSenseEnvironment.fromApiKey("dk_dev_abc123")
        )
    }

    @Test
    fun `unknown prefix defaults to production`() {
        assertEquals(
            UseSenseEnvironment.PRODUCTION,
            UseSenseEnvironment.fromApiKey("unknown_key")
        )
    }

    @Test
    fun `default base URL is set`() {
        val config = UseSenseConfig(apiKey = "sk_test_123")
        assertEquals(UseSenseConfig.DEFAULT_BASE_URL, config.baseUrl)
    }

    @Test
    fun `default environment is AUTO`() {
        val config = UseSenseConfig(apiKey = "sk_test_123")
        assertEquals(UseSenseEnvironment.AUTO, config.environment)
    }
}
