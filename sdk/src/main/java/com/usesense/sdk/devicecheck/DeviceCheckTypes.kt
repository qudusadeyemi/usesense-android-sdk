package com.usesense.sdk.devicecheck

/**
 * What the app gets from its own server for one silent device check
 * (`POST /v1/device-checks`, created with `"platform": "android"`), passed
 * to [com.usesense.sdk.UseSense.deviceCheck].
 *
 * The API key never ships to the device: [token] is a one-use `dct_` bearer
 * token that works only for this check and expires 15 minutes after create.
 */
data class DeviceCheckConfig
    @JvmOverloads
    constructor(
        /** The `dc_` id from create. Pass it to your KYC provider's metadata too. */
        val deviceCheckId: String,
        /** The `dct_` token from create. Never logged by the SDK. */
        val token: String,
        /** The `dn_` nonce from create. The Play Integrity token is bound to it. */
        val nonce: String,
        /**
         * API root without a version segment. Null (default): the base URL of
         * `UseSense.initialize`, if called, else [com.usesense.sdk.UseSenseConfig.DEFAULT_BASE_URL].
         * A trailing `/v1` is accepted and stripped, as everywhere in the SDK.
         */
        val baseUrl: String? = null,
        /**
         * Google Cloud project number for Play Integrity. Null (default): the
         * value of `UseSense.initialize`, if called, else the SDK default.
         */
        val googleCloudProjectNumber: Long? = null,
        /** Ceiling on one [DeviceCheck.start] or [DeviceCheck.finish], signal collection and retry included. */
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        companion object {
            const val DEFAULT_TIMEOUT_MS = 45_000L
        }

        /** Never print the token. */
        override fun toString(): String = "DeviceCheckConfig(deviceCheckId=$deviceCheckId, baseUrl=$baseUrl, timeoutMs=$timeoutMs)"
    }

/** The two sends of a silent device check. */
enum class DeviceCheckPhase(val wire: String) {
    /** Before the provider's SDK opens: the full camera-free device signals. */
    START("start"),

    /** Optional, after the provider's SDK closes: the camera inventory. */
    FINISH("finish"),
}

/**
 * The outcome of one send. The server only confirms receipt (202); the device
 * never learns the score or verdict, which your server reads with
 * `GET /v1/device-checks/:id` or through the Your KYC result.
 */
sealed class DeviceCheckResult {
    abstract val phase: DeviceCheckPhase

    /** True for [Sent]. */
    val isSuccess: Boolean get() = this is Sent

    /** The server accepted the signals (202 `{ received: true }`). */
    data class Sent(override val phase: DeviceCheckPhase) : DeviceCheckResult()

    /** The signals were not accepted. Nothing was thrown into the host app. */
    data class Failed(override val phase: DeviceCheckPhase, val error: DeviceCheckError) : DeviceCheckResult()
}

/** Why a send failed. */
data class DeviceCheckError(
    val code: Code,
    val message: String,
    /** HTTP status, when the server answered. */
    val httpStatus: Int? = null,
    /** The server's `code` (e.g. `nonce_mismatch`, `already_started`), passed through verbatim. */
    val serverCode: String? = null,
) {
    enum class Code(val wire: String) {
        /** 401 `invalid_token`: missing or wrong `dct_` token. */
        INVALID_TOKEN("invalid_token"),

        /** 401 `token_expired`: more than 15 minutes after create. Create a new check. */
        TOKEN_EXPIRED("token_expired"),

        /** 400 `nonce_mismatch`: the nonce is not this check's. */
        NONCE_MISMATCH("nonce_mismatch"),

        /** 400 `invalid_phase` / `invalid_input`, 413 `too_large`, or another 4xx. */
        INVALID_REQUEST("invalid_request"),

        /** 404 `not_found`: unknown device check id. */
        NOT_FOUND("not_found"),

        /**
         * 409 `already_started` / `not_started` / `already_finished`: start once,
         * then finish once, in that order. Also a second local [DeviceCheck.start]
         * after one was accepted ([serverCode] null).
         */
        OUT_OF_ORDER("out_of_order"),

        /** 5xx (503 `scoring_failed` / `unavailable`) or 429, still failing after the one retry. */
        SERVICE_UNAVAILABLE("service_unavailable"),

        /** No connection, still failing after the one retry. */
        NETWORK_ERROR("network_error"),

        /** The send did not finish within [DeviceCheckConfig.timeoutMs]. */
        TIMEOUT("timeout"),

        /** The id, token or nonce is blank or malformed; nothing was sent. */
        INVALID_CONFIG("invalid_config"),

        /** [DeviceCheck.cancel] was called; nothing more is sent. */
        CANCELLED("cancelled"),
        UNKNOWN("unknown"),
    }

    /** True when trying the same phase again later may succeed. */
    val isRetryable: Boolean
        get() = code == Code.SERVICE_UNAVAILABLE || code == Code.NETWORK_ERROR || code == Code.TIMEOUT
}

/** Java-friendly result callback, always invoked on the main thread. */
fun interface DeviceCheckCallback {
    fun onResult(result: DeviceCheckResult)
}
