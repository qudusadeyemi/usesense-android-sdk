package com.usesense.sdk.devicecheck

import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.flows.DeviceTrustSignals
import com.usesense.sdk.signals.PlayIntegrityNonceBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire contract of the silent device check, kept free of Android types so
 * it is covered by plain JVM unit tests.
 *
 * Server contract (usesense-watchtower):
 *   modules/device-checks/endpoints.ts and silent-check.ts
 *   POST {base}/v1/sdk/device-checks/:id/signals, Authorization: Bearer dct_...
 *   body { nonce, phase: "start" | "finish", channel_integrity, client }
 *   202 { received: true }, never a score.
 */
internal object DeviceCheckProtocol {
    const val CLIENT_MODE = "silent_device_check"

    /** One retry, after this long, for a 5xx / 429 / dropped connection. */
    const val RETRY_BACKOFF_MS = 1_000L

    /** A server Retry-After longer than this is clamped. */
    const val MAX_RETRY_AFTER_MS = 5_000L

    private val ID_RE = Regex("^dc_[A-Za-z0-9]{8,64}$")

    /** The signals URL for [deviceCheckId] under [baseUrl] (a trailing `/v1` is stripped). */
    fun signalsUrl(
        baseUrl: String,
        deviceCheckId: String,
    ): String = UseSenseApiClient.normalizeBaseUrl(baseUrl) + "v1/sdk/device-checks/$deviceCheckId/signals"

    /** Why [config] cannot be sent, or null when it can. Checked before anything is collected. */
    fun validate(config: DeviceCheckConfig): DeviceCheckError? {
        val problem =
            when {
                !ID_RE.matches(config.deviceCheckId.trim()) -> "deviceCheckId must be the dc_ id from POST /v1/device-checks"
                !config.token.trim().startsWith("dct_") -> "token must be the dct_ token from POST /v1/device-checks"
                !config.nonce.trim().startsWith("dn_") || config.nonce.trim().length <= 3 -> "nonce must be the dn_ nonce from POST /v1/device-checks"
                config.timeoutMs <= 0 -> "timeoutMs must be positive"
                else -> null
            }
        return problem?.let { DeviceCheckError(DeviceCheckError.Code.INVALID_CONFIG, it) }
    }

    /** `client`: for the server's diagnostics only, never scored. */
    fun client(
        sdkVersion: String,
        playIntegrity: JSONObject? = null,
        deviceBinding: JSONObject? = null,
    ): JSONObject =
        JSONObject()
            .put("sdk", "android")
            .put("version", sdkVersion)
            .put("mode", CLIENT_MODE)
            .also { c ->
                playIntegrity?.let { c.put("play_integrity", it) }
                deviceBinding?.let { c.put("device_binding", it) }
            }

    /**
     * The start phase's channel_integrity: exactly the camera-free blob a
     * Device Trust step sends (DeviceTrustSignals.build: the collector's signals
     * without the capture-only keys, plus the device telemetry), with
     *  - `play_integrity_nonce_binding: "sha256"` (the token's requestHash or
     *    nonce is SHA-256 of the check's nonce; declared even without a token
     *    so the server knows this build binds),
     *  - `play_integrity_api` (standard | classic) when a token was obtained,
     *  - `install_id`, the per-install id the server counts people on a device by,
     *  - `has_hardware_keystore` and `hardware_keystore_tier` when the probe ran.
     */
    fun startChannelIntegrity(
        collected: JSONObject,
        telemetry: JSONObject?,
        installId: String?,
        playIntegrityApi: String?,
        keystoreTier: String?,
    ): JSONObject {
        val ci = DeviceTrustSignals.build(collected, telemetry)
        ci.put("play_integrity_nonce_binding", PlayIntegrityNonceBinding.BINDING)
        if (playIntegrityApi != null && ci.has("play_integrity_token")) ci.put("play_integrity_api", playIntegrityApi)
        if (!installId.isNullOrBlank()) ci.put("install_id", installId)
        if (keystoreTier != null) {
            ci.put("has_hardware_keystore", keystoreTier == "tee" || keystoreTier == "strongbox")
            ci.put("hardware_keystore_tier", keystoreTier)
        }
        return ci
    }

    /** One camera as CameraManager describes it, without opening it. */
    data class CameraEntry(
        val id: String,
        /** front | back | external | unknown */
        val facing: String,
        /** legacy | limited | full | level_3 | external | unknown */
        val hardwareLevel: String,
        val logicalMultiCamera: Boolean,
    )

    /**
     * The finish phase's `camera_inventory`. Android camera ids carry no
     * driver name, so there are no `labels` to match against the server's
     * virtual camera driver list: sending made-up labels would tell the server
     * "inventory read, no virtual camera", which this SDK cannot know. The
     * inventory is sent as reported, with `external_count` (cameras attached
     * over USB or a virtual HAL report the EXTERNAL hardware level).
     */
    fun cameraInventory(cameras: List<CameraEntry>?): JSONObject {
        val inv = JSONObject().put("source", "android_camera2")
        if (cameras == null) return inv.put("available", false)
        val list = JSONArray()
        cameras.forEach { c ->
            list.put(
                JSONObject()
                    .put("id", c.id)
                    .put("facing", c.facing)
                    .put("hardware_level", c.hardwareLevel)
                    .put("logical_multi_camera", c.logicalMultiCamera),
            )
        }
        return inv
            .put("available", true)
            .put("video_input_count", cameras.size)
            .put("external_count", cameras.count { it.facing == "external" || it.hardwareLevel == "external" })
            .put("cameras", list)
    }

    fun startBody(
        nonce: String,
        channelIntegrity: JSONObject,
        client: JSONObject,
    ): JSONObject = body(nonce, DeviceCheckPhase.START, channelIntegrity, client)

    fun finishBody(
        nonce: String,
        cameraInventory: JSONObject,
        client: JSONObject,
    ): JSONObject = body(nonce, DeviceCheckPhase.FINISH, JSONObject().put("camera_inventory", cameraInventory), client)

    private fun body(
        nonce: String,
        phase: DeviceCheckPhase,
        channelIntegrity: JSONObject,
        client: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("nonce", nonce)
            .put("phase", phase.wire)
            .put("channel_integrity", channelIntegrity)
            .put("client", client)

    /** Map a non-2xx answer to the SDK's error taxonomy. */
    fun errorFor(
        status: Int,
        serverCode: String?,
        serverMessage: String?,
    ): DeviceCheckError {
        val code =
            when {
                status == 401 && serverCode == "token_expired" -> DeviceCheckError.Code.TOKEN_EXPIRED
                status == 401 || status == 403 -> DeviceCheckError.Code.INVALID_TOKEN
                serverCode == "nonce_mismatch" -> DeviceCheckError.Code.NONCE_MISMATCH
                status == 404 -> DeviceCheckError.Code.NOT_FOUND
                status == 409 -> DeviceCheckError.Code.OUT_OF_ORDER
                status == 429 || status >= 500 -> DeviceCheckError.Code.SERVICE_UNAVAILABLE
                status in 400..499 -> DeviceCheckError.Code.INVALID_REQUEST
                else -> DeviceCheckError.Code.UNKNOWN
            }
        val message = serverMessage?.takeIf { it.isNotBlank() } ?: "Device check signals were refused (HTTP $status)"
        return DeviceCheckError(code, message, status, serverCode)
    }

    /** Statuses worth the one retry: the server says so (503 scoring_failed) or a gateway hiccup. */
    fun isRetryableStatus(status: Int): Boolean = status == 429 || status == 502 || status == 503 || status == 504

    /**
     * After a retry that followed a dropped connection, `409 already_started`
     * means the first start did arrive and was accepted (the server gives a
     * failed scoring its start back, so a claimed start was not a failure).
     */
    fun firstAttemptLanded(
        phase: DeviceCheckPhase,
        retriedAfterTransportFailure: Boolean,
        status: Int,
        serverCode: String?,
    ): Boolean =
        retriedAfterTransportFailure &&
            status == 409 &&
            (
                (phase == DeviceCheckPhase.START && serverCode == "already_started") ||
                    (phase == DeviceCheckPhase.FINISH && serverCode == "already_finished")
            )

    /** The wait before the retry: the server's Retry-After (seconds), clamped, else [RETRY_BACKOFF_MS]. */
    fun backoffMs(retryAfterHeader: String?): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull() ?: return RETRY_BACKOFF_MS
        return (seconds * 1000).coerceIn(0L, MAX_RETRY_AFTER_MS)
    }
}
