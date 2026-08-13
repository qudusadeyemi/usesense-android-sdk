package com.usesense.sdk.v4

import com.usesense.sdk.api.V4VerificationRequest
import com.usesense.sdk.api.V4NetworkException
import com.usesense.sdk.api.V4Verdict
import com.usesense.sdk.capture.ZoomMotionStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class V4TransportTimeouts(
    val connectMs: Int = 15_000,
    val uploadMs: Int = 300_000,
    val readMs: Int = 60_000,
)

/**
 * POSTs the captured MP4 plus chain metadata to /v1/sessions/:id/signals,
 * then calls /v1/sessions/:id/result for the opaque verdict.
 *
 * Phase 1 ticket A-2. Simple java.net client; swap in OkHttp in Phase 2.
 */
internal object V4UploadClient {

    @Throws(IOException::class)
    fun upload(
        request: V4VerificationRequest,
        mp4: ByteArray,
        frameHashes: List<String>,
        terminalHashHex: String,
        signatureB64: String,
        publicKeySpkiB64: String,
        assuranceLevel: String,
        stats: ZoomMotionStats,
        timeouts: V4TransportTimeouts = V4TransportTimeouts(),
        connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        onSignalsUploaded: () -> Unit = {},
    ): V4Verdict {
        val boundary = "UseSenseV4Boundary${UUID.randomUUID()}"
        val metadata = JSONObject().apply {
            put("session_id", request.sessionId)
            put("sdk_version", "android-v4.0.0")
            put("platform", "android")
            put("source", "sdk-v4")
            put("assurance_level", assuranceLevel)
            put("frame_hashes", JSONArray(frameHashes))
            put("terminal_hash_hex", terminalHashHex)
            put("chain_signature_b64", signatureB64)
            put("public_key_spki_b64", publicKeySpkiB64)
            put("zoom_motion_stats", JSONObject().apply {
                put("scale_ratio", stats.scaleRatio)
                put("duration_ms", stats.durationMs)
                put("max_head_yaw_abs_deg", stats.maxHeadYawAbsDeg)
                put("max_head_pitch_abs_deg", stats.maxHeadPitchAbsDeg)
                put("observation_count", stats.observationCount)
            })
        }

        val signalsUrl = URL(
            "${request.apiBaseUrl.trimEnd('/')}/sessions/${request.sessionId}/signals" +
            "?env=${request.environment}&nonce=${request.nonce}"
        )

        val body = buildMultipart(boundary, mp4, metadata.toString().toByteArray(Charsets.UTF_8))
        val uploadConn = connectionFactory(signalsUrl).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeouts.connectMs
            readTimeout = timeouts.readMs
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("x-usesense-sdk-version", "v4")
            setRequestProperty("x-session-token", request.sessionToken)
            setRequestProperty("x-nonce", request.nonce)
            setRequestProperty("x-environment", request.environment)
            setRequestProperty("x-idempotency-key", UUID.randomUUID().toString())
        }
        bounded(uploadConn, V4NetworkException.Phase.SIGNALS_CONNECT, timeouts.connectMs) {
            uploadConn.connect()
        }
        bounded(uploadConn, V4NetworkException.Phase.SIGNALS_UPLOAD, timeouts.uploadMs) {
            uploadConn.outputStream.use { it.write(body) }
        }
        val uploadStatus = bounded(uploadConn, V4NetworkException.Phase.SIGNALS_RESPONSE, timeouts.readMs) {
            uploadConn.responseCode
        }
        if (uploadStatus !in 200..299) {
            val errBody = try { uploadConn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            uploadConn.disconnect()
            throw V4NetworkException(
                V4NetworkException.Phase.SIGNALS_RESPONSE,
                V4NetworkException.Kind.HTTP,
                uploadStatus,
                "v4 upload failed status=$uploadStatus body=$errBody",
            )
        }
        bounded(uploadConn, V4NetworkException.Phase.SIGNALS_RESPONSE, timeouts.readMs) {
            uploadConn.inputStream.close()
        }
        uploadConn.disconnect()
        onSignalsUploaded()

        val resultUrl = URL(
            "${request.apiBaseUrl.trimEnd('/')}/sessions/${request.sessionId}/result" +
            "?env=${request.environment}&nonce=${request.nonce}"
        )
        val resultConn = connectionFactory(resultUrl).apply {
            requestMethod = "POST"
            connectTimeout = timeouts.connectMs
            readTimeout = timeouts.readMs
            setRequestProperty("x-usesense-sdk-version", "v4")
            setRequestProperty("x-session-token", request.sessionToken)
            setRequestProperty("x-nonce", request.nonce)
            setRequestProperty("x-environment", request.environment)
        }
        bounded(resultConn, V4NetworkException.Phase.RESULT_CONNECT, timeouts.connectMs) {
            resultConn.connect()
        }
        val resultStatus = bounded(resultConn, V4NetworkException.Phase.RESULT_RESPONSE, timeouts.readMs) {
            resultConn.responseCode
        }
        if (resultStatus !in 200..299) {
            val errBody = try { resultConn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            resultConn.disconnect()
            throw V4NetworkException(
                V4NetworkException.Phase.RESULT_RESPONSE,
                V4NetworkException.Kind.HTTP,
                resultStatus,
                "v4 result failed status=$resultStatus body=$errBody",
            )
        }
        val text = bounded(resultConn, V4NetworkException.Phase.RESULT_RESPONSE, timeouts.readMs) {
            resultConn.inputStream.bufferedReader().readText()
        }
        resultConn.disconnect()
        return V4Verdict.fromWire(JSONObject(text))
    }

    private fun <T> bounded(
        connection: HttpURLConnection,
        phase: V4NetworkException.Phase,
        timeoutMs: Int,
        operation: () -> T,
    ): T {
        val future = executor.submit(Callable { operation() })
        try {
            return future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            connection.disconnect()
            future.cancel(true)
            throw V4NetworkException(phase, V4NetworkException.Kind.TIMEOUT, message = "v4 $phase timed out after ${timeoutMs}ms", cause = error)
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause ?: error
            val kind = if (cause is SocketTimeoutException) V4NetworkException.Kind.TIMEOUT else V4NetworkException.Kind.NETWORK
            throw V4NetworkException(phase, kind, message = "v4 $phase failed: ${cause.message}", cause = cause)
        }
    }

    private fun buildMultipart(boundary: String, mp4: ByteArray, metadataJson: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        fun writeString(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }

        writeString("--$boundary\r\n")
        writeString("Content-Disposition: form-data; name=\"capture\"; filename=\"capture.mp4\"\r\n")
        writeString("Content-Type: video/mp4\r\n\r\n")
        out.write(mp4)
        writeString("\r\n--$boundary\r\n")
        writeString("Content-Disposition: form-data; name=\"metadata\"; filename=\"metadata.json\"\r\n")
        writeString("Content-Type: application/json\r\n\r\n")
        out.write(metadataJson)
        writeString("\r\n--$boundary--\r\n")

        return out.toByteArray()
    }

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "usesense-v4-http").apply { isDaemon = true }
    }
}
