package com.usesense.sdk.capture.scan

import com.usesense.sdk.signals.CaptureConfigInfo
import com.usesense.sdk.signals.MetadataBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** metadata.covered_check must match capture-test-kit/COVER-v1.md section 3 field for field. */
class CoveredCheckStatsTest {

    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()

    private val cover = FaceCover(
        bodySkin = 0.004f, other = 0.0101f,
        background = 0.0f, hair = 0.031f, faceSkin = 0.9559f, clothes = 0.0f, others = 0.0101f,
    )

    private fun filled() = CoveredCheckStats().apply {
        modelLoaded(410)
        run(930); run(1210); run(880)
        alignCovered(240); alignCovered(250)
        scanStarted(cover, 640)
        scanFrame(cover)
        scanFrame(cover.copy(bodySkin = 0.02f, other = 0.04f))
        changeWatchFrame(0.06f, 0.011f, 1.3f)
        changeWatchSingleFrameFlags(3)
        changeWatchTrip("mouth")
        changeWatchTrip("chin")
        scanDropForCover()
        finalCheck(cover, 380, "clear")
    }

    @Test fun `field names match the spec`() {
        val j = filled().toJson()
        assertEquals(
            setOf(
                "version", "model_sha256_prefix", "model_load_ms", "model_error", "limits",
                "runs", "run_ms_p50", "run_ms_max", "at_scan_start", "scan_max", "final",
                "align_covered_ms", "scan_drops", "change_watch",
            ),
            keys(j),
        )
        assertEquals(setOf("body_skin", "other", "shrink"), keys(j.getJSONObject("limits")))
        val split = setOf("bg", "hair", "body_skin", "face_skin", "clothes", "others")
        assertEquals(split + "age_ms", keys(j.getJSONObject("at_scan_start")))
        assertEquals(setOf("body_skin", "other"), keys(j.getJSONObject("scan_max")))
        assertEquals(split + setOf("waited_ms", "outcome"), keys(j.getJSONObject("final")))
        val cw = j.getJSONObject("change_watch")
        assertEquals(setOf("trips", "single_frame_flags", "rebaselines", "baseline_wait_ms", "max", "trip_regions"), keys(cw))
        assertEquals(setOf("luma_ratio_delta", "chroma_delta", "edge_factor"), keys(cw.getJSONObject("max")))
    }

    @Test fun `values are as recorded, fractions to 3 decimals`() {
        val j = filled().toJson()
        assertEquals("cover-v1", j.getString("version"))
        assertEquals("c6748b12", j.getString("model_sha256_prefix"))
        assertEquals(410, j.getInt("model_load_ms"))
        assertTrue(j.isNull("model_error"))
        assertEquals(0.12, j.getJSONObject("limits").getDouble("body_skin"), 1e-6)
        assertEquals(0.25, j.getJSONObject("limits").getDouble("other"), 1e-6)
        assertEquals(0.85, j.getJSONObject("limits").getDouble("shrink"), 1e-6)
        // Written exactly, not as 0.11999999731779099.
        assertTrue(j.getJSONObject("limits").toString().contains("\"body_skin\":0.12,"))
        assertEquals(3, j.getInt("runs"))
        assertEquals(930, j.getInt("run_ms_p50"))
        assertEquals(1210, j.getInt("run_ms_max"))
        assertEquals(0.956, j.getJSONObject("at_scan_start").getDouble("face_skin"), 1e-9)
        assertEquals(640, j.getJSONObject("at_scan_start").getInt("age_ms"))
        assertEquals(0.02, j.getJSONObject("scan_max").getDouble("body_skin"), 1e-9)
        assertEquals(0.04, j.getJSONObject("scan_max").getDouble("other"), 1e-9)
        assertEquals("clear", j.getJSONObject("final").getString("outcome"))
        assertEquals(380, j.getJSONObject("final").getInt("waited_ms"))
        assertEquals(490, j.getInt("align_covered_ms"))
        assertEquals(1, j.getInt("scan_drops"))
        val cw = j.getJSONObject("change_watch")
        assertEquals(2, cw.getInt("trips"))
        assertEquals(3, cw.getInt("single_frame_flags"))
        assertEquals(1.3, cw.getJSONObject("max").getDouble("edge_factor"), 1e-9)
        assertEquals(listOf("mouth", "chin"), (0 until cw.getJSONArray("trip_regions").length()).map { cw.getJSONArray("trip_regions").getString(it) })
    }

    @Test fun `a model that failed to load is reported with no hash`() {
        val j = CoveredCheckStats().apply { modelFailed(35, "model not bundled") }.toJson()
        assertTrue(j.isNull("model_sha256_prefix"))
        assertEquals("model not bundled", j.getString("model_error"))
        assertEquals(0, j.getInt("runs"))
        assertTrue(j.isNull("run_ms_p50"))
        assertTrue(j.isNull("at_scan_start"))
        assertTrue(j.isNull("final"))
    }

    @Test fun `a final check that timed out has an outcome and no split`() {
        val j = CoveredCheckStats().apply { modelLoaded(400); scanStarted(null, null); finalCheck(null, 2510, "timeout") }.toJson()
        val f = j.getJSONObject("final")
        assertEquals(setOf("waited_ms", "outcome"), keys(f))
        assertEquals("timeout", f.getString("outcome"))
    }

    @Test fun `a new scan resets the per-scan fields, not the counters`() {
        val s = filled()
        s.scanStarted(null, null)
        val j = s.toJson()
        assertTrue(j.isNull("at_scan_start"))
        assertTrue(j.isNull("final"))
        assertEquals(0.0, j.getJSONObject("scan_max").getDouble("body_skin"), 0.0)
        assertEquals(1.0, j.getJSONObject("change_watch").getJSONObject("max").getDouble("edge_factor"), 0.0)
        assertEquals(1, j.getInt("scan_drops"))
        assertEquals(2, j.getJSONObject("change_watch").getInt("trips"))
    }

    @Test fun `the six-class split covers the whole face area`() {
        // 100 x 100 mask: a third body skin, a little hair, the rest face skin.
        val pts = FloatArray(478 * 3)
        QualityGate.FACE_OVAL.forEachIndexed { k, idx ->
            val a = 2 * Math.PI * k / QualityGate.FACE_OVAL.size
            pts[idx * 3] = (50 + 40 * kotlin.math.cos(a)).toFloat()
            pts[idx * 3 + 1] = (50 + 40 * kotlin.math.sin(a)).toFloat()
        }
        val mask = IntArray(100 * 100) {
            val y = it / 100
            when {
                y < 20 -> FaceCover.HAIR
                y > 62 -> FaceCover.BODY_SKIN
                else -> FaceCover.FACE_SKIN
            }
        }
        val c = FaceCover.measure({ mask[it] }, 100, 100, 100, 100, pts, 478)!!
        assertEquals(1f, c.background + c.hair + c.bodySkin + c.faceSkin + c.clothes + c.others, 1e-4f)
        assertEquals(c.background + c.clothes + c.others, c.other, 1e-6f)
        assertTrue(c.hair > 0f && c.bodySkin > 0f && c.faceSkin > 0f)
    }

    @Test fun `metadata json carries covered_check when the check ran`() {
        fun build(cc: JSONObject?) = JSONObject(String(MetadataBuilder().build(
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
            coveredCheck = cc,
        ), Charsets.UTF_8))
        assertEquals("cover-v1", build(filled().toJson()).getJSONObject("covered_check").getString("version"))
        assertFalse(build(null).has("covered_check"))
    }
}
