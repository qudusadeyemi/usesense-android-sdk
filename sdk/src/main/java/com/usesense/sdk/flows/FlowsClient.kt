package com.usesense.sdk.flows

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.usesense.sdk.api.models.CreateSessionResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    /**
     * Initialise a face-capture session for the parked Flows step. Returns a
     * `CreateSessionResponse` so the runner can hand it to the
     * UseSenseSession injection seam. The server response shape is nearly
     * identical to /v1/sessions; the only difference is `expires_at` is
     * omitted (the parent flow run owns the wall-clock), so we inject a
     * synthetic 15-minute expiry to satisfy the Moshi-generated adapter.
     */
    fun initSession(toolId: String?): CreateSessionResponse {
        val body = JSONObject().apply { toolId?.let { put("toolId", it) } }
        val responseJson = send(request("POST", "/init-session", body))
        if (!responseJson.has("expires_at") || responseJson.isNull("expires_at")) {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            responseJson.put("expires_at", fmt.format(Date(System.currentTimeMillis() + 15 * 60_000)))
        }
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(CreateSessionResponse::class.java)
        return adapter.fromJson(responseJson.toString())
            ?: throw FlowError(FlowError.Code.UNKNOWN, "Failed to decode init-session response")
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
