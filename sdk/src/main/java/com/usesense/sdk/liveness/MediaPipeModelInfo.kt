// Hand-written stub for first-time MediaPipe bundled-asset integration in Phase 4.
// Future updates land here automatically via the mediapipe-sdk-sync workflow
// in qudusadeyemi/usesense-watchtower, which overwrites this file with content
// generated from .github/mediapipe-sync-templates/MediaPipeModelInfo.kt.tmpl.
//
// Source:    https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
// Synced at: 2026-04-10T08:30:00Z (initial Phase 4 bootstrap, no sync run yet)
// Watchtower commit: bootstrap

package com.usesense.sdk.liveness

/**
 * Constants describing the MediaPipe FaceLandmarker model bundled with this SDK.
 * Kept in sync with usesense-watchtower's canonical manifest by the
 * mediapipe-sdk-sync workflow. Do not modify by hand.
 */
internal object MediaPipeModelInfo {

    /** Upstream version path segment, e.g. "float16/1". */
    const val VERSION: String = "float16/1"

    /**
     * SHA-256 of the bundled face_landmarker.task bytes.
     * The mediapipe-parity-check CI job verifies bundled bytes hash to this value.
     */
    const val SHA256: String = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"

    /** Size of the bundled face_landmarker.task in bytes. */
    const val SIZE_BYTES: Long = 3758596L

    /**
     * Upstream URL the canonical bytes were originally fetched from.
     * Recorded for audit and provenance.
     */
    const val SOURCE_URL: String = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

    /** CDN URL for the canonical bytes (informational on Android, used by web SDK). */
    const val CDN_URL: String = "https://cdn.usesense.ai/mediapipe/face_landmarker/64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff/face_landmarker.task"

    /**
     * Asset filename inside the AAR's assets/ directory.
     * Pass to BaseOptions.builder().setModelAssetPath(ASSET_FILENAME).
     */
    const val ASSET_FILENAME: String = "face_landmarker.task"

    /**
     * Stable identifier for upload payloads and observability.
     * Format: "mediapipe_face_landmarker@<short-sha>"
     */
    const val VERSION_LABEL: String = "mediapipe_face_landmarker@64184e22"
}
