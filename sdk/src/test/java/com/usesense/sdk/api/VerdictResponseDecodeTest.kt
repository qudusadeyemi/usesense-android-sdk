package com.usesense.sdk.api

import com.usesense.sdk.api.models.VerdictResponse
import com.usesense.sdk.api.models.apiMoshi
import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictResponseDecodeTest {
    // The same Moshi UseSenseApiClient decodes with.
    private val adapter = apiMoshi().adapter(VerdictResponse::class.java)

    @Test
    fun `decodes the pillar verdict objects the server sends`() {
        // Shape of a real sandbox dedupe REJECT, 2026-11-06. Before the fix this
        // threw "Expected a string but was BEGIN_OBJECT at path $.pillar_verdicts.dedupe".
        val json =
            """
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

    @Test
    fun `fractional numbers decode`() {
        // Sandbox REJECT, 2026-11-06: "Expected an int but was 92.8 at path
        // $.dedupe_analysis.highestDuplicateSimilarity" turned the decision into error 2001.
        val json =
            """
            {"session_id":"s","session_type":"enrollment","decision":"REJECT",
             "channel_trust_score":81.5,"liveness_score":89,"dedupe_risk_score":92.8,
             "dedupe_analysis":{"duplicateSearchPerformed":true,"highestDuplicateSimilarity":92.8,"crossIdentityRisk":92.8}}
            """.trimIndent()
        val verdict = adapter.fromJson(json)!!
        assertEquals("REJECT", verdict.decision)
        assertEquals(92.8, verdict.dedupeAnalysis!!.highestDuplicateSimilarity, 0.0)
    }

    @Test
    fun `a malformed detail block is dropped, not fatal`() {
        val json =
            """
            {"session_id":"s","session_type":"enrollment","decision":"APPROVE",
             "dedupe_analysis":{"duplicateSearchPerformed":"yes","duplicateMatches":"none"},
             "debug":[1,2,3],
             "verdict_metadata":{"source":"matrix","logic":"x"}}
            """.trimIndent()
        val verdict = adapter.fromJson(json)!!
        assertEquals("APPROVE", verdict.decision)
        assertEquals(null, verdict.dedupeAnalysis)
        assertEquals(null, verdict.debug)
        assertEquals("matrix", verdict.verdictMetadata?.source)
    }
}
