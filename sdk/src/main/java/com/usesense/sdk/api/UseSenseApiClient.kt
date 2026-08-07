package com.usesense.sdk.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseEnvironment
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.api.models.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import com.usesense.sdk.signals.DeviceSignalCollector

internal class UseSenseApiClient(private val config: UseSenseConfig) {

    companion object {
        /**
         * Write timeout for the signals upload, the one request that carries
         * megabytes. Everything else is small JSON and finishes well inside it.
         */
        const val UPLOAD_WRITE_TIMEOUT_SECONDS = 300L

        /**
         * gzip a payload, or null if that would not help.
         *
         * The server detects compression from the gzip magic bytes, and
         * [GZIPOutputStream] emits proper RFC 1952 framing, so no hand-rolled
         * header is needed here. Callers fall back to the raw bytes on null.
         */
        internal fun gzip(data: ByteArray): ByteArray? {
            if (data.isEmpty()) return null
            return try {
                val out = ByteArrayOutputStream()
                GZIPOutputStream(out).use { it.write(data) }
                val compressed = out.toByteArray()
                // Only worth sending if it actually got smaller.
                if (compressed.size < data.size) compressed else null
            } catch (e: IOException) {
                null
            }
        }
    }

    /**
     * Fires as the signals body goes out: (bytesSent, totalBytes).
     *
     * A multi-megabyte upload was previously an indeterminate spinner the
     * subject could not tell apart from a hang.
     */
    var onUploadProgress: ((Long, Long) -> Unit)? = null

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Volatile
    var sessionToken: String? = null

    @Volatile
    var nonce: String? = null

    /**
     * Auth interceptor: adds x-environment header, session token, nonce dual-delivery.
     * Supabase gateway headers are NO LONGER sent -- the Cloudflare Worker proxy
     * at api.usesense.ai injects them server-side.
     */
    private val sessionInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

        // Session token (for upload, complete, status endpoints)
        sessionToken?.let { builder.addHeader("X-Session-Token", it) }

        // Nonce dual-delivery: send in BOTH header AND query param
        val currentNonce = nonce
        if (currentNonce != null) {
            builder.addHeader("X-Nonce", currentNonce)
            val urlWithNonce = original.url.newBuilder()
                .addQueryParameter("nonce", currentNonce)
                .build()
            builder.url(urlWithNonce)
        }

        // Environment
        val env = if (config.environment == UseSenseEnvironment.AUTO) {
            UseSenseEnvironment.fromApiKey(config.apiKey)
        } else {
            config.environment
        }
        val envValue = when (env) {
            UseSenseEnvironment.SANDBOX -> "sandbox"
            else -> "production"
        }

        // x-environment header (required on all requests)
        builder.addHeader("x-environment", envValue)

        // env query parameter
        val currentUrl = builder.build().url
        val urlWithEnv = currentUrl.newBuilder()
            .addQueryParameter("env", envValue)
            .build()
        builder.url(urlWithEnv)

        // Single source of truth; a hardcoded literal here silently went stale
        // at 4.1.0 across five releases.
        builder.addHeader("User-Agent", "UseSense-Android-SDK/${DeviceSignalCollector.SDK_VERSION}")

        // v4 SDK opt-in. Server v4-flag-resolver enforces the org feature
        // flag in addition to this header (PRD section 9.1).
        if (config.liveSenseV4Enabled) {
            builder.addHeader("x-usesense-sdk-version", "v4")
        }

        chain.proceed(builder.build())
    }

    /**
     * Idempotency key interceptor for upload and complete endpoints.
     */
    private val idempotencyInterceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath

        if (path.contains("/signals") || path.contains("/complete")) {
            val sessionId = sessionToken ?: "unknown"
            val idempotencyKey = "${sessionId}_${System.currentTimeMillis()}_${UUID.randomUUID()}"
            val newRequest = request.newBuilder()
                .addHeader("X-Idempotency-Key", idempotencyKey)
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }

    /**
     * Retry interceptor per spec 1.7:
     * - Network errors: up to 3 retries with 1s, 2s, 4s backoff
     * - 5xx: up to 2 retries with 2s delay
     * - 429: respect Retry-After header
     * - Other 4xx: do NOT retry
     */
    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        var response: okhttp3.Response? = null
        var exception: IOException? = null
        val maxAttempts = 4 // initial + 3 retries
        val backoffDelays = longArrayOf(0, 1000, 2000, 4000)

        for (attempt in 0 until maxAttempts) {
            try {
                if (attempt > 0) {
                    Thread.sleep(backoffDelays[attempt])
                }
                response?.close()
                response = chain.proceed(request)

                when {
                    response.isSuccessful -> return@Interceptor response
                    response.code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 2L
                        response.close()
                        Thread.sleep(retryAfter * 1000)
                        continue
                    }
                    response.code in 500..599 -> {
                        if (attempt >= 2) return@Interceptor response // max 2 retries for 5xx
                        response.close()
                        Thread.sleep(2000)
                        continue
                    }
                    else -> return@Interceptor response // 4xx: don't retry
                }
            } catch (e: IOException) {
                exception = e
                response?.close()
                response = null
            }
        }
        response ?: throw (exception ?: IOException("Request failed after retries"))
    }

    /**
     * Wraps the signals request body so the bytes can be counted as they go
     * out. Applied here rather than per-part so it measures the fully assembled
     * multipart body -- frames, metadata and audio -- which is what the subject
     * is actually waiting on.
     */
    private val uploadProgressInterceptor = Interceptor { chain ->
        val request = chain.request()
        val body = request.body
        if (body == null || !request.url.encodedPath.endsWith("/signals")) {
            chain.proceed(request)
        } else {
            val wrapped = ProgressRequestBody(body) { sent, total ->
                onUploadProgress?.invoke(sent, total)
            }
            chain.proceed(request.newBuilder().method(request.method, wrapped).build())
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(sessionInterceptor)
        .addInterceptor(idempotencyInterceptor)
        .addInterceptor(retryInterceptor)
        .addInterceptor(uploadProgressInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // The signals upload is the only request carrying megabytes. A 30s
        // write timeout leaves no headroom on a slow mobile uplink -- a
        // measured production session managed 14.6 KB/s -- and a tripped
        // timeout looks to the subject like a hang, not a retryable error.
        .writeTimeout(UPLOAD_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val service: UseSenseApiService = Retrofit.Builder()
        .baseUrl(config.baseUrl.trimEnd('/') + "/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(UseSenseApiService::class.java)

    suspend fun createSession(request: CreateSessionRequest): Result<CreateSessionResponse> {
        return executeCall { service.createSession(config.apiKey, request) }.also { result ->
            result.getOrNull()?.let {
                sessionToken = it.sessionToken
                nonce = it.nonce
            }
        }
    }

    /**
     * Exchange a client_token for full session credentials (server-side init flow).
     * No API key required -- the token itself authenticates.
     */
    suspend fun exchangeToken(clientToken: String): Result<CreateSessionResponse> {
        return executeCall {
            service.exchangeToken(ExchangeTokenRequest(clientToken))
        }.also { result ->
            result.getOrNull()?.let {
                sessionToken = it.sessionToken
                nonce = it.nonce
            }
        }
    }

    suspend fun uploadSignals(
        sessionId: String,
        frames: List<ByteArray>,
        metadataJson: ByteArray,
        audioData: ByteArray? = null,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): Result<UploadSignalsResponse> {
        val frameParts = frames.mapIndexed { index, bytes ->
            MultipartBody.Part.createFormData(
                "frames[]",
                "frame_$index.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType()),
            )
        }

        // gzip the metadata. It is a few hundred KB of JSON on every session and
        // OkHttp does not compress request bodies on its own. The server sniffs
        // the gzip magic bytes rather than the filename, so an older server (or
        // a null here) still reads the plain JSON.
        val gzippedMetadata = gzip(metadataJson)
        val metadataPart = if (gzippedMetadata != null) {
            MultipartBody.Part.createFormData(
                "metadata",
                "metadata.json.gz",
                gzippedMetadata.toRequestBody("application/gzip".toMediaType()),
            )
        } else {
            MultipartBody.Part.createFormData(
                "metadata",
                "metadata.json",
                metadataJson.toRequestBody("application/json".toMediaType()),
            )
        }

        val audioPart = audioData?.let {
            MultipartBody.Part.createFormData(
                "audio",
                "audio.m4a",
                it.toRequestBody("audio/mp4".toMediaType()),
            )
        }

        return executeCall { service.uploadSignals(sessionId, frameParts, metadataPart, audioPart) }
    }

    suspend fun completeSession(sessionId: String): Result<VerdictResponse> {
        return executeCall { service.completeSession(sessionId) }
    }

    suspend fun getSessionStatus(sessionId: String): Result<SessionStatusResponse> {
        return executeCall { service.getSessionStatus(sessionId) }
    }

    // ── Remote Enrollment (Hosted Page) ──

    suspend fun getRemoteEnrollmentData(id: String): Result<RemoteEnrollmentDataWrapper> {
        return executeCall { service.getRemoteEnrollmentData(id) }
    }

    suspend fun markEnrollmentOpened(id: String): Result<Unit> {
        return executeCallAllowEmptyBody { service.markEnrollmentOpened(id) }
    }

    suspend fun initEnrollmentSession(id: String): Result<HostedInitSessionResponse> {
        return executeCall { service.initEnrollmentSession(id) }.also { result ->
            result.getOrNull()?.let {
                sessionToken = it.sessionToken
                nonce = it.nonce
            }
        }
    }

    suspend fun completeEnrollment(id: String): Result<HostedCompleteResponse> {
        return executeCall { service.completeEnrollment(id) }
    }

    // ── Remote Session / Verification (Hosted Page) ──

    suspend fun getRemoteSessionData(id: String): Result<RemoteSessionDataWrapper> {
        return executeCall { service.getRemoteSessionData(id) }
    }

    suspend fun markSessionOpened(id: String): Result<Unit> {
        return executeCallAllowEmptyBody { service.markSessionOpened(id) }
    }

    suspend fun initVerificationSession(id: String): Result<HostedInitSessionResponse> {
        return executeCall { service.initVerificationSession(id) }.also { result ->
            result.getOrNull()?.let {
                sessionToken = it.sessionToken
                nonce = it.nonce
            }
        }
    }

    suspend fun completeRemoteSession(id: String): Result<HostedCompleteResponse> {
        return executeCall { service.completeRemoteSession(id) }
    }

    suspend fun disputeSession(id: String): Result<DisputeResponse> {
        return executeCall { service.disputeSession(id) }
    }

    fun clearSession() {
        sessionToken = null
        nonce = null
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> executeCallAllowEmptyBody(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                Result.success(response.body() ?: Unit as T)
            } else {
                val errorBody = response.errorBody()?.string()
                val parsed = try {
                    errorBody?.let { moshi.adapter(ErrorResponse::class.java).fromJson(it) }
                } catch (_: Exception) { null }
                Result.failure(ApiException(
                    UseSenseError.fromServerError(response.code(), parsed?.error?.code,
                        parsed?.error?.message ?: getUserMessage(response.code(), parsed?.error?.code))
                ))
            }
        } catch (e: IOException) {
            Result.failure(ApiException(UseSenseError.networkError(e.message)))
        } catch (e: Exception) {
            Result.failure(ApiException(UseSenseError.networkError(e.message)))
        }
    }

    private suspend fun <T> executeCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(
                        ApiException(
                            UseSenseError.fromServerError(response.code(), null, "Empty response body")
                        )
                    )
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val parsed = try {
                    errorBody?.let { moshi.adapter(ErrorResponse::class.java).fromJson(it) }
                } catch (_: Exception) {
                    null
                }

                val error = UseSenseError.fromServerError(
                    response.code(),
                    parsed?.error?.code,
                    parsed?.error?.message ?: getUserMessage(response.code(), parsed?.error?.code)
                )
                Result.failure(ApiException(error))
            }
        } catch (e: IOException) {
            Result.failure(ApiException(UseSenseError.networkError(e.message)))
        } catch (e: Exception) {
            Result.failure(ApiException(UseSenseError.networkError(e.message)))
        }
    }

    private fun getUserMessage(httpStatus: Int, serverCode: String?): String {
        return when (httpStatus) {
            400 -> "Invalid request. Please check the parameters."
            401 -> when (serverCode) {
                "session_expired" -> "Your session has expired. Please start over."
                "nonce_mismatch" -> "Nonce does not match the session nonce."
                "invalid_token" -> "Session token is invalid."
                else -> "Authentication failed. Check API key."
            }
            402 -> "Insufficient verification credits."
            404 -> when (serverCode) {
                "identity_not_found" -> "Identity not found."
                "session_not_found" -> "Session not found."
                "token_not_found" -> "Token not found or invalid."
                else -> "Endpoint not found. Verify Backend URL."
            }
            409 -> when (serverCode) {
                "session_already_completed" -> "Session has already been completed."
                "token_already_used" -> "Token has already been exchanged."
                else -> "Conflict: $serverCode"
            }
            410 -> "Session has expired. Please start a new session."
            429 -> "Rate limit reached. Try again later."
            500 -> "Server error. Please try again."
            503 -> "Service unavailable. Try again later."
            else -> "HTTP $httpStatus"
        }
    }
}

internal class ApiException(val useSenseError: UseSenseError) : Exception(useSenseError.message)

/**
 * RequestBody that reports how much of itself has been written.
 *
 * OkHttp gives no upload-progress hook, so the body counts its own bytes on
 * the way to the socket.
 */
internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val listener: (bytesSent: Long, totalBytes: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        // A retry re-enters writeTo, so progress restarts from zero -- which is
        // the honest thing to show.
        val counting = object : ForwardingSink(sink) {
            private var written = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) listener(written, total)
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
