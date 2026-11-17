package com.usesense.sdk.devicecheck

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload building, validation and error mapping for the silent device check. */
class DeviceCheckProtocolTest {
    private val id = "dc_6f1c2a9b4e"
    private val token = "dct_" + "a".repeat(48)
    private val nonce = "dn_2b7d9f00aa11"

    private fun collected(withToken: Boolean = true) =
        JSONObject()
            .put("platform", "android")
            .put("is_emulator", false)
            .put("is_rooted", false)
            .put("is_debuggable", false)
            .put("has_play_services", true)
            .put("device_model", "SM-A035F")
            .put("screen_resolution", "720x1600")
            .put("camera_facing", "front")
            .put("camera_resolution", "640x480")
            .put("camera_permission_granted", false)
            .put("microphone_permission_granted", false)
            .put("accelerometer_data", org.json.JSONArray())
            .put("gyroscope_data", org.json.JSONArray())
            .also { if (withToken) it.put("play_integrity_token", "eyJ.token") }

    private val telemetry = JSONObject().put("security_patch", "2026-10-01").put("cpu_abi", "arm64-v8a")

    // ── URL and validation ──

    @Test
    fun `signals URL has a single v1 whatever the base`() {
        val want = "https://api.usesense.ai/v1/sdk/device-checks/$id/signals"
        assertEquals(want, DeviceCheckProtocol.signalsUrl("https://api.usesense.ai", id))
        assertEquals(want, DeviceCheckProtocol.signalsUrl("https://api.usesense.ai/", id))
        assertEquals(want, DeviceCheckProtocol.signalsUrl("https://api.usesense.ai/v1", id))
    }

    @Test
    fun `a well formed config validates`() {
        assertNull(DeviceCheckProtocol.validate(DeviceCheckConfig(id, token, nonce)))
    }

    @Test
    fun `malformed id, token or nonce is refused before anything is sent`() {
        val bad =
            listOf(
                DeviceCheckConfig("", token, nonce),
                DeviceCheckConfig("dc_../../x", token, nonce),
                DeviceCheckConfig("sess_123456789", token, nonce),
                DeviceCheckConfig(id, "sk_live_key", nonce),
                DeviceCheckConfig(id, token, ""),
                DeviceCheckConfig(id, token, "dn_"),
                DeviceCheckConfig(id, token, nonce, timeoutMs = 0),
            )
        bad.forEach { c ->
            assertEquals(c.toString(), DeviceCheckError.Code.INVALID_CONFIG, DeviceCheckProtocol.validate(c)?.code)
        }
    }

    @Test
    fun `config toString never prints the token`() {
        assertFalse(DeviceCheckConfig(id, token, nonce).toString().contains(token))
    }

    // ── Start payload ──

    @Test
    fun `start body carries nonce, phase and the camera-free channel_integrity`() {
        val ci = DeviceCheckProtocol.startChannelIntegrity(collected(), telemetry, "inst-1", "standard", "strongbox")
        val body = DeviceCheckProtocol.startBody(nonce, ci, DeviceCheckProtocol.client("4.15.0"))

        assertEquals(nonce, body.getString("nonce"))
        assertEquals("start", body.getString("phase"))
        val sent = body.getJSONObject("channel_integrity")
        // Same key names the server scores on.
        assertEquals("eyJ.token", sent.getString("play_integrity_token"))
        assertEquals(false, sent.getBoolean("is_emulator"))
        assertEquals(false, sent.getBoolean("is_rooted"))
        assertEquals(false, sent.getBoolean("is_debuggable"))
        assertEquals(true, sent.getBoolean("has_play_services"))
        assertEquals("2026-10-01", sent.getString("security_patch"))
        assertEquals("arm64-v8a", sent.getString("cpu_abi"))
        assertEquals("inst-1", sent.getString("install_id"))
        assertEquals("sha256", sent.getString("play_integrity_nonce_binding"))
        assertEquals("standard", sent.getString("play_integrity_api"))
        assertEquals(true, sent.getBoolean("has_hardware_keystore"))
        assertEquals("strongbox", sent.getString("hardware_keystore_tier"))
    }

    @Test
    fun `start never describes a camera or microphone capture`() {
        val ci = DeviceCheckProtocol.startChannelIntegrity(collected(), telemetry, "inst-1", "standard", "tee")
        listOf(
            "camera_facing", "camera_resolution", "camera_permission_granted",
            "microphone_permission_granted", "accelerometer_data", "gyroscope_data",
        ).forEach { assertFalse("$it must not be sent", ci.has(it)) }
    }

    @Test
    fun `the binding is declared even without a token, the API only with one`() {
        val ci = DeviceCheckProtocol.startChannelIntegrity(collected(withToken = false), null, null, "standard", null)
        assertEquals("sha256", ci.getString("play_integrity_nonce_binding"))
        assertFalse(ci.has("play_integrity_token"))
        assertFalse(ci.has("play_integrity_api"))
        assertFalse(ci.has("install_id"))
        assertFalse(ci.has("has_hardware_keystore"))
    }

    @Test
    fun `a software keystore reports no hardware keystore`() {
        val ci = DeviceCheckProtocol.startChannelIntegrity(collected(), null, "i", "classic", "software")
        assertEquals(false, ci.getBoolean("has_hardware_keystore"))
        assertEquals("classic", ci.getString("play_integrity_api"))
    }

    @Test
    fun `client identifies the SDK and the mode`() {
        val c = DeviceCheckProtocol.client("4.15.0", JSONObject().put("api", "standard"), JSONObject().put("components", JSONObject()))
        assertEquals("android", c.getString("sdk"))
        assertEquals("4.15.0", c.getString("version"))
        assertEquals("silent_device_check", c.getString("mode"))
        assertEquals("standard", c.getJSONObject("play_integrity").getString("api"))
        assertTrue(c.has("device_binding"))
        assertFalse(DeviceCheckProtocol.client("4.15.0").has("play_integrity"))
    }

    // ── Finish payload ──

    @Test
    fun `finish body carries the camera inventory without made-up labels`() {
        val inv =
            DeviceCheckProtocol.cameraInventory(
                listOf(
                    DeviceCheckProtocol.CameraEntry("0", "back", "full", true),
                    DeviceCheckProtocol.CameraEntry("1", "front", "limited", false),
                    DeviceCheckProtocol.CameraEntry("5", "external", "external", false),
                ),
            )
        val body = DeviceCheckProtocol.finishBody(nonce, inv, DeviceCheckProtocol.client("4.15.0"))
        assertEquals("finish", body.getString("phase"))
        assertEquals(nonce, body.getString("nonce"))
        val sent = body.getJSONObject("channel_integrity").getJSONObject("camera_inventory")
        assertEquals("android_camera2", sent.getString("source"))
        assertEquals(3, sent.getInt("video_input_count"))
        assertEquals(1, sent.getInt("external_count"))
        assertEquals("front", sent.getJSONArray("cameras").getJSONObject(1).getString("facing"))
        assertTrue(sent.getJSONArray("cameras").getJSONObject(0).getBoolean("logical_multi_camera"))
        // Android ids carry no driver name: no labels, so the server cannot read
        // "no virtual camera" from an inventory this SDK cannot vouch for.
        assertFalse(sent.has("labels"))
    }

    @Test
    fun `unreadable inventory says so`() {
        val inv = DeviceCheckProtocol.cameraInventory(null)
        assertEquals(false, inv.getBoolean("available"))
        assertFalse(inv.has("video_input_count"))
    }

    // ── Error mapping ──

    private fun code(
        status: Int,
        server: String?,
    ) = DeviceCheckProtocol.errorFor(status, server, "msg").code

    @Test
    fun `server refusals map to the SDK taxonomy`() {
        assertEquals(DeviceCheckError.Code.INVALID_TOKEN, code(401, "invalid_token"))
        assertEquals(DeviceCheckError.Code.TOKEN_EXPIRED, code(401, "token_expired"))
        assertEquals(DeviceCheckError.Code.NONCE_MISMATCH, code(400, "nonce_mismatch"))
        assertEquals(DeviceCheckError.Code.INVALID_REQUEST, code(400, "invalid_phase"))
        assertEquals(DeviceCheckError.Code.INVALID_REQUEST, code(400, "invalid_input"))
        assertEquals(DeviceCheckError.Code.INVALID_REQUEST, code(413, "too_large"))
        assertEquals(DeviceCheckError.Code.NOT_FOUND, code(404, "not_found"))
        assertEquals(DeviceCheckError.Code.OUT_OF_ORDER, code(409, "already_started"))
        assertEquals(DeviceCheckError.Code.OUT_OF_ORDER, code(409, "not_started"))
        assertEquals(DeviceCheckError.Code.OUT_OF_ORDER, code(409, "already_finished"))
        assertEquals(DeviceCheckError.Code.SERVICE_UNAVAILABLE, code(503, "scoring_failed"))
        assertEquals(DeviceCheckError.Code.SERVICE_UNAVAILABLE, code(503, "unavailable"))
        assertEquals(DeviceCheckError.Code.SERVICE_UNAVAILABLE, code(429, null))
        assertEquals(DeviceCheckError.Code.UNKNOWN, code(302, null))
    }

    @Test
    fun `the server code and message pass through`() {
        val e = DeviceCheckProtocol.errorFor(400, "nonce_mismatch", "Nonce does not match this device check")
        assertEquals(400, e.httpStatus)
        assertEquals("nonce_mismatch", e.serverCode)
        assertEquals("Nonce does not match this device check", e.message)
        assertFalse(e.isRetryable)
        assertNotNull(DeviceCheckProtocol.errorFor(500, null, null).message)
        assertTrue(DeviceCheckProtocol.errorFor(503, "scoring_failed", null).isRetryable)
    }

    @Test
    fun `only 5xx gateway faults and 429 are retried`() {
        listOf(429, 502, 503, 504).forEach { assertTrue("$it", DeviceCheckProtocol.isRetryableStatus(it)) }
        listOf(400, 401, 404, 409, 413, 500).forEach { assertFalse("$it", DeviceCheckProtocol.isRetryableStatus(it)) }
    }

    @Test
    fun `a 409 after a dropped connection means the first send landed`() {
        assertTrue(DeviceCheckProtocol.firstAttemptLanded(DeviceCheckPhase.START, true, 409, "already_started"))
        assertTrue(DeviceCheckProtocol.firstAttemptLanded(DeviceCheckPhase.FINISH, true, 409, "already_finished"))
        assertFalse(DeviceCheckProtocol.firstAttemptLanded(DeviceCheckPhase.START, false, 409, "already_started"))
        assertFalse(DeviceCheckProtocol.firstAttemptLanded(DeviceCheckPhase.FINISH, true, 409, "not_started"))
        assertFalse(DeviceCheckProtocol.firstAttemptLanded(DeviceCheckPhase.START, true, 409, "already_finished"))
    }

    @Test
    fun `backoff honours a clamped Retry-After`() {
        assertEquals(DeviceCheckProtocol.RETRY_BACKOFF_MS, DeviceCheckProtocol.backoffMs(null))
        assertEquals(DeviceCheckProtocol.RETRY_BACKOFF_MS, DeviceCheckProtocol.backoffMs("soon"))
        assertEquals(2_000L, DeviceCheckProtocol.backoffMs("2"))
        assertEquals(DeviceCheckProtocol.MAX_RETRY_AFTER_MS, DeviceCheckProtocol.backoffMs("120"))
    }
}
