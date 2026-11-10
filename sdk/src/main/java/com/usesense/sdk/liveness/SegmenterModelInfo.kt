package com.usesense.sdk.liveness

/**
 * The MediaPipe image segmentation model bundled for the covered-face check
 * (capture-test-kit/COVER-v1.md). Checked by the mediapipe-parity-check
 * workflow: the bundled bytes must hash to [SHA256].
 */
internal object SegmenterModelInfo {
    /** Upstream model and version path segment. */
    const val VERSION: String = "selfie_multiclass_256x256/float32/1"

    const val SHA256: String = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"

    const val SIZE_BYTES: Long = 16371837L

    const val SOURCE_URL: String =
        "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite"

    /** Asset filename inside the AAR's assets/ directory. */
    const val ASSET_FILENAME: String = "selfie_multiclass_256x256.tflite"
}
