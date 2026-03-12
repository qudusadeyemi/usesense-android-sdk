package com.usesense.sdk.api

import com.usesense.sdk.api.models.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

internal interface UseSenseApiService {

    // ── Direct SDK Endpoints ──

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

    // ── Remote Enrollment Endpoints (Section 3.1) ──

    @GET("remote-enrollment/{id}/data")
    suspend fun getRemoteEnrollmentData(
        @Path("id") id: String,
    ): Response<RemoteEnrollmentDataWrapper>

    @POST("remote-enrollment/{id}/opened")
    suspend fun markEnrollmentOpened(
        @Path("id") id: String,
    ): Response<Unit>

    @POST("remote-enrollment/{id}/init-session")
    suspend fun initEnrollmentSession(
        @Path("id") id: String,
    ): Response<HostedInitSessionResponse>

    @POST("remote-enrollment/{id}/complete")
    suspend fun completeEnrollment(
        @Path("id") id: String,
    ): Response<HostedCompleteResponse>

    // ── Remote Session (Verification) Endpoints (Section 3.2) ──

    @GET("remote-session/{id}/data")
    suspend fun getRemoteSessionData(
        @Path("id") id: String,
    ): Response<RemoteSessionDataWrapper>

    @POST("remote-session/{id}/opened")
    suspend fun markSessionOpened(
        @Path("id") id: String,
    ): Response<Unit>

    @POST("remote-session/{id}/init-session")
    suspend fun initVerificationSession(
        @Path("id") id: String,
    ): Response<HostedInitSessionResponse>

    @POST("remote-session/{id}/complete")
    suspend fun completeRemoteSession(
        @Path("id") id: String,
    ): Response<HostedCompleteResponse>

    @POST("remote-session/{id}/dispute")
    suspend fun disputeSession(
        @Path("id") id: String,
    ): Response<DisputeResponse>
}
