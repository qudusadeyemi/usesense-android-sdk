package com.usesense.sdk.v4

/**
 * Documentation marker for the LiveSense v4 Android flow. Phase 1
 * ticket A-2.
 *
 * The end-to-end v4 capture activity is intentionally not implemented
 * in this Phase 1 scaffold because it requires substantial CameraX +
 * ML Kit + MediaCodec integration that is only meaningfully verifiable
 * on real hardware (Pixel 7 StrongBox path, Samsung A54 TEE fallback,
 * thermal throttling at sustained 30fps).
 *
 * The flow is composed from these building blocks (all shipped in
 * this branch):
 *   - com.usesense.sdk.capture.ZoomMotionController  (state machine)
 *   - com.usesense.sdk.integrity.HashChainBuilder    (chain protocol)
 *   - com.usesense.sdk.integrity.HardwareKeyManager  (StrongBox/TEE)
 *   - com.usesense.sdk.integrity.V4ChainSigner       (ECDSA terminal)
 *   - com.usesense.sdk.v4.V4UploadClient             (upload + result)
 *
 * Integration TODO (Phase 2 ticket A-1/A-2 completion work):
 *   1. Add LiveSenseV4Activity:
 *      - CameraX Preview + ImageAnalysis pinned to 1280x720 @ 30fps.
 *      - Camera2Interop.setCaptureRequestOption to lock
 *        CONTROL_AE_MODE_OFF, CONTROL_AWB_MODE_OFF during the zoom
 *        phase after reading baseline AE/AWB values from the first
 *        steady frame.
 *      - ML Kit FaceDetection.process() (FAST mode) feeding
 *        ZoomObservation to the controller.
 *      - MediaCodec H.264 encoder + MediaMuxer MP4 container running
 *        in parallel with the capture loop.
 *      - Per-frame JPEG export appended to HashChainBuilder.
 *      - ZoomPromptView overlay for the framing-oval animation.
 *   2. Add ZoomPromptView (custom View with oval Paint and
 *      ValueAnimator using PathInterpolator(0.16f, 1f, 0.3f, 1f)).
 *   3. Add VideoEncoder (MediaCodec + MediaMuxer, ~4 Mbps, keyframe
 *      every 30 frames).
 *   4. Wire UseSense.startV4Verification(...) to launch
 *      LiveSenseV4Activity and plumb V4VerificationCallback via
 *      either result broadcasts or a bound service.
 *
 * Device-verify before GA:
 *   - Pixel 7 or newer (StrongBox path).
 *   - Samsung A54 (TEE only, verify fallback).
 *   - A mid-tier Xiaomi with budget SoC (throttling behaviour).
 *   - A Samsung foldable (hinge-state + camera reconfiguration).
 */
internal object LiveSenseV4Manifest {
    const val SUPPORTED_MIN_SDK: Int = 24
    const val TARGET_CAPTURE_FPS: Int = 30
    const val TARGET_CAPTURE_WIDTH: Int = 1280
    const val TARGET_CAPTURE_HEIGHT: Int = 720
    const val MAX_ZOOM_CAPTURE_MS: Long = 3000
}
