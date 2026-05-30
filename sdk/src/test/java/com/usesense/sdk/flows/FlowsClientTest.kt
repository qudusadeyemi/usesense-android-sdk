package com.usesense.sdk.flows

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests the SDK-Runner HTTP client. Load-bearing concerns:
 *   1. Auth: Bearer header on every request; sdkToken never lands in the URL.
 *   2. Error translation: server HTTP/JSON envelope maps to the FlowError
 *      taxonomy so host-app catch blocks are one path per code.
 */
class FlowsClientTest {

    private fun mockClient(status: Int, body: JSONObject, captured: ((Request) -> Unit)? = null): OkHttpClient {
        // Interceptor-based fake: short-circuits the chain with a synthesised
        // Response. Avoids mockk on OkHttpClient (a final class with many
        // interlocking methods) and exercises the real client wiring.
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            captured?.invoke(req)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "ERR")
                .body(body.toString().toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun mockThrowing(error: IOException): OkHttpClient {
        val interceptor = Interceptor { throw error }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun runView(): JSONObject = JSONObject(
        """
        { "flowRun": { "id": "fr_1", "state": "pending", "outcome": null,
                       "cursorStepId": null, "environment": "production",
                       "pendingAction": null },
          "definitionSteps": [], "stepRuns": [], "branding": null }
        """.trimIndent()
    )

    @Test
    fun `get sends a Bearer header and never puts the token in the URL`() {
        var seen: Request? = null
        val http = mockClient(200, runView(), captured = { seen = it })
        val client = FlowsClient("fr_1", "tok_abc", "https://api.usesense.ai", http)

        client.get()

        val req = seen!!
        assertEquals("/v1/sdk/flow-runs/fr_1", req.url.encodedPath)
        assertEquals("Bearer tok_abc", req.header("Authorization"))
        assertFalse("token must not appear in the URL", req.url.toString().contains("tok_abc"))
    }

    @Test
    fun `advance posts inputs as the JSON body`() {
        var seen: Request? = null
        val http = mockClient(200, runView(), captured = { seen = it })
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)

        client.advance(JSONObject().put("document_id", "doc_1"))

        val req = seen!!
        assertEquals("POST", req.method)
        assertEquals("/v1/sdk/flow-runs/fr_1/advance", req.url.encodedPath)
        val sink = okio.Buffer().also { req.body!!.writeTo(it) }
        val parsed = JSONObject(sink.readUtf8())
        val inputs = parsed.getJSONObject("inputs")
        assertEquals("doc_1", inputs.getString("document_id"))
    }

    @Test
    fun `401 translates to FlowError TOKEN_EXPIRED`() {
        val body = JSONObject().put("error", "SDK token has expired").put("code", "token_expired")
        val http = mockClient(401, body)
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)
        try {
            client.get(); throw AssertionError("expected throw")
        } catch (e: FlowError) {
            assertEquals(FlowError.Code.TOKEN_EXPIRED, e.code)
        }
    }

    @Test
    fun `403 translates to FlowError TOKEN_INVALID`() {
        val body = JSONObject().put("error", "Invalid token").put("code", "forbidden")
        val http = mockClient(403, body)
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)
        try {
            client.get(); throw AssertionError("expected throw")
        } catch (e: FlowError) {
            assertEquals(FlowError.Code.TOKEN_INVALID, e.code)
        }
    }

    @Test
    fun `5xx translates to FlowError PROVIDER_UNAVAILABLE`() {
        val body = JSONObject().put("error", "unavailable")
        val http = mockClient(503, body)
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)
        try {
            client.advance(JSONObject()); throw AssertionError("expected throw")
        } catch (e: FlowError) {
            assertEquals(FlowError.Code.PROVIDER_UNAVAILABLE, e.code)
        }
    }

    @Test
    fun `transport error translates to FlowError NETWORK_UNAVAILABLE`() {
        val http = mockThrowing(IOException("not connected"))
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)
        try {
            client.cancel(); throw AssertionError("expected throw")
        } catch (e: FlowError) {
            assertEquals(FlowError.Code.NETWORK_UNAVAILABLE, e.code)
        }
    }

    @Test
    fun `initSession decodes the wire response and injects a synthetic expires_at`() {
        val body = JSONObject(
            """
            {
              "session_id": "sess_abc",
              "session_token": "tok_xyz",
              "nonce": "nonce_1",
              "policy": {
                "requires_audio": false,
                "requires_stepup": false,
                "challenge_type": "none"
              },
              "upload": {
                "max_frames": 24,
                "target_fps": 6,
                "capture_duration_ms": 4000
              }
            }
            """.trimIndent()
        )
        val http = mockClient(200, body)
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)

        val response = client.initSession(toolId = null)

        assertEquals("sess_abc", response.sessionId)
        assertEquals("tok_xyz", response.sessionToken)
        assertEquals("nonce_1", response.nonce)
        assertEquals(24, response.upload.maxFrames)
        assertEquals(6, response.upload.targetFps)
        // expires_at is omitted by the server; the client synthesises one to
        // satisfy the Moshi adapter. Anything non-empty is enough — the
        // capture pipeline does not consume the field.
        assertTrue("expiresAt must be populated", response.expiresAt.isNotEmpty())
    }

    @Test
    fun `translate map matches the documented HTTP status to FlowError code table`() {
        assertEquals(FlowError.Code.TOKEN_EXPIRED, FlowsClient.translate(401, null, "m").code)
        assertEquals(FlowError.Code.TOKEN_INVALID, FlowsClient.translate(403, null, "m").code)
        assertEquals(FlowError.Code.PROVIDER_UNAVAILABLE, FlowsClient.translate(503, null, "m").code)
        assertEquals(FlowError.Code.PROVIDER_UNAVAILABLE, FlowsClient.translate(200, "provider_unavailable", "m").code)
        assertEquals(FlowError.Code.UNKNOWN, FlowsClient.translate(418, null, "m").code)
        assertTrue("code is preserved on the wire", FlowsClient.translate(401, "x", "m").serverCode == "x")
    }
}

