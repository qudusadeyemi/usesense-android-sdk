package com.usesense.sdk.v4

import com.usesense.sdk.api.V4VerificationRequest
import com.usesense.sdk.api.V4Verdict
import com.usesense.sdk.capture.ZoomMotionStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

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
        stats: ZoomMotionStats
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
        val uploadConn = (signalsUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("x-usesense-sdk-version", "v4")
            setRequestProperty("x-session-token", request.sessionToken)
            setRequestProperty("x-nonce", request.nonce)
            setRequestProperty("x-environment", request.environment)
            setRequestProperty("x-idempotency-key", UUID.randomUUID().toString())
        }
        uploadConn.outputStream.use { it.write(body) }
        if (uploadConn.responseCode !in 200..299) {
            val errBody = try { uploadConn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            throw IOException("v4 upload failed status=${uploadConn.responseCode} body=$errBody")
        }
        uploadConn.inputStream.close()
        uploadConn.disconnect()

        val resultUrl = URL(
            "${request.apiBaseUrl.trimEnd('/')}/sessions/${request.sessionId}/result" +
            "?env=${request.environment}&nonce=${request.nonce}"
        )
        val resultConn = (resultUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("x-usesense-sdk-version", "v4")
            setRequestProperty("x-session-token", request.sessionToken)
            setRequestProperty("x-nonce", request.nonce)
            setRequestProperty("x-environment", request.environment)
        }
        if (resultConn.responseCode !in 200..299) {
            val errBody = try { resultConn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            resultConn.disconnect()
            throw IOException("v4 result failed status=${resultConn.responseCode} body=$errBody")
        }
        val text = resultConn.inputStream.bufferedReader().readText()
        resultConn.disconnect()
        return V4Verdict.fromWire(JSONObject(text))
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
}
