package com.usesense.sdk.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChallengeSpec(
    @Json(name = "type") val type: String,
    @Json(name = "seed") val seed: String,
    @Json(name = "total_duration_ms") val totalDurationMs: Int,
    @Json(name = "frames_per_step") val framesPerStep: Int? = null,
    @Json(name = "capture_fps_hint") val captureFpsHint: Int? = null,

    // follow_dot fields
    @Json(name = "waypoints") val waypoints: List<Waypoint>? = null,
    @Json(name = "dot_size_px") val dotSizePx: Int? = null,

    // head_turn fields
    @Json(name = "sequence") val sequence: List<HeadTurnStep>? = null,

    // speak_phrase fields
    @Json(name = "phrase") val phrase: String? = null,
    @Json(name = "phrase_language") val phraseLanguage: String? = null,
) {
    companion object {
        const val TYPE_FOLLOW_DOT = "follow_dot"
        const val TYPE_HEAD_TURN = "head_turn"
        const val TYPE_SPEAK_PHRASE = "speak_phrase"
    }
}

@JsonClass(generateAdapter = true)
data class Waypoint(
    @Json(name = "x") val x: Float,
    @Json(name = "y") val y: Float,
    @Json(name = "duration_ms") val durationMs: Int,
    @Json(name = "index") val index: Int,
)

@JsonClass(generateAdapter = true)
data class HeadTurnStep(
    @Json(name = "direction") val direction: String,
    @Json(name = "duration_ms") val durationMs: Int,
    @Json(name = "index") val index: Int,
) {
    companion object {
        const val LEFT = "left"
        const val RIGHT = "right"
        const val UP = "up"
        const val DOWN = "down"
        const val CENTER = "center"
    }
}
