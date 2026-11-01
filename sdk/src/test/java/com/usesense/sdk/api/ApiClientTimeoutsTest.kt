package com.usesense.sdk.api

import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.finalization.FinalizationPolicy
import com.usesense.sdk.flows.FlowsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * connect/read/write timeouts bound one socket operation; only a call timeout
 * bounds a request end to end (retries, backoff and Retry-After included).
 */
class ApiClientTimeoutsTest {

    private val client = UseSenseApiClient(UseSenseConfig(apiKey = "sk_test_x", baseUrl = "https://api.example.test"))

    @Test
    fun `every API request has a whole-call ceiling`() {
        assertEquals(UseSenseApiClient.CALL_TIMEOUT_SECONDS * 1_000, client.callTimeoutMillis.toLong())
        assertTrue(client.connectTimeoutMillis > 0)
        assertTrue(client.readTimeoutMillis > 0)
    }

    @Test
    fun `the signals upload gets the upload allowance, no more`() {
        assertEquals(UseSenseApiClient.UPLOAD_CALL_TIMEOUT_SECONDS * 1_000, client.uploadCallTimeoutMillis.toLong())
        assertEquals(FinalizationPolicy().uploadAllowanceMs, client.uploadCallTimeoutMillis.toLong())
        assertTrue(client.uploadCallTimeoutMillis > client.callTimeoutMillis)
    }

    @Test
    fun `the flows runner client is bounded end to end`() {
        val http = FlowsClient.defaultClient()
        assertEquals(FlowsClient.CALL_TIMEOUT_SECONDS * 1_000, http.callTimeoutMillis.toLong())
        assertTrue(http.writeTimeoutMillis > 0)
        assertTrue(http.readTimeoutMillis > 0)
    }

    @Test
    fun `a server Retry-After cannot stall a request for long`() {
        assertTrue(UseSenseApiClient.MAX_RETRY_AFTER_SECONDS * 1_000 < UseSenseApiClient.CALL_TIMEOUT_SECONDS * 1_000)
    }
}
