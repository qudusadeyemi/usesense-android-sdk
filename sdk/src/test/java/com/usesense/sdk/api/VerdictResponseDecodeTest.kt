package com.usesense.sdk.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.usesense.sdk.api.models.VerdictResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictResponseDecodeTest {

    // Built the way UseSenseApiClient builds it.
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(VerdictResponse::class.java)

    @Test
    fun `decodes the pillar verdict objects the server sends`() {
        // Shape of a real sandbox dedupe REJECT, 2026-11-06. Before the fix this
        // threw "Expected a string but was BEGIN_OBJECT at path $.pillar_verdicts.dedupe".
        val json = """
            {
              "session_id": "sess_test",
              "session_type": "enrollment",
              "decision": "REJECT",
              "channel_trust_score": 82,
              "liveness_score": 91,
              "dedupe_risk_score": 100,
              "pillar_verdicts": {
                "deepsense": {"score": 82, "verdict": "PASS"},
                "livesense": {"score": 91.5, "verdict": "PASS"},
                "dedupe": {"score": 100, "verdict": "FAIL"}
              },
              "reasons": ["High similarity with existing enrolled identity"]
            }
        """.trimIndent()

        val verdict = adapter.fromJson(json)!!

        assertEquals("REJECT", verdict.decision)
        assertEquals("FAIL", verdict.pillarVerdicts?.dedupe?.verdict)
        assertEquals(100.0, verdict.pillarVerdicts?.dedupe?.score)
        assertEquals("PASS", verdict.pillarVerdicts?.livesense?.verdict)
    }

    @Test
    fun `decodes when pillar verdicts are absent or partial`() {
        val base = """"session_id":"s","session_type":"enrollment","decision":"APPROVE""""
        assertEquals(null, adapter.fromJson("{$base}")!!.pillarVerdicts)
        assertEquals(null, adapter.fromJson("{$base,\"pillar_verdicts\":null}")!!.pillarVerdicts)
        val partial = adapter.fromJson("{$base,\"pillar_verdicts\":{\"dedupe\":{\"verdict\":\"PASS\"}}}")!!
        assertEquals("PASS", partial.pillarVerdicts?.dedupe?.verdict)
        assertEquals(null, partial.pillarVerdicts?.dedupe?.score)
    }
}
