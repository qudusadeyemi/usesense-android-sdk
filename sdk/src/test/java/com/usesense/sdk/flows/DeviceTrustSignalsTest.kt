package com.usesense.sdk.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class DeviceTrustSignalsTest {
    @Test
    fun `a device step without a nonce is re-read once, then fails`() {
        assertEquals(DeviceTrustSignals.MissingNonce.RELOAD, DeviceTrustSignals.onMissingNonce(alreadyReloaded = false))
        assertEquals(DeviceTrustSignals.MissingNonce.FAIL, DeviceTrustSignals.onMissingNonce(alreadyReloaded = true))
    }

    @Test
    fun `stale nonce and settled step re-read the run`() {
        assertTrue(DeviceTrustSignals.needsReload("nonce_mismatch"))
        assertTrue(DeviceTrustSignals.needsReload("device_step_not_pending"))
        assertFalse(DeviceTrustSignals.needsReload("invalid_input"))
        assertFalse(DeviceTrustSignals.needsReload(null))
    }

    @Test
    fun `device binding picks only the fingerprint keys`() {
        val collected =
            JSONObject()
                .put("screen_resolution", "1080x2400")
                .put("platform", "android")
                .put("timezone", "Africa/Lagos")
                .put("hardware_concurrency", 8)
                .put("battery_level", 0.4)
                .put("is_rooted", false)
                .put("canvas_hash", JSONObject.NULL)
        val components = DeviceTrustSignals.deviceBinding(collected)!!.getJSONObject("components")
        assertEquals(setOf("screen_resolution", "platform", "timezone", "hardware_concurrency"), components.keys().asSequence().toSet())
        assertEquals(8, components.getInt("hardware_concurrency"))
    }

    @Test
    fun `no fingerprint signals means no binding`() {
        assertNull(DeviceTrustSignals.deviceBinding(JSONObject().put("battery_level", 1)))
    }

    @Test
    fun `fingerprint keys match the server`() {
        assertEquals(
            listOf(
                "canvas_hash", "webgl_renderer", "webgl_vendor", "webgl_extensions",
                "screen_resolution", "hardware_concurrency", "device_memory", "max_touch_points",
                "platform", "color_depth", "timezone", "audio_fingerprint",
            ),
            DeviceTrustSignals.FINGERPRINT_KEYS,
        )
    }
}
