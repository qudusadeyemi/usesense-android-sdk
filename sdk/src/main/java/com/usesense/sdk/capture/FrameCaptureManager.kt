package com.usesense.sdk.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.usesense.sdk.api.models.UploadConfig
import com.usesense.sdk.internal.FrameEncoder
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FrameCaptureManager(
    private val uploadConfig: UploadConfig,
) {
    private val frameBuffer = FrameBuffer(uploadConfig.maxFrames)
    private val isCapturing = AtomicBoolean(false)

    /**
     * Banking paused: the scan's quality gate is failing (the subject moved,
     * left the oval...). Frames are dropped, the buffer and its clock are kept.
     */
    @Volatile var isPaused = false
        private set
    private val encoderDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private var lastFrameTimeMs = 0L
    private val frameIntervalMs: Long = (1000L / uploadConfig.targetFps)

    var onFrameCaptured: ((CapturedFrame) -> Unit)? = null
    var onCaptureComplete: (() -> Unit)? = null

    val currentFrameIndex: Int get() = frameBuffer.currentIndex
    val capturedFrameCount: Int get() = frameBuffer.frameCount

    fun startCapture() {
        frameBuffer.startCapture()
        isPaused = false
        isCapturing.set(true)
        lastFrameTimeMs = 0L
    }

    /** Stop banking frames without ending the capture (the scan ring pauses). */
    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    /** Drop the banked frames and restart the clock, staying paused (a dropped scan window). */
    fun restartPaused() {
        isPaused = true
        frameBuffer.startCapture()
        isCapturing.set(true)
        lastFrameTimeMs = 0L
    }

    fun stopCapture() {
        isCapturing.set(false)
    }

    /** Start a server step-up round: a fresh buffer capped at [maxFrames]. */
    fun startStepUpCapture(maxFrames: Int) {
        frameBuffer.roundLimit = maxFrames
        startCapture()
    }

    fun getFrameBuffer(): FrameBuffer = frameBuffer

    @Volatile private var latencyLogged = false

    fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (!isCapturing.get() || isPaused) {
                imageProxy.close()
                return@Analyzer
            }

            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < frameIntervalMs) {
                imageProxy.close()
                return@Analyzer
            }
            lastFrameTimeMs = now
            // The sensor's exposure time, not the delivery time: on a Galaxy A03
            // frames arrive well after exposure, which paired frames with the
            // previous colour of the EVIDENCE-v1 sequence.
            val delivered = android.os.SystemClock.elapsedRealtime()
            val capturedAt = exposureElapsedMs(imageProxy.imageInfo.timestamp) ?: delivered
            if (!latencyLogged) {
                latencyLogged = true
                android.util.Log.i("UseSenseCapture", "frame exposure-to-delivery ${delivered - capturedAt} ms")
            }

            // Convert ImageProxy to non-mirrored JPEG and compute luminance in one pass
            val (jpegData, luminance) = imageProxyToJpegWithLuminance(imageProxy)
            imageProxy.close()

            if (jpegData != null) {
                val frame = frameBuffer.addFrame(jpegData, luminance, capturedAt)
                if (frame != null) {
                    onFrameCaptured?.invoke(frame)
                }
                if (frameBuffer.frameCount >= uploadConfig.maxFrames) {
                    stopCapture()
                    onCaptureComplete?.invoke()
                }
            }
        }
    }

    /**
     * Bank an upright frame that did not come from CameraX (the debug fake
     * camera). Same pacing, pause and cap rules as [createAnalyzer].
     */
    internal fun submitBitmap(upright: Bitmap) {
        if (!isCapturing.get() || isPaused) return
        val now = System.currentTimeMillis()
        if (now - lastFrameTimeMs < frameIntervalMs) return
        lastFrameTimeMs = now
        val capturedAt = android.os.SystemClock.elapsedRealtime()
        val luminance = computeLuminanceFromBitmap(upright)
        val jpegData = runCatching { FrameEncoder.bitmapToJpeg(upright) }.getOrNull() ?: return
        val frame = frameBuffer.addFrame(jpegData, luminance, capturedAt)
        if (frame != null) onFrameCaptured?.invoke(frame)
        if (frameBuffer.frameCount >= uploadConfig.maxFrames) {
            stopCapture()
            onCaptureComplete?.invoke()
        }
    }

    /**
     * Convert ImageProxy to JPEG and compute luminance in a single pass,
     * avoiding the overhead of calling toBitmap() twice.
     */
    private fun imageProxyToJpegWithLuminance(imageProxy: ImageProxy): Pair<ByteArray?, Double> {
        return try {
            val rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val correctedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(
                    rawBitmap, 0, 0,
                    rawBitmap.width, rawBitmap.height,
                    matrix, true
                ).also {
                    if (it !== rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            // Compute luminance from the corrected bitmap before JPEG encoding
            val luminance = computeLuminanceFromBitmap(correctedBitmap)
            val jpegData = FrameEncoder.bitmapToJpeg(correctedBitmap)
            correctedBitmap.recycle()

            Pair(jpegData, luminance)
        } catch (e: Exception) {
            Pair(null, 0.0)
        }
    }

    /**
     * Compute average luminance from a bitmap (downscaled to 64x48).
     * Uses ITU-R BT.601: L = 0.299*R + 0.587*G + 0.114*B
     * This feeds the Suspicion Engine's brightness stability signal.
     */
    private fun computeLuminanceFromBitmap(bitmap: Bitmap): Double {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 64, 48, true)

            var totalLuminance = 0.0
            val width = scaled.width
            val height = scaled.height
            val pixelCount = width * height
            val pixels = IntArray(pixelCount)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            if (scaled !== bitmap) scaled.recycle()

            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalLuminance += 0.299 * r + 0.587 * g + 0.114 * b
            }
            totalLuminance / pixelCount
        } catch (e: Exception) {
            0.0
        }
    }

    fun release() {
        isCapturing.set(false)
        frameBuffer.clear()
    }

    companion object {
        /**
         * A camera timestamp (ns) as an [android.os.SystemClock.elapsedRealtime] instant.
         * Camera2 reports either the realtime clock (SENSOR_INFO_TIMESTAMP_SOURCE
         * REALTIME) or the monotonic one (UNKNOWN, System.nanoTime's base); which
         * one is read from the value itself. Null when it matches neither.
         */
        internal fun exposureElapsedMs(
            sensorNs: Long,
            realtimeNs: Long = android.os.SystemClock.elapsedRealtimeNanos(),
            monotonicNs: Long = System.nanoTime(),
        ): Long? {
            if (sensorNs <= 0L) return null
            val window = 2_000_000_000L
            return when {
                sensorNs in (realtimeNs - window)..realtimeNs -> sensorNs / 1_000_000L
                sensorNs in (monotonicNs - window)..monotonicNs -> (sensorNs + (realtimeNs - monotonicNs)) / 1_000_000L
                else -> null
            }
        }
    }
}
