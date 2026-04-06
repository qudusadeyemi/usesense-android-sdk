package com.usesense.demo

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A local mock server that simulates the UseSense backend API.
 * Enables fully offline demos of the SDK with configurable challenge types and verdicts.
 */
class MockUseSenseServer {

    private var server: MockWebServer? = null
    var port: Int = 0
        private set

    var challengeType: String = "follow_dot"
    var mockDecision: String = "APPROVE"
    var requireAudio: Boolean = false

    private var lastSessionId: String? = null

    fun start() {
        server = MockWebServer()
        server?.dispatcher = createDispatcher()
        server?.start(0) // random available port
        port = server?.port ?: 0
    }

    fun stop() {
        try {
            server?.shutdown()
        } catch (_: Exception) {}
        server = null
    }

    val baseUrl: String get() = "http://localhost:$port"

    private fun createDispatcher(): Dispatcher {
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/v1/sessions" && request.method == "POST" -> handleCreateSession()
                    path.matches(Regex("/v1/sessions/.+/signals")) && request.method == "POST" -> handleUploadSignals()
                    path.matches(Regex("/v1/sessions/.+/complete")) && request.method == "POST" -> handleComplete()
                    path.matches(Regex("/v1/sessions/.+/status")) && request.method == "GET" -> handleStatus()
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"Not found"}}""")
                }
            }
        }
    }

    private fun handleCreateSession(): MockResponse {
        val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(32)}"
        val sessionToken = "sess_tok_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val nonce = "nonce_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        lastSessionId = sessionId

        val challenge = buildChallengeSpec()
        val audioChallenge = if (requireAudio) buildAudioChallenge() else null

        val response = JSONObject().apply {
            put("session_id", sessionId)
            put("session_token", sessionToken)
            put("expires_at", java.time.Instant.now().plusSeconds(900).toString())
            put("nonce", nonce)
            put("policy", JSONObject().apply {
                put("requires_audio", requireAudio)
                put("requires_stepup", true)
                put("challenge_type", challengeType)
                put("challenge", challenge)
                if (audioChallenge != null) put("audio_challenge", audioChallenge)
                put("policy_source", "sensei")
            })
            put("upload", JSONObject().apply {
                put("max_frames", 30)
                put("target_fps", 3)
                put("capture_duration_ms", 8000)
            })
            put("geometric_coherence", JSONObject().apply {
                put("dual_path_enabled", false)
                put("screen_illumination_enabled", false)
                put("on_device_3dmm_required", false)
                put("mesh_binding_challenge", "")
            })
        }

        return MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody(response.toString())
    }

    private fun handleUploadSignals(): MockResponse {
        // Simulate processing delay
        Thread.sleep(500)

        val response = JSONObject().apply {
            put("received", true)
            put("session_id", lastSessionId ?: "sess_mock")
            put("frames_count", 45)
            put("audio_received", requireAudio)
            put("metadata_received", true)
            put("total_size_bytes", 1500000)
        }

        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(response.toString())
    }

    private fun handleComplete(): MockResponse {
        // Simulate server processing time (3-5 seconds)
        Thread.sleep(3000)

        val scores = when (mockDecision) {
            "APPROVE" -> Triple(92, 87, 5)
            "REJECT" -> Triple(45, 30, 75)
            "MANUAL_REVIEW" -> Triple(72, 55, 40)
            else -> Triple(0, 0, 0)
        }

        val reasons = when (mockDecision) {
            "APPROVE" -> JSONArray(listOf("All three pillars passed threshold"))
            "REJECT" -> JSONArray(listOf("Liveness score below threshold", "Possible duplicate detected"))
            "MANUAL_REVIEW" -> JSONArray(listOf("Borderline liveness score", "Review recommended"))
            else -> JSONArray(listOf("Processing error"))
        }

        val response = JSONObject().apply {
            put("session_id", lastSessionId ?: "sess_mock")
            put("organization_id", "org_demo_12345")
            put("session_type", "enrollment")
            put("identity_id", "ident_${UUID.randomUUID().toString().take(8)}")
            put("decision", mockDecision)
            put("channel_trust_score", scores.first)
            put("liveness_score", scores.second)
            put("dedupe_risk_score", scores.third)
            put("pillar_verdicts", JSONObject().apply {
                put("channel_trust", if (scores.first >= 50) "PASS" else "FAIL")
                put("liveness", if (scores.second >= 50) "PASS" else "FAIL")
                put("dedupe", if (scores.third <= 50) "PASS" else "FAIL")
            })
            put("verdict_metadata", JSONObject().apply {
                put("source", "matrix")
                put("logic", "weakest_link")
                put("hard_gate_tripped", false)
                put("risk_band", if (mockDecision == "APPROVE") "low" else "high")
            })
            put("reasons", reasons)
            put("timestamp", java.time.Instant.now().toString())
        }

        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(response.toString())
    }

    private fun handleStatus(): MockResponse {
        val response = JSONObject().apply {
            put("session_id", lastSessionId ?: "sess_mock")
            put("status", "completed")
            put("decision", mockDecision)
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(response.toString())
    }

    private fun buildChallengeSpec(): JSONObject {
        return when (challengeType) {
            "follow_dot" -> JSONObject().apply {
                put("type", "follow_dot")
                put("seed", UUID.randomUUID().toString().take(12))
                put("total_duration_ms", 6000)
                put("frames_per_step", 2)
                put("capture_fps_hint", 10)
                put("dot_size_px", 20)
                put("waypoints", JSONArray().apply {
                    put(JSONObject().apply { put("x", 0.48); put("y", 0.47); put("duration_ms", 1500); put("index", 0) })
                    put(JSONObject().apply { put("x", 0.85); put("y", 0.20); put("duration_ms", 1500); put("index", 1) })
                    put(JSONObject().apply { put("x", 0.15); put("y", 0.80); put("duration_ms", 1500); put("index", 2) })
                    put(JSONObject().apply { put("x", 0.75); put("y", 0.65); put("duration_ms", 1500); put("index", 3) })
                })
            }
            "head_turn" -> JSONObject().apply {
                put("type", "head_turn")
                put("seed", UUID.randomUUID().toString().take(12))
                put("total_duration_ms", 5500)
                put("frames_per_step", 2)
                put("capture_fps_hint", 10)
                put("sequence", JSONArray().apply {
                    put(JSONObject().apply { put("direction", "left"); put("duration_ms", 2000); put("index", 0) })
                    put(JSONObject().apply { put("direction", "up"); put("duration_ms", 2000); put("index", 1) })
                    put(JSONObject().apply { put("direction", "center"); put("duration_ms", 1500); put("index", 2) })
                })
            }
            "speak_phrase" -> JSONObject().apply {
                put("type", "speak_phrase")
                put("seed", UUID.randomUUID().toString().take(12))
                put("total_duration_ms", 3000)
                put("phrase", "I am verifying my identity")
                put("phrase_language", "en")
            }
            else -> JSONObject()
        }
    }

    private fun buildAudioChallenge(): JSONObject {
        return JSONObject().apply {
            put("type", "speak_phrase")
            put("seed", UUID.randomUUID().toString().take(12))
            put("total_duration_ms", 3000)
            put("phrase", "My voice confirms who I am")
            put("phrase_language", "en")
        }
    }
}
