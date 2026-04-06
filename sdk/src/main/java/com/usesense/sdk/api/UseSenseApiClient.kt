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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class UseSenseApiClient(private val config: UseSenseConfig) {

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

        builder.addHeader("User-Agent", "UseSense-Android-SDK/4.1.0")

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

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(sessionInterceptor)
        .addInterceptor(idempotencyInterceptor)
        .addInterceptor(retryInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

        val metadataPart = MultipartBody.Part.createFormData(
            "metadata",
            "metadata.json",
            metadataJson.toRequestBody("application/json".toMediaType()),
        )

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
