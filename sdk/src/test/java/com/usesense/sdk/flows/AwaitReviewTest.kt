package com.usesense.sdk.flows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run held for manual review: the server returns state `awaiting_review`
 * with pending action `{ kind: "await_review", stepId, instructions }`
 * (watchtower modules/flows/runtime.ts, hostedView() in endpoints.ts). The
 * view must decode, the run must count as settled, and the result must carry
 * state AWAITING_REVIEW. Unknown kinds must not fail the view decode.
 */
class AwaitReviewTest {

    /** The exact shape hostedView() returns for a review hold. */
    private val serverPayload = """
        {
          "flowRun": {
            "id": "fr_01JREVIEW",
            "state": "awaiting_review",
            "outcome": null,
            "cursorStepId": "review_1",
            "pendingAction": {
              "kind": "await_review",
              "stepId": "review_1",
              "instructions": "Check the selfie against the ID photo."
            },
            "environment": "production",
            "lastCaptureError": null
          },
          "definitionSteps": [
            { "id": "face_1", "type": "verification", "label": "Face", "toolId": "liveness" },
            { "id": "review_1", "type": "review", "label": "Manual review" }
          ],
          "stepRuns": [],
          "branding": { "display_name": "Acme", "primary_color": "#123456" }
        }
    """.trimIndent()

    private fun view(state: String, action: String?, outcome: String? = null): FlowRunView = FlowRunView.decode(
        JSONObject(
            """
            { "flowRun": { "id": "fr_1", "state": "$state",
                           "outcome": ${outcome?.let { "\"$it\"" } ?: "null"},
                           "cursorStepId": null, "environment": "production",
                           "pendingAction": ${action ?: "null"} },
              "definitionSteps": [], "stepRuns": [], "branding": null }
            """.trimIndent(),
        ),
    )

    @Test
    fun `decodes the exact server payload for a review hold`() {
        val v = FlowRunView.decode(JSONObject(serverPayload))
        assertEquals("fr_01JREVIEW", v.id)
        assertEquals(FlowRunState.AWAITING_REVIEW, v.state)
        assertNull(v.outcome)
        assertEquals(
            PendingAction.AwaitReview(stepId = "review_1", instructions = "Check the selfie against the ID photo."),
            v.pendingAction,
        )
        assertEquals("Acme", v.branding?.displayName)
    }

    @Test
    fun `a review hold is settled and reports awaiting_review with the outcome`() {
        val v = FlowRunView.decode(JSONObject(serverPayload))
        assertTrue(FlowRunSettlement.isAwaitingReview(v))
        assertTrue(FlowRunSettlement.isSettled(v))
        assertEquals(FlowRunResult("fr_01JREVIEW", FlowRunState.AWAITING_REVIEW, null), FlowRunSettlement.resultFor(v))

        val withOutcome = view("awaiting_review", """{ "kind": "await_review", "stepId": "r" }""", "MANUAL_REVIEW")
        assertEquals(FlowOutcome.MANUAL_REVIEW, FlowRunSettlement.resultFor(withOutcome).outcome)
    }

    @Test
    fun `either signal alone settles the run`() {
        val stateOnly = view("awaiting_review", null)
        assertTrue(FlowRunSettlement.isSettled(stateOnly))
        assertEquals(FlowRunState.AWAITING_REVIEW, FlowRunSettlement.resultFor(stateOnly).state)

        val actionOnly = view("stalled", """{ "kind": "await_review", "stepId": "r" }""")
        assertTrue(FlowRunSettlement.isSettled(actionOnly))
        assertEquals(FlowRunState.AWAITING_REVIEW, FlowRunSettlement.resultFor(actionOnly).state)
    }

    @Test
    fun `other runs keep their settlement`() {
        assertTrue(FlowRunSettlement.isSettled(view("completed", null, "APPROVE")))
        assertEquals(FlowRunState.COMPLETED, FlowRunSettlement.resultFor(view("completed", null, "APPROVE")).state)
        assertTrue(FlowRunSettlement.isSettled(view("cancelled", null)))
        assertFalse(FlowRunSettlement.isSettled(view("stalled", """{ "kind": "capture", "capture": "face" }""")))
        assertFalse(FlowRunSettlement.isSettled(view("in_progress", null)))
    }

    @Test
    fun `review screen uses the MANUAL_REVIEW copy keys, never the reviewer note`() {
        val (title, body) = FlowRunSettlement.reviewScreenCopy(null)
        assertEquals("Under review", title)
        assertEquals("Your details are being reviewed.", body)

        val copy = FlowCopy(result = ResultCopy(reviewTitle = "In review", reviewBody = "We will email you."))
        assertEquals("In review" to "We will email you.", FlowRunSettlement.reviewScreenCopy(copy))

        val v = FlowRunView.decode(JSONObject(serverPayload))
        val shown = FlowRunSettlement.reviewScreenCopy(v.branding?.copy).toList()
        assertFalse(shown.any { it.contains("selfie against the ID photo") })
    }

    @Test
    fun `an unknown kind decodes as Unsupported instead of failing the view`() {
        val v = view("stalled", """{ "kind": "capture_voice", "stepId": "s" }""")
        assertEquals("fr_1", v.id)
        val action = v.pendingAction as PendingAction.Unsupported
        assertEquals("capture_voice", action.kind)
        assertFalse(FlowRunSettlement.isSettled(v))
    }

    @Test
    fun `an unknown capture variant and a malformed info action decode as Unsupported`() {
        val variant = view("stalled", """{ "kind": "capture", "capture": "selfie_video" }""").pendingAction
        assertEquals("capture:selfie_video", (variant as PendingAction.Unsupported).kind)

        val info = view("stalled", """{ "kind": "info", "title": "Hi" }""").pendingAction
        assertEquals("info", (info as PendingAction.Unsupported).kind)
    }

    @Test
    fun `unsupported step copy has defaults and honours overrides`() {
        assertEquals(
            Triple("Something went wrong", "This step could not be shown here. Try again, or come back later.", "Try again"),
            FlowRunSettlement.unsupportedStepCopy(null),
        )
        val copy = FlowCopy.decode(
            JSONObject("""{ "errors": { "generic": "Oops", "unsupported_step": "Update the app." }, "buttons": { "try_again": "Retry" } }"""),
        )
        assertEquals(Triple("Oops", "Update the app.", "Retry"), FlowRunSettlement.unsupportedStepCopy(copy))
        val merged = mergeCopy(FlowCopy(errors = ErrorsCopy(unsupportedStep = "High")), copy)
        assertEquals("High", merged?.errors?.unsupportedStep)
    }
}
