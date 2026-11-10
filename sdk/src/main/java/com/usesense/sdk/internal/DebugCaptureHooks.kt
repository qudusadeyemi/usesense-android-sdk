package com.usesense.sdk.internal

import android.content.Context
import android.graphics.Bitmap

/**
 * Test rig for the capture screen (capture-test-kit/SPEC.md, "Test rig"):
 * replaces camera frames with decoded frames of a video file, fed through the
 * same pipeline (quality gate, face mesh, contour scan, frame capture), and
 * can freeze a frame or force a state so screenshots match the goldens.
 *
 * The implementation lives in the SDK's `debug` source set only, so release
 * builds have no such class and [load] returns null: this can never ship.
 */
internal interface DebugCaptureHooks {
    /** align | scan | upload: hold the capture in that state for a screenshot. */
    val forceState: String?

    /** Ring / upload bar progress to hold, 0..1. */
    val forceProgress: Float?

    /** A GateHint name to hold on the align screen (e.g. CENTRE, FACE_LIGHT). */
    val forceHint: String?

    /**
     * A storyboard screen to hold for a screenshot (extra check, sheets,
     * pages, leave dialog), e.g. head_left, xc_dot, rejected_retry.
     */
    val forceScreen: String? get() = null

    /** The video is held on one frame. */
    val frozen: Boolean

    /** Start delivering upright, unmirrored frames (on a background thread). */
    /** False when the config only forces a state: the real camera stays on. */
    val usesFakeCamera: Boolean get() = true

    fun start(onFrame: (Bitmap) -> Unit)

    fun stop()

    companion object {
        private const val IMPL = "com.usesense.sdk.debug.FakeCameraHooks"

        /** The debug rig when this is a debug build and it was switched on; else null. */
        fun load(context: Context): DebugCaptureHooks? = runCatching {
            Class.forName(IMPL).getConstructor(Context::class.java).newInstance(context) as DebugCaptureHooks
        }.getOrNull()?.takeIf { (it as? Enablable)?.enabled == true }
    }

    interface Enablable {
        val enabled: Boolean
    }
}
