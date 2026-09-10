package com.usesense.sdk.signals

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class MetadataBuilderMeshSkipTest {

    private fun build(
        verificationPackage: JSONObject? = null,
        skipReason: String? = null,
        initResult: String? = null,
        initError: String? = null,
    ): JSONObject {
        val bytes = MetadataBuilder().build(
            sessionId = "sess_test",
            challengeResponse = null,
            channelIntegrity = JSONObject(),
            deviceTelemetry = JSONObject(),
            captureStartTime = Date(0),
            captureEndTime = Date(1_000),
            captureConfig = CaptureConfigInfo(captureDurationMs = 2_000, targetFps = 10, maxFrames = 30),
            framesManifest = emptyList(),
            framesCaptured = 0,
            framesDropped = 0,
            avgFrameIntervalMs = 0,
            verificationPackage = verificationPackage,
            verificationPackageSkipReason = skipReason,
            faceMeshInitResult = initResult,
            faceMeshInitError = initError,
        )
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `an omitted package reports why, with the landmarker outcome`() {
        val metadata = build(
            skipReason = "landmarker_init_failed",
            initResult = "failed",
            initError = "IllegalStateException: model asset missing",
        )
        assertFalse(metadata.has("verification_package"))
        assertEquals("landmarker_init_failed", metadata.getString("verification_package_skip_reason"))
        assertEquals("failed", metadata.getString("face_mesh_init_result"))
        assertEquals("IllegalStateException: model asset missing", metadata.getString("face_mesh_init_error"))
    }

    @Test
    fun `a present package never carries a skip reason`() {
        val metadata = build(
            verificationPackage = JSONObject().put("frames", org.json.JSONArray()),
            skipReason = "no_face_detected",
            initResult = "success",
        )
        assertTrue(metadata.has("verification_package"))
        assertFalse(metadata.has("verification_package_skip_reason"))
        assertEquals("success", metadata.getString("face_mesh_init_result"))
        assertFalse(metadata.has("face_mesh_init_error"))
    }

    @Test
    fun `nothing is added when mesh was not in play`() {
        val metadata = build()
        assertFalse(metadata.has("verification_package_skip_reason"))
        assertFalse(metadata.has("face_mesh_init_result"))
        assertFalse(metadata.has("face_mesh_init_error"))
    }
}
