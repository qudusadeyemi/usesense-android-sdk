package com.usesense.sdk.v4

/**
 * Constants and integration map for the LiveSense v4 Android flow.
 * Phase 1 ticket A-2.
 *
 * The activity is implemented in LiveSenseV4Activity.kt. This file
 * exposes the constants used across the integration and documents
 * the device-verify checklist that must complete before GA.
 *
 * Building blocks (all shipped in this branch):
 *   - com.usesense.sdk.capture.ZoomMotionController  (state machine)
 *   - com.usesense.sdk.integrity.HashChainBuilder    (chain protocol)
 *   - com.usesense.sdk.integrity.HardwareKeyManager  (StrongBox/TEE)
 *   - com.usesense.sdk.integrity.V4ChainSigner       (ECDSA terminal)
 *   - com.usesense.sdk.ui.ZoomPromptView             (framing oval UI)
 *   - com.usesense.sdk.upload.VideoEncoder           (MediaCodec MP4)
 *   - com.usesense.sdk.v4.LiveSenseV4Activity        (orchestration)
 *   - com.usesense.sdk.v4.V4Bridge                   (callback bridge)
 *   - com.usesense.sdk.v4.V4UploadClient             (upload + result)
 *
 * Device-verify before GA (see Phase 2 plan):
 *   - Pixel 7 or newer (StrongBox path, 30fps sustained).
 *   - Samsung A54 (TEE only, slower encoder).
 *   - A mid-tier Xiaomi / Redmi with budget SoC (thermal + NV21 pref).
 *   - A Samsung foldable (hinge-state + camera reconfigure).
 */
internal object LiveSenseV4Manifest {
    const val SUPPORTED_MIN_SDK: Int = 24
    const val TARGET_CAPTURE_FPS: Int = 30
    const val TARGET_CAPTURE_WIDTH: Int = 1280
    const val TARGET_CAPTURE_HEIGHT: Int = 720
    const val MAX_ZOOM_CAPTURE_MS: Long = 3000
}
