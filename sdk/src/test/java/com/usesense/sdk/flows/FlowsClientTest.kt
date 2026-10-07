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

    @Test
    fun `get and advance declare device_signals_v1`() {
        val seen = mutableListOf<Request>()
        val http = mockClient(200, runView(), captured = { seen.add(it) })
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)

        client.get()
        client.advance(JSONObject())

        assertEquals("device_signals_v1", seen[0].url.queryParameter("caps"))
        assertEquals("/v1/sdk/flow-runs/fr_1", seen[0].url.encodedPath)
        val sink = okio.Buffer().also { seen[1].body!!.writeTo(it) }
        val client1 = JSONObject(sink.readUtf8()).getJSONObject("client")
        assertEquals("android", client1.getString("sdk"))
        assertEquals("device_signals_v1", client1.getJSONArray("capabilities").getString(0))
    }

    @Test
    fun `submitDeviceSignals posts the nonce and channel_integrity`() {
        var seen: Request? = null
        val http = mockClient(200, runView(), captured = { seen = it })
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", http)

        client.submitDeviceSignals("dn_1", JSONObject().put("is_emulator", false))

        val req = seen!!
        assertEquals("POST", req.method)
        assertEquals("/v1/sdk/flow-runs/fr_1/device-signals", req.url.encodedPath)
        val sink = okio.Buffer().also { req.body!!.writeTo(it) }
        val parsed = JSONObject(sink.readUtf8())
        assertEquals("dn_1", parsed.getString("nonce"))
        assertFalse(parsed.getJSONObject("channel_integrity").getBoolean("is_emulator"))
        assertEquals("device_signals_v1", parsed.getJSONObject("client").getJSONArray("capabilities").getString(0))
    }

    @Test
    fun `a stale nonce keeps its server code so the runner re-reads`() {
        val body = JSONObject().put("error", "Nonce does not match").put("code", "nonce_mismatch")
        val client = FlowsClient("fr_1", "t", "https://api.usesense.ai", mockClient(400, body))
        try {
            client.submitDeviceSignals("dn_old", JSONObject()); throw AssertionError("expected throw")
        } catch (e: FlowError) {
            assertEquals("nonce_mismatch", e.serverCode)
            assertTrue(DeviceTrustSignals.needsReload(e.serverCode))
        }
        assertTrue(DeviceTrustSignals.needsReload("device_step_not_pending"))
        assertFalse(DeviceTrustSignals.needsReload("invalid_input"))
    }

    @Test
    fun `a device capture action decodes with its nonce`() {
        val action = PendingAction.decode(
            JSONObject().put("kind", "capture").put("capture", "device")
                .put("toolId", "device_trust_check").put("nonce", "dn_9"),
        )
        assertEquals(PendingAction.CaptureDevice(toolId = "device_trust_check", nonce = "dn_9"), action)
        assertEquals(
            PendingAction.CaptureDevice(toolId = null, nonce = null),
            PendingAction.decode(JSONObject().put("kind", "capture").put("capture", "device")),
        )
    }

    @Test
    fun `device signals drop capture-only keys and carry telemetry`() {
        val collected = JSONObject()
            .put("platform", "android").put("is_rooted", false)
            .put("camera_facing", "front").put("camera_resolution", "640x480")
            .put("camera_permission_granted", false).put("microphone_permission_granted", false)
            .put("accelerometer_data", org.json.JSONArray()).put("gyroscope_data", org.json.JSONArray())
            .put("device_memory", 8.0)
        val telemetry = JSONObject().put("cpu_abi", "arm64-v8a").put("device_memory", 1.0)

        val out = DeviceTrustSignals.build(collected, telemetry)

        for (k in DeviceTrustSignals.CAPTURE_ONLY_KEYS) assertFalse("$k must be dropped", out.has(k))
        assertEquals("android", out.getString("platform"))
        assertEquals("arm64-v8a", out.getString("cpu_abi"))
        assertEquals(8.0, out.getDouble("device_memory"), 0.0)
    }
}

