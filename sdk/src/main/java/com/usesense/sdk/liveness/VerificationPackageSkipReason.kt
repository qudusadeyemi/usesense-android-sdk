package com.usesense.sdk.liveness

/**
 * Why an upload carries no verification_package. Sent as
 * metadata.verification_package_skip_reason, which the server stores on
 * session.mesh_integrity.skip_reason (the web SDK already reports it), so a
 * mesh_absent penalty can be traced to its cause instead of guessed at.
 */
internal object VerificationPackageSkipReason {
    const val NO_GC_CONFIG = "no_gc_config"
    const val MESH_NOT_REQUESTED = "mesh_not_requested"
    const val LANDMARKER_NOT_STARTED = "landmarker_not_started"
    const val LANDMARKER_INIT_PENDING = "landmarker_init_pending"
    const val LANDMARKER_INIT_FAILED = "landmarker_init_failed"
    const val NO_FRAMES_PROCESSED = "no_frames_processed"
    const val NO_FACE_DETECTED = "no_face_detected"
    const val INSUFFICIENT_MESH_FRAMES = "insufficient_mesh_frames"

    /**
     * The server's mesh-integrity-validator rejects a verification_package with
     * fewer frames than this, and a rejected package can hard-reject the session,
     * so a thinner package is withheld rather than sent.
     */
    const val MIN_MESH_FRAMES = 3

    /** Mirrors iOS: either flag asks for on-device mesh. */
    fun meshRequested(dualPathEnabled: Boolean?, onDevice3dmmRequired: Boolean?): Boolean =
        dualPathEnabled == true || onDevice3dmmRequired == true

    /** Returns null when a verification_package should be built. */
    fun resolve(
        hasGeometricCoherenceConfig: Boolean,
        meshRequested: Boolean,
        initResult: FaceMeshInitResult,
        framesProcessed: Int,
        meshFrames: Int,
        fittedFrames: Int,
    ): String? = when {
        !hasGeometricCoherenceConfig -> NO_GC_CONFIG
        !meshRequested -> MESH_NOT_REQUESTED
        initResult == FaceMeshInitResult.NOT_ATTEMPTED -> LANDMARKER_NOT_STARTED
        initResult == FaceMeshInitResult.PENDING -> LANDMARKER_INIT_PENDING
        initResult == FaceMeshInitResult.FAILED -> LANDMARKER_INIT_FAILED
        framesProcessed == 0 -> NO_FRAMES_PROCESSED
        meshFrames == 0 -> NO_FACE_DETECTED
        fittedFrames < MIN_MESH_FRAMES -> INSUFFICIENT_MESH_FRAMES
        else -> null
    }
}

/** Reported as metadata.face_mesh_init_result. */
internal enum class FaceMeshInitResult(val value: String) {
    NOT_ATTEMPTED("not_attempted"),
    PENDING("pending"),
    SUCCESS("success"),
    FAILED("failed"),
}
