package com.usesense.sdk.devicecheck

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.usesense.sdk.flows.DeviceTrustSignals
import com.usesense.sdk.integrity.HardwareKeyManager
import com.usesense.sdk.internal.Bounded
import com.usesense.sdk.internal.boundedCall
import com.usesense.sdk.signals.DeviceSignalCollector
import com.usesense.sdk.signals.PlayIntegrityNonceBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.util.UUID

/**
 * The camera-free DeepSense signals, from the same collector the face and
 * Device Trust paths use (DeviceSignalCollector). Nothing here opens the
 * camera, starts the microphone or sensors, shows UI or asks for a permission:
 *  - D1 attestation: a Play Integrity token bound to the check's nonce
 *    (Standard requestHash, then Classic nonce; PlayIntegrityNonceBinding);
 *  - D2 environment: emulator, root, debuggable, Play services, security
 *    patch, and a hardware keystore probe (an AndroidKeyStore key, no prompt);
 *  - D3 hardware and D5 session context: the collector's device, screen,
 *    GPU, battery, locale, network type and uptime fields, plus telemetry;
 *  - install_id: a random per-install id, kept in the app's private prefs.
 * The finish phase reads CameraManager's id list and characteristics, which
 * needs no permission and never opens a camera.
 */
internal class AndroidDeviceCheckSignals(
    private val context: Context,
    private val cloudProjectNumber: Long,
) : DeviceCheckSignalSource {
    override suspend fun start(
        scope: CoroutineScope,
        nonce: String,
    ): CollectedSignals {
        val collector = DeviceSignalCollector(context, cloudProjectNumber)
        try {
            val integrity =
                runCatching { collector.requestNonceBoundPlayIntegrityToken(scope, nonce, STANDARD_WAIT_MS) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            val tier = keystoreTier(scope)
            val telemetry = runCatching { collector.collectDeviceTelemetry() }.getOrNull()
            val collected = collector.collectSignals()
            val installId = runCatching { installId(context) }.getOrNull()
            val ci = DeviceCheckProtocol.startChannelIntegrity(collected, telemetry, installId, integrity?.api, tier)
            val pi =
                JSONObject()
                    .put("binding", PlayIntegrityNonceBinding.BINDING)
                    .put("token", integrity?.hasToken == true)
                    .also { o ->
                        integrity?.api?.let { o.put("api", it) }
                        integrity?.errorName?.let { o.put("error", it) }
                    }
            val client = DeviceCheckProtocol.client(DeviceSignalCollector.SDK_VERSION, pi, DeviceTrustSignals.deviceBinding(collected))
            Log.d(TAG, "start signals collected (integrity=${integrity?.api ?: integrity?.errorName ?: "none"}, keystore=${tier ?: "unknown"})")
            return CollectedSignals(ci, client)
        } finally {
            collector.release()
        }
    }

    override suspend fun finish(): CollectedSignals {
        val cameras = runCatching { cameraEntries() }.getOrNull()
        Log.d(TAG, "finish inventory: ${cameras?.size ?: "unavailable"} camera(s)")
        return CollectedSignals(DeviceCheckProtocol.cameraInventory(cameras), DeviceCheckProtocol.client(DeviceSignalCollector.SDK_VERSION))
    }

    /**
     * The keystore's security level: generating an EC key in AndroidKeyStore
     * needs no permission and shows nothing. The key is reused across checks.
     * Bounded, since keystore calls are binder IPC that cannot be cancelled.
     */
    private suspend fun keystoreTier(scope: CoroutineScope): String? {
        val probe =
            boundedCall(scope, KEYSTORE_TIMEOUT_MS) {
                runCatching {
                    HardwareKeyManager(context, KEYSTORE_PROBE_ID).getOrGenerateSigningKey()
                    // A fresh manager reads the real level from KeyInfo, not the generator's guess.
                    HardwareKeyManager(context, KEYSTORE_PROBE_ID).attestationTier()
                }.getOrNull()
            }
        return when (probe) {
            is Bounded.Done -> probe.value
            is Bounded.TimedOut -> null
        }
    }

    private fun cameraEntries(): List<DeviceCheckProtocol.CameraEntry> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return manager.cameraIdList.map { id ->
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            val facing =
                when (ch?.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
            val level =
                when (ch?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
                    else -> "unknown"
                }
            val caps = ch?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val logical = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            DeviceCheckProtocol.CameraEntry(id, facing, level, logical)
        }
    }

    companion object {
        private const val TAG = "UseSense.DeviceCheck"

        /** Standard warm-up plus request; Classic gets PlayIntegrityManager.TOKEN_TIMEOUT_MS after it. */
        const val STANDARD_WAIT_MS = 6_000L
        const val KEYSTORE_TIMEOUT_MS = 3_000L
        private const val KEYSTORE_PROBE_ID = "device_check_probe"
        private const val PREFS = "com.usesense.sdk.device"
        private const val INSTALL_ID_KEY = "install_id"

        /**
         * A random id for this install of the host app (gone on uninstall or
         * "clear data"), so the server can tell one device from another phone
         * of the same model. Not derived from any hardware identifier.
         */
        @Synchronized
        fun installId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(INSTALL_ID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(INSTALL_ID_KEY, id).apply()
            return id
        }
    }
}
