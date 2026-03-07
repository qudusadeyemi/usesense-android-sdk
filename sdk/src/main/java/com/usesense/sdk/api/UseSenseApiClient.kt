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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class UseSenseApiClient(private val config: UseSenseConfig) {

    companion object {
        // Default Supabase anonymous key (public, safe to bundle)
        const val DEFAULT_GATEWAY_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1ha2Utc2VydmVyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MjAwMDAwMDAwMH0.placeholder"
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val gatewayKey: String = config.gatewayKey ?: DEFAULT_GATEWAY_KEY

    @Volatile
    var sessionToken: String? = null

    @Volatile
    var nonce: String? = null

    /**
     * Layer 1: Supabase Gateway Auth - applied to ALL requests.
     * Both Authorization and apikey headers are required by Supabase Edge Functions.
     */
    private val gatewayInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

        // Supabase gateway headers (Layer 1)
        builder.addHeader("Authorization", "Bearer $gatewayKey")
        builder.addHeader("apikey", gatewayKey)

        chain.proceed(builder.build())
    }

    /**
     * Layer 2: Endpoint-specific auth headers + environment + nonce dual-delivery.
     */
    private val sessionInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

        // Session token (for upload, complete, status endpoints)
        sessionToken?.let { builder.addHeader("X-Session-Token", it) }

        // Nonce dual-delivery (v1.17.5+): send in BOTH header AND query param
        // to survive proxy/CDN header stripping
        val currentNonce = nonce
        if (currentNonce != null) {
            builder.addHeader("X-Nonce", currentNonce)
            val urlWithNonce = original.url.newBuilder()
                .addQueryParameter("nonce", currentNonce)
                .build()
            builder.url(urlWithNonce)
        }

        // Environment query parameter
        val env = if (config.environment == UseSenseEnvironment.AUTO) {
            UseSenseEnvironment.fromApiKey(config.apiKey)
        } else {
            config.environment
        }
        val envValue = when (env) {
            UseSenseEnvironment.SANDBOX -> "sandbox"
            else -> "production"
        }

        // Add env as query parameter
        val currentUrl = builder.build().url
        val urlWithEnv = currentUrl.newBuilder()
            .addQueryParameter("env", envValue)
            .build()
        builder.url(urlWithEnv)

        builder.addHeader("User-Agent", "UseSense-Android-SDK/1.17.7")

        chain.proceed(builder.build())
    }

    /**
     * Idempotency key interceptor for upload and complete endpoints.
     */
    private val idempotencyInterceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath

        // Add idempotency key for upload and complete endpoints
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

    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        var response: okhttp3.Response? = null
        var exception: IOException? = null
        val delays = longArrayOf(0, 1000, 3000)

        for (attempt in delays.indices) {
            try {
                if (attempt > 0) {
                    Thread.sleep(delays[attempt])
                }
                response?.close()
                response = chain.proceed(request)
                if (response.isSuccessful || response.code != 500) {
                    return@Interceptor response
                }
            } catch (e: IOException) {
                exception = e
                response?.close()
            }
        }
        response ?: throw (exception ?: IOException("Request failed after retries"))
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(gatewayInterceptor)
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

    fun clearSession() {
        sessionToken = null
        nonce = null
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

    /**
     * Map HTTP status + server error codes to user-friendly messages per spec Section 11.8.
     */
    private fun getUserMessage(httpStatus: Int, serverCode: String?): String {
        return when (httpStatus) {
            400 -> "Invalid request. Please check the parameters."
            401 -> when (serverCode) {
                "session_expired" -> "Your session has expired. Please start over."
                "invalid_token" -> "Session token is invalid."
                else -> "Authentication failed. Check API key."
            }
            404 -> when (serverCode) {
                "identity_not_found" -> "Identity not found."
                else -> "Endpoint not found. Verify Backend URL."
            }
            429 -> "Rate limit reached. Try again later."
            500 -> "Server error. Please try again."
            503 -> "Service unavailable. Try again later."
            else -> "HTTP $httpStatus"
        }
    }
}

internal class ApiException(val useSenseError: UseSenseError) : Exception(useSenseError.message)
