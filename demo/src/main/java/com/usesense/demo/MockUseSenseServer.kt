package com.usesense.demo

import android.util.Log
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

    /** Ask for an extra check after the scan (server step-up round 2): head_turn, follow_dot or null. */
    var stepUpType: String? = null

    /** Head Turn directions for the mock spec, e.g. left, up, right, down (null: left, right). */
    var headSteps: List<String>? = null

    /** The challenge-fallback endpoint answers with this HTTP status (200 = swap to Follow Dot). */
    var fallbackStatus: Int = 200

    private var fallbackApplied = false

    /** On-device attack detection (policy inline_step_up): its threshold, or null for off. */
    var inlineThreshold: Int? = null
    var inlineChallenge: String = "auto"

    private var lastSessionId: String? = null

    /** Dashboard branding sent with the session (`branding`), e.g. a BRAND-v1 test brand; null sends none. */
    var branding: JSONObject? = null

    // MockWebServer resolves its host name when it binds, which is a network
    // call: on the main thread Android throws NetworkOnMainThreadException.
    // Start and stop it on a worker and wait; both take milliseconds.
    fun start() {
        val mock = MockWebServer().apply { dispatcher = createDispatcher() }
        offMainThread { mock.start(0) } // random available port
        server = mock
        port = mock.port
    }

    fun stop() {
        val mock = server ?: return
        server = null
        offMainThread {
            try {
                mock.shutdown()
            } catch (_: Exception) {}
        }
    }

    private fun offMainThread(block: () -> Unit) {
        var failure: Throwable? = null
        Thread { runCatching(block).onFailure { failure = it } }.apply {
            start()
            join()
        }
        failure?.let { throw it }
    }

    val baseUrl: String get() = "http://localhost:$port"

    private fun createDispatcher(): Dispatcher {
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Path only: the SDK appends ?env= (and ?nonce=) to every call.
                val path = request.requestUrl?.encodedPath ?: ""
                return when {
                    path == "/v1/sessions" && request.method == "POST" -> handleCreateSession()
                    path.matches(Regex("/v1/sessions/.+/signals")) && request.method == "POST" -> {
                        val round = request.requestUrl?.queryParameter("round")?.toIntOrNull() ?: 1
                        logMetadataKeys(request, round)
                        handleUploadSignals(round = round)
                    }
                    path.matches(Regex("/v1/sessions/.+/challenge-fallback")) && request.method == "POST" -> handleFallback()
                    path.matches(Regex("/v1/sessions/.+/complete")) && request.method == "POST" -> handleComplete()
                    path.matches(Regex("/v1/sessions/.+/status")) && request.method == "GET" -> handleStatus()
                    else -> {
                        Log.w("MockUseSenseServer", "No mock for ${request.method} $path")
                        MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"Not found"}}""")
                    }
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
                // Passive: no challenge and no step-up, as the server's
                // passive-first default sends.
                put("requires_stepup", challengeType != "none")
                if (challengeType != "none") {
                    put("challenge_type", challengeType)
                    put("challenge", challenge)
                }
                if (audioChallenge != null) put("audio_challenge", audioChallenge)
                put("policy_source", "sensei")
                inlineThreshold?.let { t ->
                    put("inline_step_up", JSONObject().put("enabled", true).put("suspicion_threshold", t).put("preferred_challenge", inlineChallenge))
                }
            })
            put("upload", JSONObject().apply {
                put("max_frames", 30)
                put("target_fps", 3)
                put("capture_duration_ms", if (challengeType == "none") 4500 else 8000)
            })
            put("geometric_coherence", JSONObject().apply {
                put("dual_path_enabled", false)
                put("screen_illumination_enabled", false)
                put("on_device_3dmm_required", false)
                put("mesh_binding_challenge", "")
            })
            branding?.let { put("branding", it) }
        }

        return MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody(response.toString())
    }

    /** Debug: the top-level metadata keys of each round's upload (logcat UseSenseMock). */
    private fun logMetadataKeys(request: RecordedRequest, round: Int) {
        runCatching {
            val body = request.body.readByteArray()
            val marker = "name=\"metadata\"".toByteArray()
            val at = indexOf(body, marker)
            if (at < 0) return
            val start = indexOf(body, "\r\n\r\n".toByteArray(), at) + 4
            val gz = body.copyOfRange(start, body.size)
            val json = java.util.zip.GZIPInputStream(gz.inputStream()).readBytes().toString(Charsets.UTF_8)
            val keys = JSONObject(json).keys().asSequence().toList().sorted()
            Log.i("UseSenseMock", "round $round metadata keys: $keys")
        }.onFailure { Log.w("UseSenseMock", "metadata not read: $it") }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun handleUploadSignals(round: Int): MockResponse {
        // Simulate processing delay
        Thread.sleep(500)

        val response = JSONObject().apply {
            val stepUp = stepUpType
            if (round == 1 && stepUp != null) {
                // As the server's step_up_v1: one more challenge in the same session.
                put("step_up", JSONObject().apply {
                    put("round", 2)
                    put("challenge", buildChallengeSpec(stepUp))
                    put("upload", JSONObject().put("max_frames", 20))
                })
            }
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
                // Same shape as the real server: { score, verdict } per pillar.
                fun pillar(score: Int, pass: Boolean) = JSONObject().apply {
                    put("score", score)
                    put("verdict", if (pass) "PASS" else "FAIL")
                }
                put("deepsense", pillar(scores.first, scores.first >= 50))
                put("livesense", pillar(scores.second, scores.second >= 50))
                put("dedupe", pillar(scores.third, scores.third <= 50))
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

    /** Speak Digits -> Follow Dot (POST /v1/sessions/:id/challenge-fallback). */
    private fun handleFallback(): MockResponse {
        if (fallbackStatus != 200) {
            val code = if (fallbackStatus == 409) "fallback_not_allowed" else "invalid_request"
            return MockResponse().setResponseCode(fallbackStatus)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"$code","message":"mock"}}""")
        }
        fallbackApplied = true
        val response = JSONObject().apply {
            put("session_id", lastSessionId ?: "sess_mock")
            put("policy", JSONObject().apply {
                put("requires_audio", false)
                put("requires_stepup", true)
                put("challenge_type", "follow_dot")
                put("challenge", buildChallengeSpec("follow_dot"))
                put("policy_source", "sensei+fallback")
            })
        }
        return MockResponse().setResponseCode(200)
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

    private fun buildChallengeSpec(type: String = challengeType): JSONObject {
        return when (type) {
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
                put("total_duration_ms", 4000)
                put("frames_per_step", 2)
                put("capture_fps_hint", 10)
                put("sequence", JSONArray().apply {
                    (headSteps ?: listOf("left", "right")).forEachIndexed { i, dir ->
                        put(JSONObject().apply { put("direction", dir); put("duration_ms", 2000); put("index", i) })
                    }
                })
            }
            "speak_phrase" -> JSONObject().apply {
                put("type", "speak_phrase")
                put("seed", UUID.randomUUID().toString().take(12))
                // As the server: 4 to 6 spaced digits, about a second each.
                put("total_duration_ms", 4000)
                put("phrase", "4 7 2 9")
                put("phrase_language", "numeric")
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
