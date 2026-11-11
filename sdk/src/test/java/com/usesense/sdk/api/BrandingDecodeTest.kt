package com.usesense.sdk.api

import com.usesense.sdk.api.models.CreateSessionResponse
import com.usesense.sdk.api.models.apiMoshi
import com.usesense.sdk.flows.FlowRunView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Branding on the wire: every field nullable, the object itself optional, no invented defaults. */
class BrandingDecodeTest {

    private val adapter = apiMoshi().adapter(CreateSessionResponse::class.java)

    private fun session(brandingJson: String?): String {
        val branding = brandingJson?.let { ""","branding": $it""" } ?: ""
        return """
            {"session_id":"sess_1","session_token":"tok","expires_at":"2026-11-07T12:00:00.000Z","nonce":"n",
             "policy":{"requires_audio":false,"requires_stepup":false},
             "upload":{"max_frames":30,"target_fps":10,"capture_duration_ms":2500}$branding}
        """.trimIndent()
    }

    @Test
    fun `session without branding decodes with none`() {
        assertNull(adapter.fromJson(session(null))!!.branding)
        assertNull(adapter.fromJson(session("null"))!!.branding)
    }

    @Test
    fun `session branding decodes partial fields and appearance`() {
        val r = adapter.fromJson(
            session("""{"display_name":"XTransfer","logo_url":null,"primary_color":"#FF6A00","appearance":{"colors":{"foreground":"#111111"},"shape":{"radius":12}}}"""),
        )!!
        val server = r.branding!!.toServerBranding()
        assertEquals("XTransfer", server.displayName)
        assertNull(server.logoUrl)
        assertEquals("#FF6A00", server.primaryColor)
        assertEquals("#111111", server.appearance?.colors?.foreground)
        assertEquals(12, server.appearance?.shape?.radius)
    }

    @Test
    fun `flow run branding has no invented defaults`() {
        val view = FlowRunView.decode(
            JSONObject("""{"flowRun":{"id":"fr_1","state":"in_progress"},"branding":{}}"""),
        )
        val b = view.branding!!
        // Previously "UseSense" and "#4F7CFF" were invented here and counted as set.
        assertNull(b.displayName)
        assertNull(b.primaryColor)
        assertNull(b.logoUrl)
    }

    @Test
    fun `remote hosted link branding keeps appearance and copy`() {
        val wrapper = apiMoshi().adapter(com.usesense.sdk.api.models.RemoteSessionDataWrapper::class.java).fromJson(
            """{"session":{"id":"rs_1","status":"pending"},
                "branding":{"display_name":"Acme Bank","primary_color":"#1F7A4D",
                  "appearance":{"mode":"light","colors":{"surface":"#F3F6F4"},"capture":{"challenge":"#FF4FA3","contour_start":"#7A96FF"}},
                  "copy":{"capture":{"leave":{"keep_going":"Stay"},"primer":{"selfieTips":["Light","No hats"]}}}}}""",
        )!!
        val server = wrapper.branding!!.toServerBranding()
        assertEquals("Acme Bank", server.displayName)
        assertEquals(com.usesense.sdk.flows.FlowAppearance.Mode.LIGHT, server.appearance?.mode)
        assertEquals("#F3F6F4", server.appearance?.colors?.surface)
        assertEquals("#FF4FA3", server.appearance?.capture?.challenge)
        assertEquals("Stay", server.copy?.capture?.text("leave.keepGoing"))
        assertEquals(listOf("Light", "No hats"), server.copy?.capture?.list("primer.selfieTips"))
    }
}
