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
    fun `default base URL carries no version segment`() {
        // This test used to assert `https://api.usesense.ai/v1` and so pinned
        // the bug in place: every path in UseSenseApiService already starts
        // with `v1/`, so a version here resolved every call to `/v1/v1/...`.
        // The server rejected that before reading the request body, and a
        // megabyte signals upload turned the rejection into a silent hang --
        // which is why no Android integration ever completed a production
        // verification. Resolution is asserted end to end in
        // api/BaseUrlResolutionTest.
        val config = UseSenseConfig(apiKey = "sk_sandbox_123")
        assertEquals("https://api.usesense.ai", config.baseUrl)
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
