package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Request body for POST /v1/sessions/exchange-token.
 * Used in the server-side init flow where the integrator's backend
 * creates a client_token and the SDK exchanges it for full session credentials.
 */
@JsonClass(generateAdapter = true)
data class ExchangeTokenRequest(
    @Json(name = "client_token") val clientToken: String,
)

/**
 * Response from POST /v1/sessions/create-token (called by integrator backend).
 * The SDK does not call this endpoint directly, but the model is provided
 * for completeness and potential server-side SDK usage.
 */
@JsonClass(generateAdapter = true)
data class CreateTokenResponse(
    @Json(name = "client_token") val clientToken: String,
    @Json(name = "expires_at") val expiresAt: String,
)
