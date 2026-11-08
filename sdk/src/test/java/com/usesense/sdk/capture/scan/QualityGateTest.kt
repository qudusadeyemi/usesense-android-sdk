package com.usesense.sdk.capture.scan

import com.usesense.sdk.capture.ImageQualityAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class QualityGateTest {

    private val identity = PointMapper { x, y, out -> out[0] = x; out[1] = y }

    /** A 480x640 frame with the oval centred, radii 130 x 175 px. */
    private val oval = OvalSpec(cx = 240f, cy = 320f, rx = 130f, ry = 175f)

    private val goodRegion = ImageQualityAnalyzer.FaceRegionReport(
        meanLuma = 130.0, clippedRatio = 0.01, highlightRatio = 0.005, sharpness = 100.0,
    )

    /**
     * 478 points filling an ellipse of radii [rx] x [ry] around ([cx], [cy]),
     * iris centres [eyeDist] apart, stable points slightly moved by [jitter].
     */
    private fun face(
        cx: Float = 240f,
        cy: Float = 320f,
        rx: Float = 85f,
        ry: Float = 115f,
        eyeDist: Float = 110f,
        jitter: Float = 0f,
    ): FloatArray {
        val pts = FloatArray(478 * 3)
        for (i in 0 until 478) {
            val a = i * 2.399963
            val r = kotlin.math.sqrt((i + 0.5) / 478.0)
            pts[i * 3] = cx + (rx * r * cos(a)).toFloat() + jitter
            pts[i * 3 + 1] = cy + (ry * r * sin(a)).toFloat()
            pts[i * 3 + 2] = (-r).toFloat()
        }
        pts[468 * 3] = cx - eyeDist / 2 + jitter
        pts[468 * 3 + 1] = cy - 20
        pts[473 * 3] = cx + eyeDist / 2 + jitter
        pts[473 * 3 + 1] = cy - 20
        return pts
    }

    private fun frame(
        t: Long,
        pts: FloatArray? = face(),
        faces: Int = 1,
        pose: PoseAngles? = PoseAngles(0.0, 0.0, 0.0),
        region: ImageQualityAnalyzer.FaceRegionReport? = goodRegion,
        blink: Float? = 0.1f,
    ) = GateFrame(t, 480, 640, faces, pts, pose, region, blink, blink)

    /** Feed still, good frames every 100 ms until stillness has its 300 ms. */
    private fun warm(gate: QualityGate, until: Long = 400): GateResult {
        var r: GateResult? = null
        var t = 0L
        while (t <= until) { r = gate.evaluate(frame(t), identity, oval); t += 100 }
        return r!!
    }

    @Test fun `good still face passes after warm-up`() {
        val gate = QualityGate()
        assertTrue(warm(gate).allPass)
    }

    @Test fun `no face asks to show the face`() {
        val gate = QualityGate()
        val r = gate.evaluate(frame(0, pts = null, faces = 0), identity, oval)
        assertEquals(listOf(GateHint.SHOW_FACE), r.failing)
    }

    @Test fun `face must be found three frames in a row`() {
        val gate = QualityGate()
        assertEquals(GateHint.SHOW_FACE, gate.evaluate(frame(0), identity, oval).top)
        assertEquals(GateHint.SHOW_FACE, gate.evaluate(frame(100), identity, oval).top)
        assertFalse(GateHint.SHOW_FACE in gate.evaluate(frame(200), identity, oval).failing)
    }

    @Test fun `second face fails one person`() {
        val gate = QualityGate()
        warm(gate)
        assertEquals(GateHint.ONE_PERSON, gate.evaluate(frame(500, faces = 2), identity, oval).top)
    }

    @Test fun `face off centre asks to centre`() {
        val gate = QualityGate()
        warm(gate)
        val r = gate.evaluate(frame(500, pts = face(cx = 330f)), identity, oval)
        assertEquals(GateHint.CENTRE, r.top)
    }

    @Test fun `face bigger than the oval asks to move back, not to centre`() {
        val gate = QualityGate()
        warm(gate)
        val r = gate.evaluate(frame(500, pts = face(rx = 140f, ry = 190f, eyeDist = 120f)), identity, oval)
        assertFalse(GateHint.CENTRE in r.failing)
        assertTrue(GateHint.MOVE_BACK in r.failing)
    }

    @Test fun `distance thresholds are eye distance over frame width`() {
        val gate = QualityGate()
        warm(gate)
        // 0.15 < 0.17
        assertTrue(GateHint.MOVE_CLOSER in gate.evaluate(frame(500, pts = face(eyeDist = 72f)), identity, oval).failing)
        // 0.32 > 0.30
        assertTrue(GateHint.MOVE_BACK in gate.evaluate(frame(600, pts = face(eyeDist = 154f)), identity, oval).failing)
        // 0.25 is fine
        val ok = gate.evaluate(frame(700, pts = face(eyeDist = 120f)), identity, oval)
        assertFalse(ok.failing.any { it.check == GateCheck.DISTANCE })
    }

    @Test fun `pose limits are 15, 15 and 12 degrees`() {
        val gate = QualityGate()
        warm(gate)
        assertTrue(GateHint.LOOK_STRAIGHT in gate.evaluate(frame(500, pose = PoseAngles(16.0, 0.0, 0.0)), identity, oval).failing)
        assertTrue(GateHint.LOOK_STRAIGHT in gate.evaluate(frame(600, pose = PoseAngles(0.0, -15.0, 0.0)), identity, oval).failing)
        assertTrue(GateHint.LOOK_STRAIGHT in gate.evaluate(frame(700, pose = PoseAngles(0.0, 0.0, 12.5)), identity, oval).failing)
        assertFalse(GateHint.LOOK_STRAIGHT in gate.evaluate(frame(800, pose = PoseAngles(14.0, 14.0, 11.0)), identity, oval).failing)
    }

    @Test fun `dark face brightens first, then asks for a light`() {
        val gate = QualityGate()
        warm(gate)
        val dark = goodRegion.copy(meanLuma = 50.0)
        val r1 = gate.evaluate(frame(500, region = dark), identity, oval, fillLightOnForMs = null)
        assertTrue(r1.faceTooDark)
        assertTrue(GateHint.BRIGHTENING in r1.failing)
        val r2 = gate.evaluate(frame(600, region = dark), identity, oval, fillLightOnForMs = 400)
        assertTrue(GateHint.BRIGHTENING in r2.failing)
        val r3 = gate.evaluate(frame(700, region = dark), identity, oval, fillLightOnForMs = 1600)
        assertTrue(GateHint.FACE_LIGHT in r3.failing)
    }

    @Test fun `blown highlights fail light as too bright`() {
        val gate = QualityGate()
        warm(gate)
        val r = gate.evaluate(frame(500, region = goodRegion.copy(clippedRatio = 0.10, highlightRatio = 0.09)), identity, oval)
        assertTrue(GateHint.TOO_BRIGHT in r.failing)
        assertFalse(r.faceTooDark)
        val r2 = gate.evaluate(frame(600, region = goodRegion.copy(meanLuma = 210.0)), identity, oval)
        assertTrue(GateHint.TOO_BRIGHT in r2.failing)
    }

    @Test fun `soft face fails sharpness on the median of three frames`() {
        val gate = QualityGate(sharpnessThreshold = { 50.0 })
        warm(gate)
        val soft = goodRegion.copy(sharpness = 10.0)
        // One soft frame among sharp ones does not fail.
        assertFalse(GateHint.HOLD_STEADY in gate.evaluate(frame(500, region = soft), identity, oval).failing)
        // Two of three soft does.
        assertTrue(GateHint.HOLD_STEADY in gate.evaluate(frame(600, region = soft), identity, oval).failing)
    }

    @Test fun `closed eyes fail eyes open`() {
        val gate = QualityGate()
        warm(gate)
        assertTrue(GateHint.EYES_OPEN in gate.evaluate(frame(500, blink = 0.7f), identity, oval).failing)
        // Without blendshapes the check is skipped.
        assertFalse(GateHint.EYES_OPEN in gate.evaluate(frame(600, blink = null), identity, oval).failing)
    }

    @Test fun `movement fails stillness, small jitter does not`() {
        val gate = QualityGate()
        warm(gate)
        // 3 px of jitter on a 110 px eye distance = 0.027 < 0.04
        assertTrue(gate.evaluate(frame(500, pts = face(jitter = 3f)), identity, oval).allPass)
        val g2 = QualityGate()
        warm(g2)
        // 12 px jump = 0.11 > 0.04
        assertTrue(GateHint.HOLD_STILL in g2.evaluate(frame(500, pts = face(jitter = 12f)), identity, oval).failing)
    }

    @Test fun `a face that just appeared is not yet still`() {
        val gate = QualityGate()
        gate.evaluate(frame(0), identity, oval)
        gate.evaluate(frame(100), identity, oval)
        val r = gate.evaluate(frame(200), identity, oval)
        assertTrue(GateHint.HOLD_STILL in r.failing)
    }

    @Test fun `failing hints are in priority order`() {
        val gate = QualityGate()
        warm(gate)
        val r = gate.evaluate(
            frame(500, faces = 2, pose = PoseAngles(30.0, 0.0, 0.0), region = goodRegion.copy(meanLuma = 40.0)),
            identity, oval,
        )
        assertEquals(listOf(GateCheck.ONE_PERSON, GateCheck.POSE, GateCheck.LIGHT), r.failing.map { it.check }.distinct())
    }
}
