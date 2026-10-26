package com.usesense.sdk.flows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for location capture (address ladder rung 0).
 *
 * These guard a frozen wire contract that five clients implement
 * independently, so they assert the exact payload rather than "something
 * reasonable". The rule that matters most is that no failure path terminates
 * the step: a denied permission still submits and still advances.
 */
class LocationCaptureTest {

    private val lagos = LocationFix(latitude = 6.4281, longitude = 3.4219, accuracyM = 24f, attested = true)
    private val descriptors = mapOf<String, Any>(
        "street_name" to "Adeola Odeku Street",
        "locality" to "Victoria Island",
    )

    // ── Usable fix ───────────────────────────────────────────────────────────

    @Test
    fun `accepts a normal coordinate`() {
        assertTrue(LocationCapture.isUsableFix(6.4281, 3.4219))
    }

    @Test
    fun `rejects null island`() {
        // The device failed and reported zeroes. Submitting it would mint a
        // Place in the Gulf of Guinea inside a registry shared across every
        // customer, and a poisoned Place reaches every future subject who
        // resolves onto it.
        assertFalse(LocationCapture.isUsableFix(0.0, 0.0))
    }

    @Test
    fun `rejects out of range and non finite coordinates`() {
        assertFalse(LocationCapture.isUsableFix(91.0, 3.0))
        assertFalse(LocationCapture.isUsableFix(6.0, 181.0))
        assertFalse(LocationCapture.isUsableFix(Double.NaN, 3.4219))
        assertFalse(LocationCapture.isUsableFix(Double.POSITIVE_INFINITY, 3.4219))
    }

    // ── Payload ──────────────────────────────────────────────────────────────

    @Test
    fun `sends coordinate accuracy attestation and rung`() {
        val out = LocationCapture.buildInputs(lagos, descriptors, CaptureRung.AT_THE_DOOR)
        assertEquals(6.4281, out["latitude"])
        assertEquals(3.4219, out["longitude"])
        assertEquals(24, out["accuracy_m"])
        assertEquals(true, out["attested"])
        assertEquals("at_the_door", out["rung"])
        assertEquals("Adeola Odeku Street", out["street_name"])
    }

    @Test
    fun `rounds accuracy to whole metres`() {
        val fix = lagos.copy(accuracyM = 23.7419f)
        assertEquals(24, LocationCapture.buildInputs(fix, emptyMap(), null)["accuracy_m"])
    }

    @Test
    fun `omits accuracy when the device did not report one`() {
        val fix = lagos.copy(accuracyM = null)
        assertNull(LocationCapture.buildInputs(fix, emptyMap(), null)["accuracy_m"])
    }

    @Test
    fun `defaults the rung when the step did not name one`() {
        assertEquals("at_the_door", LocationCapture.buildInputs(lagos, emptyMap(), null)["rung"])
    }

    @Test
    fun `carries the frontage photo when there is one`() {
        val out = LocationCapture.buildInputs(lagos, emptyMap(), CaptureRung.AT_THE_DOOR, "doc_1")
        assertEquals("doc_1", out["frontage_document_id"])
    }

    @Test
    fun `a mocked location is submitted as unattested rather than rejected`() {
        // An unattested fix is a weaker evidence class, not a failure.
        // Rejecting it would push honest subjects on rooted or unusual
        // handsets off the ladder entirely.
        val mocked = lagos.copy(attested = false)
        val out = LocationCapture.buildInputs(mocked, emptyMap(), CaptureRung.AT_THE_DOOR)
        assertEquals(false, out["attested"])
        assertEquals("at_the_door", out["rung"])
    }

    // ── No position is not a failure ─────────────────────────────────────────

    @Test
    fun `no fix submits descriptors alone at a lower rung`() {
        val out = LocationCapture.buildInputs(null, descriptors, CaptureRung.AT_THE_DOOR)
        assertNull(out["latitude"])
        assertNull(out["longitude"])
        assertEquals("spoken_description", out["rung"])
        assertEquals("Victoria Island", out["locality"])
    }

    @Test
    fun `null island is treated as no fix at all`() {
        val fix = LocationFix(latitude = 0.0, longitude = 0.0, accuracyM = 5f)
        val out = LocationCapture.buildInputs(fix, emptyMap(), CaptureRung.AT_THE_DOOR)
        assertNull(out["latitude"])
        assertEquals("spoken_description", out["rung"])
    }

    @Test
    fun `never claims a rung higher than it performed`() {
        val out = LocationCapture.buildInputs(null, emptyMap(), CaptureRung.AGENT_VISIT)
        assertEquals("spoken_description", out["rung"])
    }

    // ── Status copy ──────────────────────────────────────────────────────────

    @Test
    fun `denied and unavailable both say the subject can continue`() {
        assertTrue(LocationCapture.statusText(LocationCaptureState.DENIED)!!.contains("still continue"))
        assertTrue(LocationCapture.statusText(LocationCaptureState.UNAVAILABLE)!!.contains("still continue"))
    }

    @Test
    fun `reports the accuracy achieved not the one requested`() {
        assertTrue(LocationCapture.statusText(LocationCaptureState.READY, 137f)!!.contains("137 m"))
    }

    // ── Decoding the action ──────────────────────────────────────────────────

    @Test
    fun `decodes a location capture`() {
        val raw = JSONObject(
            """
            {
              "kind": "capture",
              "capture": "location",
              "toolId": "address_capture",
              "locationRung": "at_the_door",
              "locationAccuracyTargetM": 150,
              "locationMaxWaitMs": 25000,
              "requireFrontagePhoto": true,
              "requireAttestation": true,
              "descriptorFields": [{ "key": "street_name", "type": "text", "label": "Street" }]
            }
            """.trimIndent()
        )
        val action = PendingAction.decode(raw)
        assertTrue(action is PendingAction.CaptureLocation)
        val spec = (action as PendingAction.CaptureLocation).spec
        assertEquals(CaptureRung.AT_THE_DOOR, spec.rung)
        assertEquals(25000, spec.maxWaitMs)
        assertEquals(150.0, spec.accuracyTargetM!!, 0.001)
        assertTrue(spec.requireFrontagePhoto)
        assertEquals(1, spec.descriptorFields.size)
        assertEquals("street_name", spec.descriptorFields[0].key)
    }

    @Test
    fun `decoding survives an empty action`() {
        // Every field is optional on the wire. A client that meets an action
        // with nothing set must render defaults, not fall over: this is a
        // frozen contract and the alternative is a crash mid-verification.
        val action = PendingAction.decode(JSONObject("""{"kind":"capture","capture":"location"}"""))
        val spec = (action as PendingAction.CaptureLocation).spec
        assertNull(spec.rung)
        assertEquals(20_000, spec.maxWaitMs)
        assertFalse(spec.requireAttestation)
        assertTrue(spec.descriptorFields.isEmpty())
    }

    @Test
    fun `an unrecognised rung is ignored rather than fatal`() {
        // The server takes the weaker of its own ceiling and whatever we
        // report, so falling back to null costs nothing and cannot overstate.
        val raw = JSONObject("""{"kind":"capture","capture":"location","locationRung":"teleportation"}""")
        val spec = (PendingAction.decode(raw) as PendingAction.CaptureLocation).spec
        assertNull(spec.rung)
    }

    @Test(expected = FlowError::class)
    fun `an unknown capture variant still throws`() {
        // The reason the whole downgrade exists: this decoder is strict, so a
        // build that has not declared the capability must never be sent a kind
        // it does not know.
        PendingAction.decode(JSONObject("""{"kind":"capture","capture":"retina_scan"}"""))
    }

    // ── Frontage photo ───────────────────────────────────────────────────────

    @Test
    fun `a frontage photo travels with the position`() {
        val out = LocationCapture.buildInputs(lagos, descriptors, CaptureRung.AT_THE_DOOR, "doc_1")
        assertEquals("doc_1", out["frontage_document_id"])
        assertEquals(6.4281, out["latitude"])
    }

    @Test
    fun `no frontage photo means the key is absent, not empty`() {
        val out = LocationCapture.buildInputs(lagos, descriptors, CaptureRung.AT_THE_DOOR, null)
        assertNull(out["frontage_document_id"])
        assertNull(LocationCapture.buildInputs(lagos, descriptors, CaptureRung.AT_THE_DOOR, "")["frontage_document_id"])
    }

    @Test
    fun `the photo does not change the rung either way`() {
        // Supporting evidence about the dwelling, not about how the position
        // was established. A subject who could not take it completes at the
        // same rung, one piece of evidence lighter.
        val withPhoto = LocationCapture.buildInputs(lagos, emptyMap(), CaptureRung.AT_THE_DOOR, "doc_1")
        val without = LocationCapture.buildInputs(lagos, emptyMap(), CaptureRung.AT_THE_DOOR, null)
        assertEquals(without["rung"], withPhoto["rung"])
    }
}