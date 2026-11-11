package com.usesense.sdk.ui.theme

import com.usesense.sdk.flows.CaptureCopy
import com.usesense.sdk.flows.FlowCopy
import com.usesense.sdk.flows.mergeCopy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** BRAND-v1 section 5: `copy.capture`, shipped English defaults, blank overrides and placeholders. */
class CaptureCopyTest {

    /** strings.xml, read as the shipped English (the defaults must stay byte-identical). */
    private val shippedXml: Map<String, String> by lazy {
        val f = listOf("src/main/res/values/strings.xml", "sdk/src/main/res/values/strings.xml").map(::File).first { it.exists() }
        Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Fake Android lookups: ids are indexes into the strings.xml names; `%s` takes the app. */
    private val names by lazy { shippedXml.keys.toList() }
    private fun id(name: String) = names.indexOf(name).also { require(it >= 0) { name } }
    private fun strings(copy: CaptureCopy?, app: String = "Acme Bank") = CaptureStrings(
        copy = copy,
        app = app,
        resourceName = { names.getOrNull(it) },
        shipped = { i, a -> shippedXml.getValue(names[i]).replace("\\'", "'").replace("%s", a) },
    )

    private fun copy(json: String) = FlowCopy.decode(JSONObject(json)).capture

    @Test fun `no copy keeps the shipped English`() {
        val s = strings(null)
        assertEquals("Take a quick selfie", s.text(id("usesense_primer_title")))
        assertEquals("Leave verification?", s.text(id("usesense_leave_title")))
        assertEquals("Thanks. You can carry on in Acme Bank.", s.text(id("usesense_page_approved_body")))
        assertEquals("Turn your head to the left", s.text(id("usesense_xc_turn_left")))
        assertEquals(
            listOf("Good light on your face", "Take off sunglasses, hats and masks", "Only you in the picture"),
            s.list(CaptureCopyKeys.PRIMER_SELFIE_TIPS, listOf(id("usesense_primer_tip_light"), id("usesense_primer_tip_uncovered"), id("usesense_primer_tip_alone"))),
        )
    }

    @Test fun `overrides decode in camelCase and snake_case`() {
        val c = copy(
            """{"capture":{
                 "primer":{"selfieTitle":"Quick selfie for {app}","two_steps_title":"Two steps","selfie_tips":["Light","No hats"]},
                 "extra_check":{"headTurn":"Look {side}","head_turn_next":"Next: {side}"},
                 "leave":{"keepGoing":"Stay"}
               }}""",
        )!!
        assertEquals("Quick selfie for {app}", c.text("primer.selfieTitle"))
        assertEquals("Two steps", c.text("primer.twoStepsTitle"))
        assertEquals(listOf("Light", "No hats"), c.list("primer.selfieTips"))
        assertEquals("Look {side}", c.text("extraCheck.headTurn"))
        assertEquals("Next: {side}", c.text("extraCheck.headTurnNext"))
        val s = strings(c)
        assertEquals("Quick selfie for Acme Bank", s.text(id("usesense_primer_title")))
        assertEquals("Stay", s.text(id("usesense_keep_going")))
        assertEquals("Look left", s.text(id("usesense_xc_turn_left")))
        assertEquals("Look up", s.text(id("usesense_xc_turn_up")))
        assertEquals("Next: right", s.text(id("usesense_xc_now_right")))
        assertEquals(listOf("Light", "No hats"), s.list(CaptureCopyKeys.PRIMER_SELFIE_TIPS, emptyList()))
        // Not overridden: shipped English.
        assertEquals("Leave verification?", s.text(id("usesense_leave_title")))
    }

    @Test fun `blank and missing overrides fall back to the shipped string`() {
        val c = copy("""{"capture":{"leave":{"title":"   ","body":""},"primer":{"selfieTips":["", " "]}}}""")
        val s = strings(c)
        assertEquals("Leave verification?", s.text(id("usesense_leave_title")))
        assertEquals("You're nearly done. If you leave, you'll need to start again.", s.text(id("usesense_leave_body")))
        assertEquals(listOf("Good light on your face"), s.list(CaptureCopyKeys.PRIMER_SELFIE_TIPS, listOf(id("usesense_primer_tip_light"))))
    }

    @Test fun `placeholders - app and side fill, unknown ones stay literal`() {
        assertEquals("Hi Acme {user} {side}", CaptureStrings.fill("Hi {app} {user} {side}", "Acme", null))
        assertEquals("left / Acme", CaptureStrings.fill("{side} / {app}", "Acme", "left"))
        val s = strings(copy("""{"capture":{"result":{"approvedBody":"Back to {app}, {name}."}}}"""), app = "Night Pay")
        assertEquals("Back to Night Pay, {name}.", s.text(id("usesense_page_approved_body")))
    }

    @Test fun `explicit keys separate a shared resource`() {
        val s = strings(copy("""{"capture":{"result":{"retryTitle":"One more go","rejectedTitle":"Not verified"},"upload":{"retry":"Send again"}}}"""))
        val title = id("usesense_page_rejected_title")
        assertEquals("Not verified", s.text(title))
        assertEquals("One more go", s.text(title, CaptureCopyKeys.RESULT_RETRY_TITLE))
        assertEquals("Send again", s.text(id("usesense_page_try_again"), CaptureCopyKeys.UPLOAD_RETRY))
        assertEquals("Try again", s.text(id("usesense_page_try_again")))
    }

    @Test fun `code copy beats dashboard copy per key, a blank code key lets the dashboard through`() {
        val code = FlowCopy(capture = CaptureCopy(strings = mapOf("leave.title" to "Code title", "leave.body" to " ")))
        val dash = FlowCopy(capture = CaptureCopy(strings = mapOf("leave.title" to "Dash title", "leave.body" to "Dash body", "leave.leave" to "Go")))
        val merged = mergeCopy(code, dash)!!.capture!!
        assertEquals("Code title", merged.text("leave.title"))
        assertEquals("Dash body", merged.text("leave.body"))
        assertEquals("Go", merged.text("leave.leave"))
        assertNull(mergeCopy(FlowCopy(), FlowCopy())!!.capture)
    }

    @Test fun `every mapped resource exists in strings xml`() {
        CaptureCopyKeys.byResource.keys.forEach { assertTrue("missing string $it", it in shippedXml) }
    }

    /** Section 5 keys. Two have no Android string (no primer Cancel, no separate "ready" line). */
    @Test fun `every section 5 key is used on Android or listed as having no Android string`() {
        val spec = mapOf(
            "primer" to "selfieTitle selfieBody selfieTips start cancel twoStepsTitle twoStepsBody twoStepsTips",
            "guidance" to "ready centre closer back straight dark darkHint bright brightHint blur eyes onePerson showFace covered holdStill shutterHint",
            "extraCheck" to "sheetTitle sheetBody continue headTurn headTurnNext followDot speakDigits lookCentre tryNext done pausedTurnSide pausedLookCentre",
            "upload" to "title caption failedTitle failedBody retry",
            "result" to "approvedTitle approvedBody reviewTitle reviewBody rejectedTitle rejectedBody retryTitle retryBody done tryAgain notNow",
            "errors" to "cameraDeniedTitle cameraDeniedBody openSettings cameraStoppedTitle cameraStoppedBody micTitle micBody useHeadMovement offlineTitle offlineBody expiredTitle expiredBody startAgain errorTitle errorBody",
            "leave" to "title body leave keepGoing",
        ).flatMap { (g, keys) -> keys.split(' ').map { "$g.$it" } }
        val notOnAndroid = setOf("primer.cancel", "guidance.ready", "extraCheck.tryNext")
        val used = CaptureCopyKeys.byResource.values.map { it.key }.toSet() + setOf(
            CaptureCopyKeys.PRIMER_SELFIE_TIPS, CaptureCopyKeys.PRIMER_TWO_STEPS_TIPS,
            CaptureCopyKeys.RESULT_RETRY_TITLE, CaptureCopyKeys.UPLOAD_RETRY,
        )
        spec.forEach { k -> assertTrue("unmapped $k", k in used || k in notOnAndroid) }
        assertEquals(7, CaptureCopy.GROUPS.size)
        CaptureCopyKeys.byResource.values.forEach { assertTrue(it.key, it.key.substringBefore('.') in CaptureCopy.GROUPS) }
    }

    @Test fun `the next side's title is headTurnNext, not tryNext, with a bare side word`() {
        val s = strings(copy("""{"capture":{"extraCheck":{"headTurnNext":"Next, {side}","tryNext":"Let's try the next one"}}}"""))
        assertEquals("Next, left", s.text(id("usesense_xc_now_left")))
        assertEquals("Next, down", s.text(id("usesense_xc_now_down")))
        // tryNext (after a missed step) never replaces a step title.
        val onlyTryNext = strings(copy("""{"capture":{"extraCheck":{"tryNext":"Let's try the next one"}}}"""))
        assertEquals("Now to the right", onlyTryNext.text(id("usesense_xc_now_right")))
        // Shipped defaults for the paused line (BRAND-v1 section 5).
        val none = strings(null)
        assertEquals("Then keep turning to the left", none.text(id("usesense_xc_paused_turn_left")))
        assertEquals("Then keep tilting your head up", none.text(id("usesense_xc_paused_turn_up")))
        assertEquals("Then look straight ahead", none.text(id("usesense_xc_paused_turn_centre")))
        assertEquals("Then keep turning", none.text(id("usesense_xc_paused_turn_detail")))
    }
}
