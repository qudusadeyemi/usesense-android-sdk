package com.usesense.sdk.devicecheck

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Posts one phase of a silent device check, with one retry for a 5xx / 429
 * or a dropped connection. Bearer auth, so the token never lands in a URL.
 * The call is enqueued, not executed, so coroutine cancellation (the
 * DeviceCheck timeout) cancels the request itself.
 *
 * No Android types: covered by JVM unit tests with an interceptor fake.
 */
internal class DeviceCheckTransport(
    baseUrl: String,
    deviceCheckId: String,
    private val token: String,
    private val userAgent: String,
    private val httpClient: OkHttpClient = defaultClient(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private val url = DeviceCheckProtocol.signalsUrl(baseUrl, deviceCheckId)
    private val json = "application/json".toMediaType()

    suspend fun send(
        phase: DeviceCheckPhase,
        body: JSONObject,
    ): DeviceCheckResult {
        var retriedAfterTransportFailure = false
        var attempt = 0
        while (true) {
            attempt++
            val outcome =
                try {
                    execute(body)
                } catch (e: IOException) {
                    null.also { log("${phase.wire} attempt $attempt: no connection (${e.javaClass.simpleName})") }
                }

            if (outcome == null) {
                if (attempt == 1) {
                    retriedAfterTransportFailure = true
                    sleep(DeviceCheckProtocol.RETRY_BACKOFF_MS)
                    continue
                }
                return DeviceCheckResult.Failed(phase, DeviceCheckError(DeviceCheckError.Code.NETWORK_ERROR, "Could not reach UseSense"))
            }

            if (outcome.status in 200..299) {
                log("${phase.wire} accepted (${outcome.status})")
                return DeviceCheckResult.Sent(phase)
            }
            val envelope = runCatching { JSONObject(outcome.body) }.getOrNull()
            val serverCode = envelope?.optString("code")?.takeIf { it.isNotEmpty() }
            val serverMessage = envelope?.optString("error")?.takeIf { it.isNotEmpty() }
            if (DeviceCheckProtocol.firstAttemptLanded(phase, retriedAfterTransportFailure, outcome.status, serverCode)) {
                log("${phase.wire} had already landed before the retry")
                return DeviceCheckResult.Sent(phase)
            }
            if (attempt == 1 && DeviceCheckProtocol.isRetryableStatus(outcome.status)) {
                log("${phase.wire} attempt 1: HTTP ${outcome.status} ${serverCode ?: ""}, retrying once")
                sleep(DeviceCheckProtocol.backoffMs(outcome.retryAfter))
                continue
            }
            log("${phase.wire} refused: HTTP ${outcome.status} ${serverCode ?: ""}")
            return DeviceCheckResult.Failed(phase, DeviceCheckProtocol.errorFor(outcome.status, serverCode, serverMessage))
        }
    }

    private class Answer(val status: Int, val body: String, val retryAfter: String?)

    /** One POST; throws IOException on transport failure. */
    private suspend fun execute(body: JSONObject): Answer {
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", userAgent)
                .post(body.toString().toRequestBody(json))
                .build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val answer =
                            try {
                                response.use { Answer(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
                            } catch (e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                                return
                            }
                        if (cont.isActive) cont.resume(answer)
                    }
                },
            )
        }
    }

    companion object {
        /** Per request; two attempts plus the backoff stay inside DeviceCheckConfig.DEFAULT_TIMEOUT_MS. */
        const val CALL_TIMEOUT_SECONDS = 15L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
