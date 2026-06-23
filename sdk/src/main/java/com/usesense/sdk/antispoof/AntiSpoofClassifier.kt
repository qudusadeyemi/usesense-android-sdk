// ============================================================================
// AntiSpoofClassifier -- On-device EfficientNet-B0 spoof detection (v4.2)
// ============================================================================
//
// Runs the CelebA-Spoof antispoof classifier against captured face frames.
// Feature-flagged behind UseSenseConfig.antispoofOnDeviceEnabled. When the
// flag is off (default in v4.2), the classifier is not loaded and the
// watchtower backend runs the classifier server-side.
//
// Loads antispoof.tflite from one of (in order):
//   1. app-private storage (OTA-downloaded)   -> filesDir/usesense/antispoof/<version>/antispoof.tflite
//   2. SDK assets (shipped with the AAR)      -> assets/antispoof/<version>/antispoof.tflite
//
// When the artifact is missing the classifier enters no-op mode and
// `predict()` returns null; the server path stays authoritative.
//
// See docs/sdk-specs/antispoof-classifier-sdk-spec.md for the full contract.
// ============================================================================
package com.usesense.sdk.antispoof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Per-frame classifier output. Uploaded as signals.deep_classifier_on_device.samples[]. */
data class OnDeviceClassifierSample(
    val frameIndex: Int,
    val spoofProbability: Double,
    val latencyMs: Int,
    val modelVersion: String,
    val backbone: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "frameIndex" to frameIndex,
        "spoofProbability" to spoofProbability,
        "latencyMs" to latencyMs,
        "modelVersion" to modelVersion,
        "backbone" to backbone,
    )
}

class AntiSpoofClassifier private constructor(
    private val interpreter: Interpreter?,
    private val nnApiDelegate: NnApiDelegate?,
    val modelVersion: String,
) : AutoCloseable {

    val isAvailable: Boolean get() = interpreter != null

    /**
     * Runs inference on `bitmap` within `faceBounds` (pixel coordinates).
     * Returns null when the classifier is disabled, the crop is invalid, or
     * the interpreter fails. Safe to call from a background thread; a single
     * classifier instance is NOT concurrent-safe so serialise externally.
     */
    fun predict(
        bitmap: Bitmap,
        frameIndex: Int,
        faceBounds: Rect,
    ): OnDeviceClassifierSample? {
        val interpreter = interpreter ?: return null
        val start = System.nanoTime()

        val cropped = centerCropWithMargin(bitmap, faceBounds) ?: return null
        val resized = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToNormalizedBuffer(resized)

        val output = Array(1) { FloatArray(NUM_CLASSES) }
        try {
            interpreter.run(input, output)
        } catch (e: Exception) {
            Log.w(TAG, "antispoof inference failed", e)
            return null
        }

        val probs = softmax(output[0])
        val spoofProb = probs[SPOOF_CLASS_INDEX].toDouble()
        val latencyMs = ((System.nanoTime() - start) / 1_000_000).toInt()

        return OnDeviceClassifierSample(
            frameIndex = frameIndex,
            spoofProbability = spoofProb,
            latencyMs = latencyMs,
            modelVersion = modelVersion,
            backbone = MODEL_BACKBONE,
        )
    }

    override fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
    }

    // ─── Preprocessing ────────────────────────────────────────────────────

    private fun centerCropWithMargin(bitmap: Bitmap, face: Rect): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val cx = (face.left + face.right) / 2f
        val cy = (face.top + face.bottom) / 2f
        val side = max(face.width(), face.height()) * BBOX_EXPANSION
        val left = max(0f, cx - side / 2f).toInt()
        val top = max(0f, cy - side / 2f).toInt()
        val right = min(w.toFloat(), cx + side / 2f).toInt()
        val bottom = min(h.toFloat(), cy + side / 2f).toInt()
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun bitmapToNormalizedBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xff) / 255f
            val g = ((px shr 8) and 0xff) / 255f
            val b = (px and 0xff) / 255f
            buf.putFloat((r - MEAN[0]) / STD[0])
            buf.putFloat((g - MEAN[1]) / STD[1])
            buf.putFloat((b - MEAN[2]) / STD[2])
        }
        buf.rewind()
        return buf
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val m = logits.max()
        var denom = 0f
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            val v = exp((logits[i] - m).toDouble()).toFloat()
            out[i] = v
            denom += v
        }
        if (denom <= 0f) return logits
        for (i in out.indices) out[i] /= denom
        return out
    }

    companion object {
        private const val TAG = "AntiSpoofClassifier"
        private const val INPUT_SIZE = 224
        private const val NUM_CLASSES = 2
        private const val SPOOF_CLASS_INDEX = 1      // model head order: [live, spoof]
        private const val BBOX_EXPANSION = 1.25f     // 25% margin around face crop
        private const val MODEL_BACKBONE = "efficientnet_b0"
        private const val ASSET_BASE = "antispoof"   // assets/antispoof/<version>/antispoof.tflite
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * Loads the antispoof model. When `enabled` is false or the artifact
         * is missing on the device, returns a no-op instance (`isAvailable = false`).
         */
        @JvmOverloads
        fun load(
            context: Context,
            enabled: Boolean,
            modelVersion: String = "v1",
            useNnApi: Boolean = true,
        ): AntiSpoofClassifier {
            if (!enabled) {
                return AntiSpoofClassifier(null, null, modelVersion)
            }

            val buffer = locateModelBuffer(context, modelVersion)
                ?: return AntiSpoofClassifier(null, null, modelVersion).also {
                    Log.i(TAG, "antispoof model not found for version $modelVersion -- falling back to server")
                }

            var nnApi: NnApiDelegate? = null
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                if (useNnApi) {
                    try {
                        nnApi = NnApiDelegate()
                        addDelegate(nnApi)
                    } catch (e: Throwable) {
                        Log.w(TAG, "NNAPI delegate unavailable, falling back to CPU", e)
                        nnApi = null
                    }
                }
            }

            return try {
                val interpreter = Interpreter(buffer, options)
                AntiSpoofClassifier(interpreter, nnApi, modelVersion)
            } catch (e: Throwable) {
                Log.w(TAG, "antispoof interpreter load failed", e)
                nnApi?.close()
                AntiSpoofClassifier(null, null, modelVersion)
            }
        }

        private fun locateModelBuffer(context: Context, modelVersion: String): ByteBuffer? {
            // 1. OTA-downloaded copy in app-private storage.
            val otaFile = File(
                context.filesDir,
                "usesense/antispoof/$modelVersion/antispoof.tflite",
            )
            if (otaFile.exists() && otaFile.length() > 0) {
                return mapFile(otaFile)
            }

            // 2. AAR asset: assets/antispoof/<version>/antispoof.tflite
            return runCatching {
                context.assets.openFd("$ASSET_BASE/$modelVersion/antispoof.tflite").use { fd ->
                    fd.createInputStream().channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
            }.getOrNull()
        }

        private fun mapFile(file: File): ByteBuffer =
            FileInputStream(file).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            }
    }
}
