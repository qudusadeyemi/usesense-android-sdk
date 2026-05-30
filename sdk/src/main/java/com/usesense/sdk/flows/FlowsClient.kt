package com.usesense.sdk.flows

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the SDK Runner endpoint family under /v1/sdk/flow-runs.
 * Bearer auth (Authorization: Bearer <sdkToken>) so the token never lands in
 * URLs (which end up in server logs and browser history). The OkHttpClient is
 * injectable for tests — pass a client with a stubbed Dispatcher / Interceptor
 * to verify behaviour without hitting the network.
 */
class FlowsClient(
    private val flowRunId: String,
    private val sdkToken: String,
    apiBaseUrl: String = "https://api.usesense.ai",
    private val httpClient: OkHttpClient = defaultClient(),
) {

    private val baseUrl = apiBaseUrl.trimEnd('/')
    private val jsonMediaType = "application/json".toMediaType()

    private fun url(suffix: String): String = "$baseUrl/v1/sdk/flow-runs/$flowRunId$suffix"

    private fun request(method: String, suffix: String, body: JSONObject? = null): Request {
        val builder = Request.Builder()
            .url(url(suffix))
            .header("Authorization", "Bearer $sdkToken")
        when (method) {
            "GET" -> builder.get()
            "POST" -> {
                val payload = (body ?: JSONObject()).toString().toRequestBody(jsonMediaType)
                builder.post(payload)
            }
            else -> error("Unsupported HTTP method: $method")
        }
        return builder.build()
    }

    /** Round-trip a request; map transport / server faults onto FlowError. */
    private fun send(request: Request): JSONObject {
        val response: Response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw FlowError(FlowError.Code.NETWORK_UNAVAILABLE, e.message ?: "Network unavailable")
        }
        response.use { res ->
            val raw = res.body?.string().orEmpty()
            val envelope = runCatching { JSONObject(raw) }.getOrNull()
            if (res.isSuccessful) {
                return envelope
                    ?: throw FlowError(FlowError.Code.UNKNOWN, "Empty / non-JSON success body")
            }
            val code = envelope?.optString("code", null)
            val message = envelope?.optString("error", null) ?: "Request failed with status ${res.code}"
            throw translate(res.code, code, message)
        }
    }

    fun get(): FlowRunView = FlowRunView.decode(send(request("GET", "")))

    fun advance(inputs: JSONObject): FlowRunView {
        val body = JSONObject().put("inputs", inputs)
        return FlowRunView.decode(send(request("POST", "/advance", body)))
    }

    fun cancel(): FlowRunView = FlowRunView.decode(send(request("POST", "/cancel")))

    data class InitSessionResponse(val rawJson: JSONObject) {
        // The response is the same shape sessions use; slice 5b-2 will decode
        // this through the existing session models once the injection seam
        // (analog to iOS UseSenseSession.injectHostedSessionData) is opened.
        val sessionId: String get() = rawJson.getString("session_id")
        val sessionToken: String get() = rawJson.getString("session_token")
        val nonce: String get() = rawJson.getString("nonce")
    }

    fun initSession(toolId: String?): InitSessionResponse {
        val body = JSONObject().apply { toolId?.let { put("toolId", it) } }
        return InitSessionResponse(send(request("POST", "/init-session", body)))
    }

    data class UploadDocumentResponse(val documentId: String, val status: String, val reason: String?)

    fun uploadDocument(data: String, mimeType: String, side: String, documentType: String?): UploadDocumentResponse {
        val body = JSONObject().apply {
            put("data", data)
            put("mimeType", mimeType)
            put("side", side)
            documentType?.let { put("documentType", it) }
        }
        val res = send(request("POST", "/documents", body))
        return UploadDocumentResponse(
            documentId = res.getString("document_id"),
            status = res.getString("status"),
            reason = res.optString("reason", null),
        )
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Map server HTTP/JSON error envelope to the SDK's FlowError taxonomy.
         * Kept as a static so tests verify the translation independently of I/O.
         */
        fun translate(status: Int, code: String?, message: String): FlowError {
            return when {
                status == 401 || code == "token_expired" -> FlowError(FlowError.Code.TOKEN_EXPIRED, message, code)
                status == 403 || code == "forbidden" -> FlowError(FlowError.Code.TOKEN_INVALID, message, code)
                status >= 500 || code == "provider_unavailable" || code == "internal" ->
                    FlowError(FlowError.Code.PROVIDER_UNAVAILABLE, message, code)
                else -> FlowError(FlowError.Code.UNKNOWN, message, code)
            }
        }
    }
}
