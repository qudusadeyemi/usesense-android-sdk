package com.usesense.sdk.api

import com.usesense.sdk.api.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

internal interface UseSenseApiService {

    @POST("v1/sessions")
    suspend fun createSession(
        @Header("x-api-key") apiKey: String,
        @Body request: CreateSessionRequest,
    ): Response<CreateSessionResponse>

    @Multipart
    @POST("v1/sessions/{sessionId}/signals")
    suspend fun uploadSignals(
        @Path("sessionId") sessionId: String,
        @Part frames: List<MultipartBody.Part>,
        @Part metadata: MultipartBody.Part,
        @Part audio: MultipartBody.Part? = null,
    ): Response<UploadSignalsResponse>

    @POST("v1/sessions/{sessionId}/complete")
    suspend fun completeSession(
        @Path("sessionId") sessionId: String,
    ): Response<VerdictResponse>

    @GET("v1/sessions/{sessionId}/status")
    suspend fun getSessionStatus(
        @Path("sessionId") sessionId: String,
    ): Response<SessionStatusResponse>
}
