package com.usesense.sdk.flows

import org.json.JSONObject

/**
 * Camera-free Device Trust.
 *
 * The runner declares [CAPABILITY] on every load and advance, so a Device
 * Trust step arrives as a `device` capture: the SDK collects the same device
 * signals it sends with a face capture, minus everything that needs the camera
 * or microphone, and posts them with the step's nonce. Without the capability
 * the server settles the step from the network alone, which is what older SDKs
 * get. Server contract: docs/sdk/device-trust-protocol.md in usesense-watchtower.
 */
object DeviceTrustSignals {
    const val CAPABILITY = "device_signals_v1"

    /** Capabilities the flow runner declares to the SDK Runner endpoints. */
    val RUNNER_CAPABILITIES: List<String> = listOf(CAPABILITY)

    /**
     * Keys that describe a camera or microphone capture. Nothing was captured,
     * and the server leaves Sensor & Capture out of the score, so sending them
     * (camera_facing defaults to "front", sensor arrays are empty) would only
     * describe a capture that never happened.
     */
    internal val CAPTURE_ONLY_KEYS =
        setOf(
            "camera_facing",
            "camera_resolution",
            "camera_permission_granted",
            "microphone_permission_granted",
            "accelerometer_data",
            "gyroscope_data",
        )

    /**
     * The channel_integrity a device step sends: the collector's signals with
     * the capture-only keys removed, plus the device telemetry (cpu_abi, RAM,
     * security patch) a face upload carries alongside as device_telemetry.
     * Collector values win over telemetry on a key clash.
     */
    fun build(
        collected: JSONObject,
        telemetry: JSONObject?,
    ): JSONObject {
        val out = JSONObject()
        telemetry?.keys()?.forEach { k -> out.put(k, telemetry.get(k)) }
        collected.keys().forEach { k -> if (k !in CAPTURE_ONLY_KEYS) out.put(k, collected.get(k)) }
        return out
    }

    /**
     * Server codes after which the run should be re-read instead of failing:
     * the nonce moved on, or the step was already settled (a retry).
     */
    fun needsReload(serverCode: String?): Boolean = serverCode == "nonce_mismatch" || serverCode == "device_step_not_pending"

    /** What the runner does with a device step that arrived without a nonce. */
    enum class MissingNonce { RELOAD, FAIL }

    /**
     * The server mints the nonce when the client declares the capability, so a
     * device step without one is re-read once to pick it up. If it is still
     * missing the run fails with a clear error rather than spinning forever.
     * Same rule as the iOS SDK.
     */
    fun onMissingNonce(alreadyReloaded: Boolean): MissingNonce = if (alreadyReloaded) MissingNonce.FAIL else MissingNonce.RELOAD

    /** Error message when a device step still has no nonce after one re-read. */
    const val MISSING_NONCE_MESSAGE = "Device Trust step is missing its nonce"
}
