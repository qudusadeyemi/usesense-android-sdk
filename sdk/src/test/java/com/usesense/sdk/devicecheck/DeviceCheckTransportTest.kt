package com.usesense.sdk.devicecheck

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The signals POST: auth, URL, the one retry, and error surfacing. */
class DeviceCheckTransportTest {
    private val id = "dc_6f1c2a9b4e"
    private val token = "dct_" + "b".repeat(48)

    /** Each answer is a (status, body) pair, or an IOException to throw. */
    private class Script(vararg answers: Any) {
        val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()

        fun client(): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        val req = chain.request()
                        requests += req
                        bodies += Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                        when (val next = queue.removeFirst()) {
                            is IOException -> throw next
                            is Pair<*, *> ->
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(next.first as Int)
                                    .message("x")
                                    .body((next.second as String).toResponseBody("application/json".toMediaType()))
                                    .build()
                            else -> error("bad script")
                        }
                    },
                ).build()
    }

    private val sleeps = mutableListOf<Long>()

    private fun transport(script: Script) =
        DeviceCheckTransport(
            baseUrl = "https://api.usesense.ai/v1",
            deviceCheckId = id,
            token = token,
            userAgent = "UseSense-Android-SDK/4.15.0",
            httpClient = script.client(),
            sleep = { sleeps += it },
        )

    private val body = JSONObject().put("nonce", "dn_x1").put("phase", "start").put("channel_integrity", JSONObject())

    private fun err(
        code: String,
        message: String = "m",
    ) = JSONObject().put("error", message).put("code", code).toString()

    @Test
    fun `202 is Sent, with Bearer auth and the token never in the URL`() =
        runBlocking {
            val s = Script(202 to """{"received":true}""")
            val r = transport(s).send(DeviceCheckPhase.START, body)

            assertEquals(DeviceCheckResult.Sent(DeviceCheckPhase.START), r)
            val req = s.requests.single()
            assertEquals("POST", req.method)
            assertEquals("/v1/sdk/device-checks/$id/signals", req.url.encodedPath)
            assertEquals("Bearer $token", req.header("Authorization"))
            assertEquals("UseSense-Android-SDK/4.15.0", req.header("User-Agent"))
            assertFalse(req.url.toString().contains(token))
            assertEquals("start", JSONObject(s.bodies.single()).getString("phase"))
            assertTrue(sleeps.isEmpty())
        }

    @Test
    fun `503 is retried once with backoff, then succeeds`() =
        runBlocking {
            val s = Script(503 to err("scoring_failed"), 202 to """{"received":true}""")
            val r = transport(s).send(DeviceCheckPhase.START, body)
            assertTrue(r.isSuccess)
            assertEquals(2, s.requests.size)
            assertEquals(listOf(DeviceCheckProtocol.RETRY_BACKOFF_MS), sleeps)
        }

    @Test
    fun `503 twice surfaces SERVICE_UNAVAILABLE after exactly one retry`() =
        runBlocking {
            val s = Script(503 to err("scoring_failed"), 503 to err("scoring_failed", "Device scoring failed; send start again"))
            val r = transport(s).send(DeviceCheckPhase.START, body) as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.SERVICE_UNAVAILABLE, r.error.code)
            assertEquals("scoring_failed", r.error.serverCode)
            assertEquals(503, r.error.httpStatus)
            assertEquals(2, s.requests.size)
        }

    @Test
    fun `401 and 400 are not retried`() =
        runBlocking {
            val expired = transport(Script(401 to err("token_expired"))).send(DeviceCheckPhase.START, body) as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.TOKEN_EXPIRED, expired.error.code)

            val s = Script(400 to err("nonce_mismatch", "Nonce does not match this device check"))
            val mismatch = transport(s).send(DeviceCheckPhase.START, body) as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.NONCE_MISMATCH, mismatch.error.code)
            assertEquals("Nonce does not match this device check", mismatch.error.message)
            assertEquals(1, s.requests.size)
            assertTrue(sleeps.isEmpty())
        }

    @Test
    fun `409 out of order is surfaced`() =
        runBlocking {
            val r = transport(Script(409 to err("not_started"))).send(DeviceCheckPhase.FINISH, body) as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.OUT_OF_ORDER, r.error.code)
            assertEquals("not_started", r.error.serverCode)
            assertEquals(DeviceCheckPhase.FINISH, r.phase)
        }

    @Test
    fun `a dropped connection is retried once`() =
        runBlocking {
            val s = Script(IOException("reset"), 202 to """{"received":true}""")
            assertTrue(transport(s).send(DeviceCheckPhase.START, body).isSuccess)
            assertEquals(2, s.requests.size)
        }

    @Test
    fun `a start that landed before the connection dropped is not reported as a failure`() =
        runBlocking {
            val s = Script(IOException("read timeout"), 409 to err("already_started"))
            assertEquals(DeviceCheckResult.Sent(DeviceCheckPhase.START), transport(s).send(DeviceCheckPhase.START, body))
        }

    @Test
    fun `no connection twice is NETWORK_ERROR`() =
        runBlocking {
            val r = transport(Script(IOException("a"), IOException("b"))).send(DeviceCheckPhase.START, body) as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.NETWORK_ERROR, r.error.code)
            assertTrue(r.error.isRetryable)
        }

    @Test
    fun `a non-JSON error body still maps by status`() =
        runBlocking {
            val r = transport(Script(404 to "<html>nope</html>")).send(DeviceCheckPhase.START, body) as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.NOT_FOUND, r.error.code)
            assertEquals(null, r.error.serverCode)
        }
}
