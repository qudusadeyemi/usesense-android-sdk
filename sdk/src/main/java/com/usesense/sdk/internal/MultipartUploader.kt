package com.usesense.sdk.internal

import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.UploadSignalsResponse
import java.util.UUID

internal class MultipartUploader(private val apiClient: UseSenseApiClient) {

    suspend fun upload(
        sessionId: String,
        frames: List<ByteArray>,
        metadataJson: ByteArray,
        audioData: ByteArray? = null,
    ): Result<UploadSignalsResponse> {
        val idempotencyKey = UUID.randomUUID().toString()
        return apiClient.uploadSignals(
            sessionId = sessionId,
            frames = frames,
            metadataJson = metadataJson,
            audioData = audioData,
            idempotencyKey = idempotencyKey,
        )
    }
}
