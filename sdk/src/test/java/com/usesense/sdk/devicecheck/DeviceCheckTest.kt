package com.usesense.sdk.devicecheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The public DeviceCheck: ordering, never throwing, timeouts, callbacks. */
class DeviceCheckTest {
    private val config = DeviceCheckConfig("dc_6f1c2a9b4e", "dct_" + "c".repeat(48), "dn_2b7d9f00aa11")

    private class FakeSignals(
        val startDelayMs: Long = 0,
        val throwOnStart: Throwable? = null,
    ) : DeviceCheckSignalSource {
        var startNonce: String? = null
        var startCalls = 0

        override suspend fun start(
            scope: CoroutineScope,
            nonce: String,
        ): CollectedSignals {
            startCalls++
            startNonce = nonce
            if (startDelayMs > 0) delay(startDelayMs)
            throwOnStart?.let { throw it }
            return CollectedSignals(JSONObject().put("is_emulator", false), DeviceCheckProtocol.client("4.15.0"))
        }

        override suspend fun finish(): CollectedSignals =
            CollectedSignals(DeviceCheckProtocol.cameraInventory(emptyList()), DeviceCheckProtocol.client("4.15.0"))
    }

    private val sent = mutableListOf<JSONObject>()

    private fun http(vararg statuses: Int): OkHttpClient {
        val queue = ArrayDeque(statuses.toList())
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    synchronized(sent) { sent += JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8()) }
                    val status = queue.removeFirstOrNull() ?: 202
                    val body = if (status == 202) """{"received":true}""" else """{"error":"e","code":"already_started"}"""
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("x")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            ).build()
    }

    private fun check(
        signals: DeviceCheckSignalSource = FakeSignals(),
        cfg: DeviceCheckConfig = config,
        client: OkHttpClient = http(),
    ) = DeviceCheck(
        config = cfg,
        signals = signals,
        transport = DeviceCheckTransport("https://api.usesense.ai", cfg.deviceCheckId, cfg.token, "ua", client, sleep = {}),
        ioDispatcher = Dispatchers.IO,
        callbackDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `start then finish send both phases with the check's nonce`() =
        runBlocking {
            val signals = FakeSignals()
            val c = check(signals)
            assertEquals(DeviceCheckResult.Sent(DeviceCheckPhase.START), c.start())
            assertEquals(DeviceCheckResult.Sent(DeviceCheckPhase.FINISH), c.finish())

            assertEquals("dn_2b7d9f00aa11", signals.startNonce)
            assertEquals(listOf("start", "finish"), sent.map { it.getString("phase") })
            assertTrue(sent.all { it.getString("nonce") == "dn_2b7d9f00aa11" })
            assertTrue(sent[1].getJSONObject("channel_integrity").has("camera_inventory"))
        }

    @Test
    fun `a second start after an accepted one is refused locally`() =
        runBlocking {
            val signals = FakeSignals()
            val c = check(signals)
            assertTrue(c.start().isSuccess)
            val again = c.start() as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.OUT_OF_ORDER, again.error.code)
            assertEquals(1, signals.startCalls)
            assertEquals(1, sent.size)
        }

    @Test
    fun `a malformed config sends nothing and does not throw`() =
        runBlocking {
            val signals = FakeSignals()
            val c = check(signals, cfg = config.copy(token = "sk_prod_oops"))
            assertEquals(DeviceCheckError.Code.INVALID_CONFIG, (c.start() as DeviceCheckResult.Failed).error.code)
            assertEquals(DeviceCheckError.Code.INVALID_CONFIG, (c.finish() as DeviceCheckResult.Failed).error.code)
            assertEquals(0, signals.startCalls)
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a collector that throws becomes a Failed result`() =
        runBlocking {
            val r = check(FakeSignals(throwOnStart = IllegalStateException("EGL exploded"))).start() as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.UNKNOWN, r.error.code)
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a slow collection times out instead of hanging`() =
        runBlocking {
            val r = check(FakeSignals(startDelayMs = 5_000), cfg = config.copy(timeoutMs = 50)).start() as DeviceCheckResult.Failed
            assertEquals(DeviceCheckError.Code.TIMEOUT, r.error.code)
            assertTrue(r.error.isRetryable)
        }

    @Test
    fun `the callback variant delivers the result`() {
        val got = AtomicReference<DeviceCheckResult>()
        val latch = CountDownLatch(1)
        check().start { got.set(it); latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(DeviceCheckResult.Sent(DeviceCheckPhase.START), got.get())
    }

    @Test
    fun `after cancel nothing more is sent`() =
        runBlocking {
            val c = check()
            c.cancel()
            assertEquals(DeviceCheckError.Code.CANCELLED, (c.start() as DeviceCheckResult.Failed).error.code)
            assertTrue(sent.isEmpty())
        }
}
