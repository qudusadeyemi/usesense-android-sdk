package com.usesense.sdk.evidence

import com.usesense.sdk.api.models.CreateSessionResponse
import com.usesense.sdk.api.models.apiMoshi
import com.usesense.sdk.capture.scan.ScanController
import com.usesense.sdk.liveness.FittedFrame
import com.usesense.sdk.liveness.FrameMeshData
import com.usesense.sdk.liveness.FaceBoundingBox
import com.usesense.sdk.liveness.HeadPose
import com.usesense.sdk.liveness.LandmarkPoint
import com.usesense.sdk.liveness.MeshBindingProof
import com.usesense.sdk.liveness.VerificationPackageBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {

    // ─── Section 1: the evidence block decodes leniently ────────────────

    private val adapter = apiMoshi().adapter(CreateSessionResponse::class.java)

    private fun session(evidence: String?): String = """
        {"session_id":"s","session_token":"t","expires_at":"2026-11-10T00:00:00Z","nonce":"n",
         "policy":{},"upload":{"max_frames":30,"target_fps":3,"capture_duration_ms":4500}
         ${evidence?.let { ",\"evidence\":$it" } ?: ""}}
    """.trimIndent()

    private val issued = """
        {"contract":"evidence-v1",
         "colour_sequence":{"id":"cs_7f3a9c21","colours":["#3D6BFF","#ffd23f","#B04DFF"],"segment_ms":600,"lead_in_ms":300,"opacity":0.35},
         "closer_step":{"enabled":true,"target_scale":1.4,"min_scale":1.25},
         "binding_v2":{"enabled":true},
         "landmarks":{"required":true,"format":"mp_norm_v1"},
         "future_key":{"anything":[1,2,3]}}
    """.trimIndent()

    @Test
    fun `the evidence block decodes with unknown keys ignored`() {
        val ev = adapter.fromJson(session(issued))!!.evidence!!
        val cs = ev.colourSequence!!
        assertEquals("cs_7f3a9c21", cs.id)
        assertEquals(listOf("#3D6BFF", "#FFD23F", "#B04DFF"), cs.colours)
        assertEquals(600, cs.segmentMs)
        assertEquals(300, cs.leadInMs)
        assertEquals(0.35, cs.opacity, 1e-9)
        assertEquals(2100, cs.totalMs)
        assertTrue(ev.closerEnabled)
        assertEquals(1.4, ev.closerStep!!.targetScale, 1e-9)
        assertEquals(1.25, ev.closerStep!!.minScale, 1e-9)
        assertTrue(ev.bindingV2Enabled)
        assertTrue(ev.landmarksRequired)
    }

    @Test
    fun `an absent, odd or foreign evidence block never fails the session`() {
        assertNull(adapter.fromJson(session(null))!!.evidence)
        assertNull(adapter.fromJson(session("\"evidence-v1\""))!!.evidence)
        assertNull(adapter.fromJson(session("[1,2]"))!!.evidence)
        assertNull(adapter.fromJson(session("null"))!!.evidence)
        assertNull(adapter.fromJson(session("""{"contract":"evidence-v2","binding_v2":{"enabled":true}}"""))!!.evidence)
        assertNull(adapter.fromJson(session("""{"binding_v2":{"enabled":true}}"""))!!.evidence)
    }

    @Test
    fun `inside the block every key is optional and a malformed one reads as absent`() {
        val ev = adapter.fromJson(session("""{"contract":"evidence-v1","colour_sequence":{"id":"x","colours":["#12345G"]},"closer_step":"yes","binding_v2":true}"""))!!.evidence!!
        assertNull(ev.colourSequence)
        assertNull(ev.closerStep)
        assertFalse(ev.closerEnabled)
        assertFalse(ev.bindingV2Enabled)
        assertFalse(ev.landmarksRequired)
        // Web sessions: flags off, read as sent.
        val web = EvidenceConfig.parse(mapOf("contract" to "evidence-v1", "closer_step" to mapOf("enabled" to false), "binding_v2" to mapOf("enabled" to false)))!!
        assertFalse(web.closerEnabled)
        assertFalse(web.bindingV2Enabled)
        // Out of range numbers are clamped, min never above target.
        val c = EvidenceConfig.parse(mapOf("contract" to "evidence-v1", "closer_step" to mapOf("enabled" to true, "target_scale" to 9.0, "min_scale" to 5.0)))!!
        assertEquals(2.0, c.closerStep!!.targetScale, 1e-9)
        assertEquals(2.0, c.closerStep!!.minScale, 1e-9)
    }

    // ─── Section 8: binding v2 digest ───────────────────────────────────

    @Test
    fun `binding v2 golden vector from the spec`() {
        val text = EvidenceDigest.text("s", "c", emptyList(), null, null)
        assertEquals("usesense-evidence-v1\nsession:s\nchallenge:c\ncolour:none\ncloser:none", text)
        assertEquals(
            "42c6249ed7be90ff3d6c4f64e9fe507bd0b6d79298c96d051394ac520828ba78",
            EvidenceDigest.digest("s", "c", emptyList(), null, null),
        )
    }

    @Test
    fun `digest lines for frames, colour and closer match the server's text`() {
        val frames = listOf(EvidenceDigest.Frame(0, "aa", "bb"), EvidenceDigest.Frame(2, "cc", "dd"))
        val colour = ColourEvidence(
            "cs_1",
            listOf(ColourEvidence.Segment("#3D6BFF", 1830, 2431), ColourEvidence.Segment("#FFD23F", 2431, 3030)),
        )
        val closer = CloserEvidence(farFrames = listOf(14, 15, 16), nearFrames = listOf(21, 22, 23), completed = true)
        // Digest generated with Node from the same lines (binding-v2.ts evidenceDigestText).
        assertEquals(
            "77faa8cfeeddc43953716cea7aa586af963c276522ff8af88bd2c4f9d52ec219",
            EvidenceDigest.digest("sess_1", "ab12", frames, colour, closer),
        )
        // A colour record with no segments, and a timed-out closer, read as none.
        val text = EvidenceDigest.text("s", "c", emptyList(), ColourEvidence("cs_1"), CloserEvidence.skipped(CloserEvidence.TIMEOUT))
        assertTrue(text.endsWith("colour:none\ncloser:none"))
    }

    @Test
    fun `the binding block carries the token or the error`() {
        val ok = BindingV2Evidence("d".repeat(64), integrityToken = "tok").toJson()
        assertEquals("binding_v2", ok.getString("version"))
        assertEquals("tok", ok.getJSONObject("android").getString("integrity_token"))
        assertFalse(ok.has("error"))
        val err = BindingV2Evidence("d".repeat(64), errorName = "StandardIntegrityException#-100", errorMessage = "x".repeat(400)).toJson()
        assertFalse(err.has("android"))
        assertEquals("StandardIntegrityException#-100", err.getJSONObject("error").getString("name"))
        assertEquals(256, err.getJSONObject("error").getString("message").length)
    }

    // ─── Sections 4 and 5: per-frame fields ─────────────────────────────

    @Test
    fun `rounding is half away from zero and never -0`() {
        assertEquals(0.12346, Evidence.round(0.123455, 5), 0.0)
        assertEquals(-0.12346, Evidence.round(-0.123455, 5), 0.0)
        assertEquals(0.0, Evidence.round(-0.000001, 5), 0.0)
        assertEquals("0.0", Evidence.round(-0.000001, 5).toString())
        assertEquals(0.0, Evidence.round(Double.NaN, 5), 0.0)
    }

    @Test
    fun `landmarks hash and mesh digest match the server's computeMeshDigest`() {
        // Node: sha256(Buffer.from(new Float64Array(lm).buffer)), then
        // sha256(JSON.stringify({s, p: [y, p, r], d, l: hash})).
        val lm = doubleArrayOf(0.12345, 0.5, -0.00012, 0.6, 0.7, 0.01)
        val hash = Evidence.landmarksHash(lm)
        assertEquals("d5dbefb464b2a1b87233cc970e9c418af9768d3e79132402734b2dfee2a14fe3", hash)
        assertEquals(
            "8a02ebd299d96ed28f3c2e83aec1a8a59c22441d96ca1851f0d95aaa3a996408",
            MeshBindingProof.computeMeshDigest(doubleArrayOf(0.1, -0.2), HeadPose(1.5, -0.5, 0.3), 80, hash),
        )
        assertNull(Evidence.landmarksHash(DoubleArray(0)))
    }

    @Test
    fun `matrix pose is the server's poseFromMatrix`() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, -40.0, 0.0, 0.0, 0.0, 1.0)
        val p0 = Evidence.matrixPose(identity)!!
        assertEquals(0.0, p0.yaw, 1e-9); assertEquals(0.0, p0.pitch, 1e-9); assertEquals(0.0, p0.roll, 1e-9)
        // Rotation of 20 degrees about Y (yaw): R = [[c,0,s],[0,1,0],[-s,0,c]].
        val a = Math.toRadians(20.0)
        val c = Math.cos(a); val s = Math.sin(a)
        val yaw = doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0)
        val p = Evidence.matrixPose(yaw)!!
        assertEquals(20.0, p.yaw, 1e-9); assertEquals(0.0, p.pitch, 1e-9); assertEquals(0.0, p.roll, 1e-9)
        // Uniform scale doesn't change the angles.
        assertEquals(20.0, Evidence.matrixPose(yaw.map { it * 3 }.toDoubleArray())!!.yaw, 1e-9)
        assertNull(Evidence.matrixPose(DoubleArray(9)))
    }

    @Test
    fun `column-major MediaPipe matrices are uploaded row-major`() {
        // Column-major packing of a matrix with translation (1, 2, -40).
        val cm = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 2f, -40f, 1f)
        val rm = com.usesense.sdk.capture.scan.HeadPoseMath.rowMajor(cm)!!
        assertEquals(1.0, rm[3], 0.0)
        assertEquals(2.0, rm[7], 0.0)
        assertEquals(-40.0, rm[11], 0.0)
        assertEquals(0.0, rm[12], 0.0)
    }

    // ─── Section 3: frame rules ─────────────────────────────────────────

    private fun mesh(index: Int, shift: Double = 0.0, blink: Double? = 0.0412): FrameMeshData {
        val pts = (0 until 478).map { i -> LandmarkPoint(0.3 + (i % 20) * 0.02 + shift + 0.0000049, 0.3 + (i / 20) * 0.015, -0.01 * (i % 7) - 0.0000051) }
        val m = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, -40.123456789, 0.0, 0.0, 0.0, 1.0)
        return FrameMeshData(index, index * 333L, HeadPose(9.0, 9.0, 9.0), 0.3, 0.3, FaceBoundingBox(0.3, 0.3, 0.4, 0.4), pts,
            transformMatrix = m, eyeBlinkLeft = blink, eyeBlinkRight = blink?.plus(0.01), imageWidth = 480, imageHeight = 640)
    }

    private fun fit(index: Int) = FittedFrame(index, index * 333L, DoubleArray(12) { 0.1 * it }, HeadPose(9.0, 9.0, 9.0), 80, DoubleArray(6), DoubleArray(5))

    private fun build(fits: List<FittedFrame>, meshes: List<FrameMeshData>, uploaded: Int, evidence: Boolean = true): JSONObject =
        VerificationPackageBuilder().build(
            fits = fits,
            crossFrameConsistency = 90,
            preliminaryScore = 85,
            uploadHashes = (0 until uploaded).map { "hash$it" },
            uploadPositionOf = (0 until uploaded).associateWith { it },
            meshBindingChallenge = "00112233",
            meshDataList = meshes,
            playIntegrityToken = null,
            evidence = evidence,
        )

    @Test
    fun `frameIndex is the upload position, ascending, one entry per frame, none for a mesh-less frame`() {
        // Frame 0 has no mesh (MediaPipe not ready); fits arrive out of order; frame 3 was fitted twice.
        val fits = listOf(fit(2), fit(1), fit(3), fit(3))
        val meshes = listOf(mesh(1), mesh(2), mesh(3))
        val frames = build(fits, meshes, uploaded = 5).getJSONArray("frames")
        val indices = (0 until frames.length()).map { frames.getJSONObject(it).getInt("frameIndex") }
        assertEquals(listOf(1, 2, 3), indices)
        val hashes = (0 until frames.length()).map { frames.getJSONObject(it).getString("frameHash") }
        assertEquals(listOf("hash1", "hash2", "hash3"), hashes)
        // A fit for a frame that was not uploaded has no entry.
        assertEquals(0, build(listOf(fit(9)), listOf(mesh(9)), uploaded = 5).getJSONArray("frames").length())
    }

    @Test
    fun `entries carry landmarks, matrix pose and blendshapes, bound into meshDigest`() {
        val f = build(listOf(fit(1)), listOf(mesh(1)), uploaded = 2).getJSONArray("frames").getJSONObject(0)
        assertEquals("matrix_zyx_v3", f.getString("poseNormalizationMethod"))
        val lm = f.getJSONArray("landmarks")
        assertEquals(478 * 3, lm.length())
        // 5 decimals, rounded before hashing.
        assertEquals(0.30000, lm.getDouble(0), 0.0)
        assertEquals(-0.00001, lm.getDouble(2), 0.0)
        assertEquals(-0.01001, lm.getDouble(5), 0.0)
        val tm = f.getJSONArray("transformMatrix")
        assertEquals(16, tm.length())
        assertEquals(-40.12346, tm.getDouble(11), 0.0)
        assertEquals(0.041, f.getJSONObject("blendshapes").getDouble("eyeBlinkLeft"), 0.0)
        assertEquals(0.051, f.getJSONObject("blendshapes").getDouble("eyeBlinkRight"), 0.0)
        // Pose from the matrix, not the landmark estimate.
        assertEquals(0.0, f.getJSONObject("pose").getDouble("yaw"), 1e-9)
        // meshDigest = the server's computeMeshDigest over what was sent.
        val sent = DoubleArray(lm.length()) { lm.getDouble(it) }
        val shape = f.getJSONArray("shapeParams").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val pose = f.getJSONObject("pose").let { HeadPose(it.getDouble("yaw"), it.getDouble("pitch"), it.getDouble("roll")) }
        assertEquals(MeshBindingProof.computeMeshDigest(shape, pose, 80, Evidence.landmarksHash(sent)), f.getString("meshDigest"))
        assertEquals(
            MeshBindingProof.computeBindingProof("00112233", "hash1", f.getString("meshDigest")),
            f.getString("bindingProof"),
        )
    }

    @Test
    fun `without the contract the package is as before`() {
        val f = build(listOf(fit(1)), listOf(mesh(1)), uploaded = 2, evidence = false).getJSONArray("frames").getJSONObject(0)
        assertEquals("mediapipe_zyx_v2", f.getString("poseNormalizationMethod"))
        assertFalse(f.has("landmarks"))
        assertFalse(f.has("transformMatrix"))
        assertEquals(9.0, f.getJSONObject("pose").getDouble("yaw"), 0.0)
        assertEquals(MeshBindingProof.computeMeshDigest(fit(1).shapeParams, HeadPose(9.0, 9.0, 9.0), 80), f.getString("meshDigest"))
    }

    @Test
    fun `digest frames are read from the package in array order`() {
        val pkg = build(listOf(fit(3), fit(1)), listOf(mesh(1), mesh(3)), uploaded = 4)
        val frames = EvidenceDigest.frames(pkg)
        assertEquals(listOf(1, 3), frames.map { it.frameIndex })
        assertEquals("hash3", frames[1].frameHash)
    }

    // ─── Section 6: colour sequence ─────────────────────────────────────

    private class FakeHost : ColourTintHost {
        val tints = mutableListOf<String?>()
        var brightnessHeld = false
        var awb = false
        override fun showTint(colour: String?, opacity: Double) { tints += colour }
        override fun holdFullBrightness(): Double { brightnessHeld = true; return 1.0 }
        override fun releaseFullBrightness() { brightnessHeld = false }
        override fun lockWhiteBalance(locked: Boolean): Boolean { awb = locked; return true }
    }

    private val seq = EvidenceConfig.ColourSequence("cs_1", listOf("#3D6BFF", "#FFD23F", "#B04DFF"))
    private val ms = 1_000_000L

    /** Capture clock = frame time in ms + 1000 (the camera opened 1 s before nanoTime 0). */
    private fun player(host: FakeHost) = ColourSequencePlayer(seq) { it / ms + 1000 }.also { it.host = host }

    private fun ColourSequencePlayer.run(fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t <= toMs) { advance(t * ms); t += 16 }
    }

    @Test
    fun `the sequence plays in order after the lead-in and records what showed`() {
        val host = FakeHost()
        val p = player(host)
        p.play(0)
        assertTrue(host.brightnessHeld)
        assertTrue(host.awb)
        p.run(0, 3000)
        assertTrue(p.finished)
        val r = p.record
        assertTrue(r.completed)
        assertNull(r.interruptedReason)
        assertEquals(listOf("#3D6BFF", "#FFD23F", "#B04DFF"), r.segments.map { it.colour })
        // First colour at the first frame past the 300 ms lead-in, on the capture clock.
        assertEquals(1304L, r.segments[0].startMs)
        r.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        r.segments.forEach { assertTrue(it.endMs - it.startMs in 600L..620L) }
        assertEquals(1.0, r.screenBrightness!!, 0.0)
        assertTrue(r.awbLocked)
        // Tint off, brightness and white balance released at the end.
        assertNull(host.tints.last())
        assertFalse(host.brightnessHeld)
        assertFalse(host.awb)
        assertEquals("#3D6BFF", r.toJson().getJSONArray("segments").getJSONObject(0).getString("colour"))
    }

    @Test
    fun `a gate pause drops the partly shown colour and the replay resumes at it`() {
        val host = FakeHost()
        val p = player(host)
        p.play(0)
        p.run(0, 1100) // colour 0 done at ~904, colour 1 showing
        p.interrupt()
        assertFalse(p.isPlaying)
        assertEquals(listOf("#3D6BFF"), p.record.segments.map { it.colour })
        assertFalse(p.record.completed)
        assertEquals("gate_pause", p.record.interruptedReason)
        assertEquals(1, p.nextIndex)
        // Resumes with a fresh lead-in, at colour 1.
        p.play(2000 * ms)
        p.run(2000, 4000)
        assertTrue(p.record.completed)
        assertNull(p.record.interruptedReason)
        val colours = p.record.segments.map { it.colour }
        assertEquals(listOf("#3D6BFF", "#FFD23F", "#B04DFF"), colours)
        // The server's order rule: segment i is issued[i % n].
        colours.forEachIndexed { i, c -> assertEquals(seq.colours[i % 3], c) }
        assertEquals(2, p.runs)
    }

    @Test
    fun `a pause during the lead-in costs no play, and a dropped window clears the record`() {
        val p = player(FakeHost())
        p.play(0)
        p.run(0, 200)
        p.interrupt()
        assertEquals(0, p.runs)
        assertTrue(p.record.segments.isEmpty())
        p.play(500 * ms)
        p.run(500, 1500)
        assertEquals(1, p.runs)
        p.resetForNewWindow()
        assertTrue(p.record.segments.isEmpty())
        assertEquals(0, p.nextIndex)
        assertEquals("cs_1", p.record.sequenceId)
        assertFalse(p.isPlaying)
    }

    @Test
    fun `no colour shows before the capture clock starts`() {
        val p = ColourSequencePlayer(seq) { null }.also { it.host = FakeHost() }
        p.play(0)
        p.run(0, 1000)
        assertTrue(p.record.segments.isEmpty())
        assertTrue(p.isPlaying)
    }

    @Test
    fun `the scan holds while the colours play`() {
        val c = ScanController(totalMs = 2000)
        c.start(0, manual = false)
        c.require(2250)
        var t = 0L
        while (t < 2200) { t += 50; assertNull(c.tick(t, true)) }
        assertEquals(ScanController.Event.COMPLETED, (1..10).firstNotNullOfOrNull { t += 50; c.tick(t, true) })
        // Never below the scan's own length.
        val d = ScanController(totalMs = 2000)
        d.start(0, false)
        d.require(0)
        assertEquals(2000L, d.targetMs)
    }

    // ─── Section 7: closer step ─────────────────────────────────────────

    @Test
    fun `the closer step times out after 4 s when the face never comes closer`() {
        val c = CloserStepController(farEye = 100.0, minScale = 1.25, startMs = 0)
        assertEquals(125.0, c.threshold, 1e-9)
        var t = 0L
        var last: CloserStepController.Tick
        do {
            last = c.tick(t, 110.0, true, 0, true)
            assertFalse(last.bank)
            assertTrue(last.needsCloser)
            t += 66
        } while (!last.done)
        assertTrue(t in 4000L..4100L)
    }

    @Test
    fun `near frames bank only once close enough, gate passing and held still`() {
        val c = CloserStepController(100.0, 1.25, 0)
        assertFalse(c.tick(3000, 130.0, true, 0, true).bank) // just arrived
        assertFalse(c.tick(3100, 130.0, false, 0, true).bank) // gate fails: hold restarts
        assertFalse(c.tick(3200, 130.0, true, 0, true).bank)
        val t = c.tick(3650, 130.0, true, 0, true)
        assertTrue(t.bank)
        assertFalse(t.needsCloser)
        // Reached at 3 s: the hold budget (3 s) runs past the 4 s timeout.
        assertFalse(c.tick(5500, 130.0, true, 1, true).done)
        assertTrue(c.tick(5600, 130.0, true, 3, true).done)
        assertTrue(CloserStepController(100.0, 1.25, 0).also { it.tick(3000, 130.0, true, 0, true) }.tick(6000, 130.0, true, 1, true).done)
        // Dipping just below the threshold keeps "Hold still" (no flicker) but stops banking.
        val d = CloserStepController(100.0, 1.25, 0)
        d.tick(0, 130.0, true, 0, true)
        val dip = d.tick(500, 121.0, true, 0, true)
        assertFalse(dip.needsCloser)
        assertFalse(dip.bank)
        // Out of frame budget: done.
        assertTrue(CloserStepController(100.0, 1.25, 0).tick(10, 130.0, true, 0, budgetLeft = false).done)
    }

    @Test
    fun `the closer record comes from the uploaded frames' own mesh`() {
        val far = (5..9).map { CloserResolver.MeshedFrame(it, 0.10) }
        val near = listOf(
            CloserResolver.MeshedFrame(10, 0.12), // not close enough
            CloserResolver.MeshedFrame(11, 0.13),
            CloserResolver.MeshedFrame(12, 0.135),
            CloserResolver.MeshedFrame(13, 0.14),
            CloserResolver.MeshedFrame(14, 0.20), // after the step ended
        )
        val r = CloserResolver.resolve(far + near, startIndex = 10, endIndex = 14, minScale = 1.25, startedAtMs = 4120, endedAtMs = 5480)
        assertTrue(r.completed)
        assertEquals(listOf(7, 8, 9), r.farFrames)
        assertEquals(listOf(11, 12, 13), r.nearFrames)
        assertEquals(1.35, r.achievedScale!!, 1e-9)
        assertNull(r.skippedReason)
        val json = r.toJson()
        assertEquals(1.35, json.getDouble("achieved_scale"), 0.0)
        assertEquals(4120L, json.getLong("started_at_ms"))
        assertTrue(json.isNull("skipped_reason"))

        val timeout = CloserResolver.resolve(far + near.take(2), 10, 14, 1.25, 4120, 8120)
        assertFalse(timeout.completed)
        assertEquals("timeout", timeout.skippedReason)
        assertTrue(timeout.farFrames.isEmpty() && timeout.nearFrames.isEmpty())
        // Too few far frames with a mesh: nothing to compare.
        assertEquals("timeout", CloserResolver.resolve(far.take(2) + near, 10, 14, 1.25, null, null).skippedReason)
    }

    @Test
    fun `eye distance uses the irises and the image aspect`() {
        val pts = MutableList(478) { LandmarkPoint(0.5, 0.5, 0.0) }
        pts[468] = LandmarkPoint(0.4, 0.5, 0.0)
        pts[473] = LandmarkPoint(0.6, 0.5, 0.0)
        assertEquals(0.2, Evidence.eyeDistance(pts, 4.0 / 3.0)!!, 1e-9)
        pts[473] = LandmarkPoint(0.4, 0.65, 0.0)
        assertEquals(0.2, Evidence.eyeDistance(pts, 4.0 / 3.0)!!, 1e-9)
        assertNull(Evidence.eyeDistance(pts.take(100), 1.0))
    }

    // ─── Section 2: metadata.evidence ───────────────────────────────────

    @Test
    fun `the upload block has the contract, the clock and an honest capability`() {
        val cap = EvidenceCapability.current(meshAvailable = false, guided = true, playIntegrity = true)
        assertFalse(cap.landmarks)
        assertNull(cap.poseMethod)
        assertFalse(cap.closerStep)
        val json = EvidenceUploadBlock(
            EvidenceCapability.current(meshAvailable = true, guided = true, playIntegrity = true),
            colour = ColourEvidence("cs_1", completed = false, interruptedReason = "gate_pause"),
            closer = CloserEvidence.skipped(CloserEvidence.DISABLED),
        ).toJson()
        assertEquals("evidence-v1", json.getString("contract"))
        assertEquals("capture_monotonic_ms", json.getString("clock"))
        assertEquals("matrix_zyx_v3", json.getJSONObject("capability").getString("pose_method"))
        assertTrue(json.getJSONObject("capability").getBoolean("closer_step"))
        assertEquals("disabled", json.getJSONObject("closer").getString("skipped_reason"))
        assertTrue(json.getJSONObject("colour").isNull("screen_brightness"))
        assertFalse(json.has("binding"))
        assertNotNull(json.getJSONObject("colour").getJSONArray("segments"))
    }

    @Test
    fun `camera timestamps map to the capture clock from either clock base`() {
        val exposure = com.usesense.sdk.capture.FrameCaptureManager.Companion
        // Realtime base: exposure 120 ms before now.
        assertEquals(9_880L, exposure.exposureElapsedMs(9_880_000_000L, realtimeNs = 10_000_000_000L, monotonicNs = 4_000_000_000L))
        // Monotonic base (System.nanoTime), realtime 6 s ahead of it.
        assertEquals(9_880L, exposure.exposureElapsedMs(3_880_000_000L, realtimeNs = 10_000_000_000L, monotonicNs = 4_000_000_000L))
        // Neither (or not set): the caller falls back to the delivery time.
        assertNull(exposure.exposureElapsedMs(0L, 10_000_000_000L, 4_000_000_000L))
        assertNull(exposure.exposureElapsedMs(50_000_000_000L, 10_000_000_000L, 4_000_000_000L))
    }
}
