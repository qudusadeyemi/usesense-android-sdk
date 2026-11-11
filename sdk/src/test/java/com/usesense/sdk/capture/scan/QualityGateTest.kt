package com.usesense.sdk.capture.scan

import com.usesense.sdk.capture.ImageQualityAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
     * 478 points filling a face ellipse of radii [rx] x [ry] around ([cx], [cy]),
     * the FACE_OVAL silhouette on its outline (10 top, 152 chin, 234 / 454
     * cheeks), iris centres [eyeDist] apart, stable points moved by [jitter].
     * The defaults sit where a well-placed face does: centred, low in the oval.
     */
    private fun face(
        cx: Float = 240f,
        cy: Float = 364f,
        rx: Float = 110f,
        ry: Float = 150f,
        eyeDist: Float = 100f,
        jitter: Float = 0f,
    ): FloatArray {
        val pts = FloatArray(478 * 3)
        for (i in 0 until 478) {
            val a = i * 2.399963
            val r = kotlin.math.sqrt((i + 0.5) / 478.0) * 0.9
            pts[i * 3] = cx + (rx * r * cos(a)).toFloat() + jitter
            pts[i * 3 + 1] = cy + (ry * r * sin(a)).toFloat()
            pts[i * 3 + 2] = (-r).toFloat()
        }
        val ring = QualityGate.FACE_OVAL
        ring.forEachIndexed { k, idx ->
            val a = -Math.PI / 2 + 2 * Math.PI * k / ring.size
            pts[idx * 3] = cx + (rx * cos(a)).toFloat() + jitter
            pts[idx * 3 + 1] = cy + (ry * sin(a)).toFloat()
        }
        pts[234 * 3] = cx - rx + jitter; pts[234 * 3 + 1] = cy
        pts[454 * 3] = cx + rx + jitter; pts[454 * 3 + 1] = cy
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

    // ── Dark room, no face (iOS PR #102 device finding) ──────────────────

    private fun noFace(t: Long, luma: Double?) = GateFrame(t, 480, 640, 0, null, null, null, frameLuma = luma)

    @Test fun `dark frame with no face turns the fill light on after the hint delay, silently`() {
        val gate = QualityGate()
        val r0 = gate.evaluate(noFace(0, 12.0), identity, oval)
        assertEquals(listOf(GateHint.FILLING_NO_FACE), r0.failing)
        assertFalse(r0.faceTooDark)
        assertFalse(gate.evaluate(noFace(300, 12.0), identity, oval).faceTooDark)
        val r1 = gate.evaluate(noFace(400, 12.0), identity, oval)
        assertTrue(r1.faceTooDark)
        // Still no face: it blocks the scan like "Show your face".
        assertFalse(r1.allPass)
        // While the light settles there is no text.
        assertEquals(listOf(GateHint.FILLING_NO_FACE), gate.evaluate(noFace(800, 12.0), identity, oval, fillLightOnForMs = 400).failing)
    }

    @Test fun `still no face once the light has settled asks for more light, not for the face`() {
        val gate = QualityGate()
        gate.evaluate(noFace(0, 5.0), identity, oval)
        val r = gate.evaluate(noFace(2000, 5.0), identity, oval, fillLightOnForMs = 1500)
        assertEquals(listOf(GateHint.DARK_NO_FACE), r.failing)
        assertFalse(GateHint.SHOW_FACE in r.failing)
        assertFalse(r.allPass)
    }

    @Test fun `a bright frame with no face, or no measurement, behaves as before`() {
        val gate = QualityGate()
        val bright = gate.evaluate(noFace(0, 120.0), identity, oval)
        assertEquals(listOf(GateHint.SHOW_FACE), bright.failing)
        assertFalse(bright.faceTooDark)
        assertEquals(listOf(GateHint.SHOW_FACE), gate.evaluate(noFace(500, null), identity, oval).failing)
        // The dark clock restarts once the frame is lit.
        gate.evaluate(noFace(600, 10.0), identity, oval)
        gate.evaluate(noFace(700, 150.0), identity, oval)
        assertFalse(gate.evaluate(noFace(900, 10.0), identity, oval).faceTooDark)
    }

    @Test fun `during a scan or a movement a dark no-face frame changes nothing`() {
        val gate = QualityGate()
        gate.evaluate(noFace(0, 5.0), identity, oval, scanning = true)
        val scan = gate.evaluate(noFace(1000, 5.0), identity, oval, scanning = true)
        assertEquals(listOf(GateHint.SHOW_FACE), scan.failing)
        assertFalse(scan.faceTooDark)
        val move = gate.evaluate(noFace(2000, 5.0), identity, oval, motion = GateMotion(yawAllowed = true, pitchAllowed = false, moving = true))
        assertEquals(listOf(GateHint.SHOW_FACE), move.failing)
        assertFalse(move.faceTooDark)
    }

    @Test fun `centre luma reads the middle 60 percent only`() {
        val w = 100; val h = 100
        // Black frame with a white border 20 px wide: the middle is black.
        val argb = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x < 20 || x >= 80 || y < 20 || y >= 80) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertEquals(0.0, QualityGate.centreLuma(argb, w, h)!!, 0.001)
        val grey = IntArray(w * h) { 0xFF808080.toInt() }
        assertEquals(128.0, QualityGate.centreLuma(grey, w, h)!!, 0.5)
        assertNull(QualityGate.centreLuma(IntArray(0), 0, 0))
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

    @Test fun `silhouette may reach 1_15 oval radii`() {
        val gate = QualityGate()
        warm(gate)
        // The lower diagonal of a wider face reaches ~1.14 radii: still in frame.
        val r = gate.evaluate(frame(500, pts = face(rx = 138f)), identity, oval)
        assertTrue("fit ${r.metrics!!.ovalFit}", r.metrics.ovalFit in 1.1f..1.15f)
        assertFalse(r.failing.any { it.check == GateCheck.IN_OVAL })
    }

    @Test fun `a centred face too big for the oval asks to move back, not to centre`() {
        val gate = QualityGate()
        warm(gate)
        // Cheeks at 160 / 130 = 1.23, face centre where a well-placed face sits.
        val r = gate.evaluate(frame(500, pts = face(rx = 160f)), identity, oval)
        assertFalse(GateHint.CENTRE in r.failing)
        assertTrue(GateHint.TOO_BIG in r.failing)
    }

    @Test fun `eye ratio is on screen, relative to the oval, and runs before in-frame`() {
        val gate = QualityGate()
        warm(gate)
        // The oval is 260 px wide, so the ratio is eye distance / 361.
        // 55 / 361 = 0.15 < 0.17
        assertTrue(GateHint.MOVE_CLOSER in gate.evaluate(frame(500, pts = face(eyeDist = 55f)), identity, oval).failing)
        // 120 / 361 = 0.33 > 0.32, and the size hint comes first.
        val far = gate.evaluate(frame(600, pts = face(eyeDist = 120f, rx = 160f)), identity, oval)
        assertEquals(GateHint.MOVE_BACK, far.top)
        assertFalse(GateHint.TOO_BIG in far.failing)
        // 100 / 361 = 0.28 is fine.
        val ok = gate.evaluate(frame(700, pts = face(eyeDist = 100f)), identity, oval)
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
        // Light is advice only: a dark face still passes the gate.
        assertTrue(r3.allPass)
        assertTrue(r3.blocking.isEmpty())
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
        // 3 px of jitter on a 100 px eye distance = 0.03 < 0.04
        assertTrue(gate.evaluate(frame(500, pts = face(jitter = 3f)), identity, oval).allPass)
        val g2 = QualityGate()
        warm(g2)
        // 12 px jump = 0.12 > 0.04
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

    @Test fun `distance band holds a little wider once scanning`() {
        val gate = QualityGate()
        warm(gate)
        // 120 / 361 = 0.332: outside 0.32 to start, inside 0.34 while scanning.
        val edge = face(eyeDist = 120f)
        assertTrue(GateHint.MOVE_BACK in gate.evaluate(frame(500, pts = edge), identity, oval).failing)
        assertFalse(GateHint.MOVE_BACK in gate.evaluate(frame(600, pts = edge), identity, oval, scanning = true).failing)
        // 130 / 361 = 0.36 still fails while scanning.
        assertTrue(GateHint.MOVE_BACK in gate.evaluate(frame(700, pts = face(eyeDist = 130f)), identity, oval, scanning = true).failing)
    }

    // ── Fill light and the change watch (COVER-v1) ──

    private fun regions(lumaChin: Float = 1f) = FaceRegions(FloatArray(FaceRegions.COUNT * FaceRegions.STRIDE) { k ->
        when (k % FaceRegions.STRIDE) {
            0 -> if (k / FaceRegions.STRIDE == 5) lumaChin else 1f
            1 -> 0.4f
            2 -> 0.25f
            else -> 10f
        }
    })

    private val clear = FaceCover(bodySkin = 0f, other = 0f, faceSkin = 1f)
    private val handOver = FaceCover(bodySkin = 0.5f, other = 0f, faceSkin = 0.5f)

    private fun scanFrame(t: Long, r: FaceRegions, cover: FaceCover? = clear) =
        GateFrame(t, 480, 640, 1, face(), PoseAngles(0.0, 0.0, 0.0), goodRegion, 0.1f, 0.1f, r, cover, false, t)

    /** A gate with stats, warmed up (face found, 300 ms of stillness history) before the scan. */
    private fun gateWithStats() = QualityGate().also { it.coverStats = CoveredCheckStats(); warm(it) }

    private fun covered(g: QualityGate, f: GateFrame) =
        GateHint.FACE_COVERED in g.evaluate(f, identity, oval, scanning = true).failing

    @Test fun `the fill light is frozen while a scan runs`() {
        val latch = FillLightLatch()
        assertTrue(latch.mayToggle())
        latch.scanStarted()
        assertFalse(latch.mayToggle())
        latch.scanEnded()
        assertTrue(latch.mayToggle())
    }

    @Test fun `a light change re-baselines after 500 ms on a clear face`() {
        val g = gateWithStats()
        for (t in listOf(1000L, 1100L, 1200L)) assertFalse(covered(g, scanFrame(t, regions())))
        g.onLightChanged(1300)
        // The light lit the chin region unevenly: ignored while exposure settles.
        assertFalse(covered(g, scanFrame(1350, regions(1.5f))))
        assertFalse(covered(g, scanFrame(1450, regions(1.5f))))
        // After 500 ms, clear: a new baseline is taken from the lit face.
        assertFalse(covered(g, scanFrame(1800, regions(1.5f))))
        for (t in listOf(1900L, 2000L, 2100L, 2200L)) assertFalse(covered(g, scanFrame(t, regions(1.5f))))
        assertEquals(1, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("rebaselines"))
    }

    @Test fun `a light change while covered waits for a clear result`() {
        val g = gateWithStats()
        for (t in listOf(1000L, 1100L, 1200L)) covered(g, scanFrame(t, regions()))
        g.onLightChanged(1300)
        val waiting = g.evaluate(scanFrame(1800, regions(1.5f), handOver), identity, oval, scanning = true)
        assertTrue(GateHint.HOLD_STILL in waiting.failing)
        assertEquals(0, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("rebaselines"))
        g.evaluate(scanFrame(1900, regions(1.5f), clear), identity, oval, scanning = true)
        assertEquals(1, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("rebaselines"))
    }

    @Test fun `a brightness jump with no light change is not re-baselined`() {
        val g = gateWithStats()
        for (t in listOf(1000L, 1100L, 1200L)) covered(g, scanFrame(t, regions()))
        assertFalse(covered(g, scanFrame(1300, regions(1.5f))))
        assertTrue(covered(g, scanFrame(1400, regions(1.5f))))
        assertEquals(0, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("rebaselines"))
    }

    @Test fun `a light change long before the scan doesn't touch it`() {
        val g = gateWithStats()
        g.onLightChanged(100)
        for (t in listOf(1000L, 1100L, 1200L)) covered(g, scanFrame(t, regions()))
        assertFalse(covered(g, scanFrame(1300, regions(1.5f))))
        assertTrue(covered(g, scanFrame(1400, regions(1.5f))))
        assertEquals(0, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("rebaselines"))
    }

    private fun scanFrameAt(t: Long, r: FaceRegions, pts: FloatArray) =
        GateFrame(t, 480, 640, 1, pts, PoseAngles(0.0, 0.0, 0.0), goodRegion, 0.1f, 0.1f, r, clear, false, t)

    @Test fun `a change while the face moves reads hold still, not uncovered`() {
        val g = gateWithStats()
        for (t in listOf(1000L, 1100L, 1200L)) assertFalse(covered(g, scanFrame(t, regions())))
        // The chin region changes on two frames while the face is moving.
        val moving = listOf(1300L, 1400L).map {
            g.evaluate(scanFrameAt(it, regions(1.5f), face(jitter = if (it == 1300L) 20f else -20f)), identity, oval, scanning = true)
        }
        assertTrue(moving.all { GateHint.HOLD_STILL in it.failing })
        assertTrue(moving.none { GateHint.FACE_COVERED in it.failing })
        assertEquals(0, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("trips"))
        // Once the face is still again, the same change is a cover.
        val later = (1800L..2400L step 100L).map { covered(g, scanFrame(it, regions(1.5f))) }
        assertTrue(later.last())
        assertEquals(1, g.coverStats!!.toJson().getJSONObject("change_watch").getInt("trips"))
    }

    @Test fun `the baseline waits for a settled face`() {
        // No warm-up: the first scan frames have no stillness history, so they
        // must not become the baseline.
        val g = QualityGate().also { it.coverStats = CoveredCheckStats() }
        // Three unsettled frames with a different chin...
        for (t in listOf(0L, 50L, 100L)) covered(g, scanFrame(t, regions(1.5f)))
        // ...then settled frames with the real face: the baseline is these.
        for (t in 200L..900L step 100L) covered(g, scanFrame(t, regions()))
        // The real face stays clear (it would trip against an unsettled baseline).
        assertFalse((1000L..1300L step 100L).map { covered(g, scanFrame(it, regions())) }.any { it })
    }

    @Test fun `the watch is off until a steady baseline exists (a shutter start mid-motion)`() {
        // A shutter start ignores the gate: the scan begins while the head moves.
        val g = QualityGate().also { it.coverStats = CoveredCheckStats() }
        val moving = (0L..600L step 100L).map {
            g.evaluate(scanFrameAt(it, regions(if (it % 200 == 0L) 1.5f else 1f), face(jitter = if (it % 200 == 0L) 20f else -20f)),
                identity, oval, scanning = true)
        }
        assertTrue(moving.none { GateHint.FACE_COVERED in it.failing })
        assertTrue(g.coverStats!!.toJson().getJSONObject("change_watch").isNull("baseline_wait_ms"))
        // The head settles: after 300 ms of stillness the next 3 frames are the baseline.
        val settled = (700L..1400L step 100L).map { covered(g, scanFrame(it, regions())) }
        assertTrue(settled.none { it })
        val wait = g.coverStats!!.toJson().getJSONObject("change_watch").getLong("baseline_wait_ms")
        assertTrue("baseline taken after the head settled: $wait ms", wait >= 900L)
    }

    @Test fun `a hand on a still head is still caught`() {
        val g = gateWithStats()
        for (t in listOf(1000L, 1100L, 1200L)) assertFalse(covered(g, scanFrame(t, regions())))
        assertFalse(covered(g, scanFrame(1300, regions(1.5f))))
        assertTrue(covered(g, scanFrame(1400, regions(1.5f))))
    }

    @Test fun `stillness failing shows hold still, and the drop isn't a cover drop`() {
        val g = gateWithStats()
        for (t in listOf(1000L, 1100L, 1200L)) covered(g, scanFrame(t, regions()))
        val r = g.evaluate(scanFrameAt(1300, regions(1.5f), face(jitter = 20f)), identity, oval, scanning = true)
        assertTrue(GateHint.HOLD_STILL in r.failing)
        assertFalse(GateHint.FACE_COVERED in r.failing)
        assertTrue(r.moving)
        assertFalse(r.coverDrop)
        // The segmenter says covered while moving: still not a cover drop.
        val seg = g.evaluate(GateFrame(1400, 480, 640, 1, face(jitter = -20f), PoseAngles(0.0, 0.0, 0.0), goodRegion, 0.1f, 0.1f,
            regions(), handOver, false, 1400), identity, oval, scanning = true)
        assertTrue(GateHint.FACE_COVERED in seg.failing)
        assertFalse(seg.coverDrop)
        // Covered on a still face is (after the head has been still for the window).
        for (t in 1500L..2100L step 100L) g.evaluate(scanFrame(t, regions()), identity, oval, scanning = true)
        val still = g.evaluate(GateFrame(2200, 480, 640, 1, face(), PoseAngles(0.0, 0.0, 0.0), goodRegion, 0.1f, 0.1f,
            regions(), handOver, false, 2200), identity, oval, scanning = true)
        assertTrue(still.coverDrop)
    }

    // ─── During an extra check (the same gate; only pose and stillness give way) ───

    private val turning = GateMotion(yawAllowed = true, pitchAllowed = false, moving = true)

    private fun turningFrame(t: Long, yaw: Double, pts: FloatArray? = face(jitter = (t % 300).toFloat() / 25f), cover: FaceCover? = null) =
        GateFrame(t, 480, 640, 1, pts, PoseAngles(yaw, 0.0, 0.0), goodRegion, 0.1f, 0.1f, cover = cover)

    @Test fun `during a head turn yaw toward the step passes, the scan limit doesn't`() {
        val gate = QualityGate()
        warm(gate)
        val r = gate.evaluate(turningFrame(500, yaw = 35.0), identity, oval, scanning = true, motion = turning)
        assertTrue(r.failing.toString(), r.allPass)
        val strict = gate.evaluate(turningFrame(600, yaw = 35.0), identity, oval, scanning = true,
            motion = turning.copy(yawAllowed = false))
        assertTrue(GateHint.LOOK_STRAIGHT in strict.failing)
        val tooFar = gate.evaluate(turningFrame(700, yaw = 55.0), identity, oval, scanning = true, motion = turning)
        assertTrue(GateHint.LOOK_STRAIGHT in tooFar.failing)
    }

    @Test fun `a moving head isn't told to hold still, but a still-only check is`() {
        val gate = QualityGate()
        warm(gate)
        var moving: GateResult? = null
        var t = 500L
        while (t < 1200) { moving = gate.evaluate(turningFrame(t, yaw = 10.0), identity, oval, scanning = true, motion = turning); t += 100 }
        assertFalse(GateHint.HOLD_STILL in moving!!.failing)
        assertTrue(moving.moving)
        val gate2 = QualityGate()
        warm(gate2)
        var still: GateResult? = null
        t = 500L
        while (t < 1200) { still = gate2.evaluate(turningFrame(t, yaw = 10.0), identity, oval, scanning = true, motion = null); t += 100 }
        assertTrue(GateHint.HOLD_STILL in still!!.failing)
    }

    @Test fun `a covered face, a second face, distance and frame still pause a movement`() {
        val gate = QualityGate()
        warm(gate)
        val covered = gate.evaluate(turningFrame(500, yaw = 20.0, cover = handOver), identity, oval, scanning = true, motion = turning)
        assertTrue(GateHint.FACE_COVERED in covered.blocking)
        val two = gate.evaluate(GateFrame(600, 480, 640, 2, face(), PoseAngles(20.0, 0.0, 0.0), goodRegion, 0.1f, 0.1f),
            identity, oval, scanning = true, motion = turning)
        assertTrue(GateHint.ONE_PERSON in two.blocking)
        val far = gate.evaluate(turningFrame(700, yaw = 20.0, pts = face(rx = 50f, ry = 70f, eyeDist = 40f)), identity, oval,
            scanning = true, motion = turning)
        assertTrue(GateHint.MOVE_CLOSER in far.blocking)
        val off = gate.evaluate(turningFrame(800, yaw = 20.0, pts = face(cx = 380f)), identity, oval, scanning = true, motion = turning)
        assertTrue(GateHint.CENTRE in off.blocking)
        val shut = gate.evaluate(GateFrame(900, 480, 640, 1, face(), PoseAngles(20.0, 0.0, 0.0), goodRegion, 0.9f, 0.9f),
            identity, oval, scanning = true, motion = turning)
        assertTrue(GateHint.EYES_OPEN in shut.blocking)
    }
}
