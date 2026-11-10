package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.api.ApiException
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.capture.AudioLevel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** POST /v1/sessions/:id/challenge-fallback (Watchtower #1056). */
class ChallengeFallbackApiTest {
    private val server = MockWebServer().apply { start() }
    private val client = UseSenseApiClient(UseSenseConfig(apiKey = "sk_test_x", baseUrl = server.url("/").toString()))
        .apply { sessionToken = "tok_123" }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `sends the reason with the session token and reads the new policy`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"session_id":"s1","policy":{"requires_audio":false,"requires_stepup":true,"challenge_type":"follow_dot",
               "challenge":{"type":"follow_dot","seed":"x","total_duration_ms":4000,
               "waypoints":[{"x":0.2,"y":0.3,"duration_ms":1000,"index":0}]},"policy_source":"custom+fallback"}}""",
        ))
        val policy = client.challengeFallback("s1").getOrThrow().policy
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/v1/sessions/s1/challenge-fallback"))
        assertEquals("tok_123", req.getHeader("X-Session-Token"))
        assertEquals("""{"reason":"microphone_unavailable"}""", req.body.readUtf8())
        assertEquals("follow_dot", policy.challenge!!.type)
        assertEquals(false, policy.requiresAudio)
    }

    @Test
    fun `a refused fallback keeps its server code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409)
            .setBody("""{"error":{"code":"capture_already_uploaded","message":"too late"}}"""))
        val e = client.challengeFallback("s1").exceptionOrNull() as ApiException
        assertEquals("capture_already_uploaded", e.useSenseError.serverCode)
    }

    @Test
    fun `the meter is flat for room noise and full for a shout`() {
        assertEquals(0f, AudioLevel.normalise(0), 0f)
        assertEquals(0f, AudioLevel.normalise(AudioLevel.FLOOR), 0f)
        assertTrue(AudioLevel.normalise(3000) in 0.4f..0.7f)
        assertEquals(1f, AudioLevel.normalise(32767), 0.02f)
    }
}
