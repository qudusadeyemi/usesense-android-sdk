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

    private val sessionInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

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

        val env = if (config.environment == UseSenseEnvironment.AUTO) {
            UseSenseEnvironment.fromApiKey(config.apiKey)
        } else {
            config.environment
        }
        val envValue = when (env) {
            UseSenseEnvironment.SANDBOX -> "sandbox"
            else -> "production"
        }
        builder.addHeader("X-Environment", envValue)
        builder.addHeader("User-Agent", "UseSense-Android/1.0.0")

        chain.proceed(builder.build())
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
        .addInterceptor(sessionInterceptor)
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
                "audio.webm",
                it.toRequestBody("audio/webm".toMediaType()),
            )
        }

        // Add idempotency key via a custom request with header
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
                Result.failure(
                    ApiException(
                        UseSenseError.fromServerError(
                            response.code(),
                            parsed?.error?.code,
                            parsed?.error?.message ?: "HTTP ${response.code()}"
                        )
                    )
                )
            }
        } catch (e: IOException) {
            Result.failure(ApiException(UseSenseError.networkError(e.message)))
        } catch (e: Exception) {
            Result.failure(ApiException(UseSenseError.networkError(e.message)))
        }
    }
}

internal class ApiException(val useSenseError: UseSenseError) : Exception(useSenseError.message)
