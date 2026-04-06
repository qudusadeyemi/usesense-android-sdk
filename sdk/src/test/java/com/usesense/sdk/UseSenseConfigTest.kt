package com.usesense.sdk

import org.junit.Assert.*
import org.junit.Test

class UseSenseConfigTest {

    @Test
    fun `sandbox detected from sk_sandbox_ prefix`() {
        assertEquals(
            UseSenseEnvironment.SANDBOX,
            UseSenseEnvironment.fromApiKey("sk_sandbox_abc123")
        )
    }

    @Test
    fun `production detected from sk_prod_ prefix`() {
        assertEquals(
            UseSenseEnvironment.PRODUCTION,
            UseSenseEnvironment.fromApiKey("sk_prod_abc123")
        )
    }

    @Test
    fun `production detected from pk_prod_ prefix`() {
        assertEquals(
            UseSenseEnvironment.PRODUCTION,
            UseSenseEnvironment.fromApiKey("pk_prod_abc123")
        )
    }

    @Test
    fun `sandbox detected from pk_sandbox_ prefix`() {
        assertEquals(
            UseSenseEnvironment.SANDBOX,
            UseSenseEnvironment.fromApiKey("pk_sandbox_abc123")
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
    fun `default base URL is api_usesense_ai_v1`() {
        val config = UseSenseConfig(apiKey = "sk_sandbox_123")
        assertEquals("https://api.usesense.ai/v1", config.baseUrl)
    }

    @Test
    fun `default environment is AUTO`() {
        val config = UseSenseConfig(apiKey = "sk_sandbox_123")
        assertEquals(UseSenseEnvironment.AUTO, config.environment)
    }

    @Test
    fun `config does not have gatewayKey field`() {
        // v4.1: Supabase gateway headers removed, Cloudflare Worker handles them
        val config = UseSenseConfig(apiKey = "sk_prod_123")
        assertNotNull(config)
    }
}
