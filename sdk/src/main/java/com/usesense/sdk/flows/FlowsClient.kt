package com.usesense.sdk.flows

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.usesense.sdk.api.models.CreateSessionResponse
import com.usesense.sdk.signals.DeviceSignalCollector
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
            // Identify the platform to the server at the first device contact
            // (init-session) so the flow capture session is scored on the Android
            // surface from creation rather than defaulting to web. Mirrors the
            // session API client's User-Agent.
            .header("User-Agent", "UseSense-Android-SDK/${DeviceSignalCollector.SDK_VERSION}")
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
            // 422 invalid_input — flatten details.errors into [field_key:
            // message] so the runner can highlight each offending field
            // inline instead of failing the whole run.
            val details: Map<String, String> = if (code == "invalid_input") {
                val out = mutableMapOf<String, String>()
                val errs = envelope?.optJSONObject("details")?.optJSONArray("errors")
                if (errs != null) for (i in 0 until errs.length()) {
                    val e = errs.optJSONObject(i) ?: continue
                    val k = e.optString("field_key", null) ?: continue
                    val m = e.optString("message", null) ?: continue
                    out[k] = m
                }
                out
            } else emptyMap()
            throw translate(res.code, code, message, details)
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

    /** `message` carries the server's instruction for the reasons where our
     *  built-in copy would be wrong ('too_large', 'incomplete'): only the
     *  server knows the limit that was exceeded, or that the bytes arrived
     *  cut short. Null for every other reason, where our copy is correct. */
    data class UploadDocumentResponse(
        val documentId: String,
        val status: String,
        val reason: String?,
        val message: String?,
    )

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
            message = res.optString("message", null),
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
        fun translate(status: Int, code: String?, message: String, details: Map<String, String> = emptyMap()): FlowError {
            return when {
                status == 401 || code == "token_expired" -> FlowError(FlowError.Code.TOKEN_EXPIRED, message, code)
                status == 403 || code == "forbidden" -> FlowError(FlowError.Code.TOKEN_INVALID, message, code)
                code == "invalid_input" -> FlowError(FlowError.Code.INVALID_INPUT, message, code, details)
                status >= 500 || code == "provider_unavailable" || code == "internal" ->
                    FlowError(FlowError.Code.PROVIDER_UNAVAILABLE, message, code)
                else -> FlowError(FlowError.Code.UNKNOWN, message, code)
            }
        }
    }
}
