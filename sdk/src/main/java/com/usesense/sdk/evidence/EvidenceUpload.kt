package com.usesense.sdk.evidence

import org.json.JSONArray
import org.json.JSONObject

/** Colour sequence record (EVIDENCE-v1 section 6): what actually played, measured. */
data class ColourEvidence(
    val sequenceId: String,
    val segments: List<Segment> = emptyList(),
    val screenBrightness: Double? = null,
    val completed: Boolean = false,
    val interruptedReason: String? = null,
    val awbLocked: Boolean = false,
) {
    data class Segment(val colour: String, val startMs: Long, val endMs: Long)

    fun toJson(): JSONObject = JSONObject().apply {
        put("sequence_id", sequenceId)
        put("segments", JSONArray().apply {
            segments.forEach { s ->
                put(JSONObject().put("colour", s.colour).put("start_ms", s.startMs).put("end_ms", s.endMs))
            }
        })
        put("screen_brightness", screenBrightness?.let { Evidence.round(it, 3) } ?: JSONObject.NULL)
        put("completed", completed)
        put("interrupted_reason", interruptedReason ?: JSONObject.NULL)
        put("awb_locked", awbLocked)
    }
}

/** Closer step record (EVIDENCE-v1 section 7). */
data class CloserEvidence(
    val farFrames: List<Int> = emptyList(),
    val nearFrames: List<Int> = emptyList(),
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val achievedScale: Double? = null,
    val completed: Boolean = false,
    val skippedReason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("far_frames", JSONArray(farFrames))
        put("near_frames", JSONArray(nearFrames))
        put("started_at_ms", startedAtMs ?: JSONObject.NULL)
        put("ended_at_ms", endedAtMs ?: JSONObject.NULL)
        put("achieved_scale", achievedScale?.let { Evidence.round(it, 3) } ?: JSONObject.NULL)
        put("completed", completed)
        put("skipped_reason", skippedReason ?: JSONObject.NULL)
    }

    companion object {
        const val TIMEOUT = "timeout"
        const val DISABLED = "disabled"

        fun skipped(reason: String, startedAtMs: Long? = null, endedAtMs: Long? = null) =
            CloserEvidence(startedAtMs = startedAtMs, endedAtMs = endedAtMs, completed = false, skippedReason = reason)
    }
}

/**
 * What this build can do, whether or not it happened in this session. The
 * server reads a missing signal from a capable SDK as a failed step, from an
 * incapable one as not available.
 */
data class EvidenceCapability(
    val landmarks: Boolean,
    val blendshapes: Boolean,
    val poseMethod: String?,
    val closerStep: Boolean,
    val bindingV2: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("landmarks", landmarks)
        put("blendshapes", blendshapes)
        put("pose_method", poseMethod ?: JSONObject.NULL)
        put("closer_step", closerStep)
        put("binding_v2", bindingV2)
    }

    companion object {
        /**
         * [meshAvailable]: MediaPipe loaded for this session. [guided]: the guided
         * scan runs (the only UI with the closer step). [playIntegrity]: a cloud
         * project number is configured, so a Standard request can be made.
         */
        fun current(meshAvailable: Boolean, guided: Boolean, playIntegrity: Boolean) = EvidenceCapability(
            landmarks = meshAvailable,
            blendshapes = meshAvailable,
            poseMethod = if (meshAvailable) Evidence.POSE_METHOD_V3 else null,
            closerStep = meshAvailable && guided,
            bindingV2 = playIntegrity,
        )
    }
}

/** Binding v2 digest (EVIDENCE-v1 section 8), byte for byte the server's `evidenceDigestText`. */
object EvidenceDigest {
    data class Frame(val frameIndex: Int, val frameHash: String, val meshDigest: String)

    /** Lines joined with "\n", no trailing newline. */
    fun text(sessionId: String, challenge: String, frames: List<Frame>, colour: ColourEvidence?, closer: CloserEvidence?): String {
        val lines = mutableListOf("usesense-evidence-v1", "session:$sessionId", "challenge:$challenge")
        for (f in frames) lines += "frame:${f.frameIndex}:${f.frameHash}:${f.meshDigest}"
        lines += if (colour != null && colour.segments.isNotEmpty() && colour.sequenceId.isNotEmpty()) {
            "colour:${colour.sequenceId}:" + colour.segments.joinToString(",") { "${it.colour}@${it.startMs}-${it.endMs}" }
        } else {
            "colour:none"
        }
        val far = closer?.farFrames.orEmpty()
        val near = closer?.nearFrames.orEmpty()
        lines += if (far.isEmpty() && near.isEmpty()) "closer:none" else "closer:${far.joinToString(",")}|${near.joinToString(",")}"
        return lines.joinToString("\n")
    }

    fun digest(sessionId: String, challenge: String, frames: List<Frame>, colour: ColourEvidence?, closer: CloserEvidence?): String =
        Evidence.sha256Hex(text(sessionId, challenge, frames, colour, closer))

    /** The verification package's frames, in array order, as the digest reads them. */
    fun frames(pkg: JSONObject?): List<Frame> {
        val list = pkg?.optJSONArray("frames") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val f = list.optJSONObject(i) ?: return@mapNotNull null
            if (!f.has("frameIndex") || !f.has("frameHash") || !f.has("meshDigest")) return@mapNotNull null
            Frame(f.getInt("frameIndex"), f.getString("frameHash"), f.getString("meshDigest"))
        }
    }
}

/** The binding block: a Play Integrity token whose requestHash is the digest, or why there is none. */
data class BindingV2Evidence(
    val digest: String,
    val integrityToken: String? = null,
    val errorName: String? = null,
    val errorMessage: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", "binding_v2")
        put("digest", digest)
        if (integrityToken != null) {
            put("android", JSONObject().put("integrity_token", integrityToken))
        } else {
            put("error", JSONObject()
                .put("name", (errorName ?: "BindingUnavailable").take(64))
                .put("message", (errorMessage ?: "no Play Integrity token").take(256)))
        }
    }
}

/** `metadata.evidence`. */
data class EvidenceUploadBlock(
    val capability: EvidenceCapability,
    val colour: ColourEvidence? = null,
    val closer: CloserEvidence? = null,
    val binding: BindingV2Evidence? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("contract", EvidenceConfig.CONTRACT_V1)
        put("clock", Evidence.CLOCK)
        put("capability", capability.toJson())
        colour?.let { put("colour", it.toJson()) }
        closer?.let { put("closer", it.toJson()) }
        binding?.let { put("binding", it.toJson()) }
    }
}
