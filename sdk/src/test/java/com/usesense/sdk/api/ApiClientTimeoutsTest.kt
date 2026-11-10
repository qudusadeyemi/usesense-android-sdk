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

    @Test
    fun `the signals upload has a 20 s idle timeout per attempt`() {
        assertEquals(UseSenseApiClient.UPLOAD_IDLE_TIMEOUT_SECONDS * 1_000, client.uploadWriteTimeoutMillis.toLong())
        assertEquals(UseSenseApiClient.UPLOAD_IDLE_TIMEOUT_SECONDS * 1_000, client.uploadReadTimeoutMillis.toLong())
    }

    /**
     * The invariant behind the iOS failure (a stalled upload whose retries were
     * all cancelled): an attempt must time out, back off and start its retry
     * (which counts as progress) before either stall window gives up.
     */
    @Test
    fun `idle timeout plus the longest backoff fits inside the stall windows`() {
        val idleMs = UseSenseApiClient.UPLOAD_IDLE_TIMEOUT_SECONDS * 1_000
        val longestBackoff = UseSenseApiClient.RETRY_BACKOFF_MS.max()
        assertTrue(idleMs + longestBackoff < com.usesense.sdk.internal.CaptureWatchdog.PROGRESS_GRACE_MS)
        assertTrue(idleMs + longestBackoff < FinalizationPolicy().noProgressTimeoutMs)
    }

    @Test
    fun `every attempt stalling still fits the upload budget`() {
        val idleMs = UseSenseApiClient.UPLOAD_IDLE_TIMEOUT_SECONDS * 1_000
        val worst = (UseSenseApiClient.MAX_RETRIES + 1) * idleMs + UseSenseApiClient.RETRY_BACKOFF_MS.sum()
        assertEquals(87_000L, worst)
        assertTrue(worst < FinalizationPolicy().uploadAllowanceMs)
        assertTrue(worst < client.uploadCallTimeoutMillis)
        assertEquals(UseSenseApiClient.MAX_RETRIES, UseSenseApiClient.RETRY_BACKOFF_MS.size)
    }
}
