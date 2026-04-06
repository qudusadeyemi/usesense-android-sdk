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
    private val encoderDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private var lastFrameTimeMs = 0L
    private val frameIntervalMs: Long = (1000L / uploadConfig.targetFps)

    var onFrameCaptured: ((CapturedFrame) -> Unit)? = null
    var onCaptureComplete: (() -> Unit)? = null

    val currentFrameIndex: Int get() = frameBuffer.currentIndex
    val capturedFrameCount: Int get() = frameBuffer.frameCount

    fun startCapture() {
        frameBuffer.startCapture()
        isCapturing.set(true)
        lastFrameTimeMs = 0L
    }

    fun stopCapture() {
        isCapturing.set(false)
    }

    fun getFrameBuffer(): FrameBuffer = frameBuffer

    fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (!isCapturing.get()) {
                imageProxy.close()
                return@Analyzer
            }

            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < frameIntervalMs) {
                imageProxy.close()
                return@Analyzer
            }
            lastFrameTimeMs = now

            // Convert ImageProxy to non-mirrored JPEG
            val jpegData = imageProxyToJpeg(imageProxy)
            val luminance = computeLuminance(imageProxy)
            imageProxy.close()

            if (jpegData != null) {
                val frame = frameBuffer.addFrame(jpegData, luminance)
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

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
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

            FrameEncoder.bitmapToJpeg(correctedBitmap).also {
                correctedBitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compute average luminance for a frame (downscaled to 64x48).
     * Uses ITU-R BT.601: L = 0.299*R + 0.587*G + 0.114*B
     * This feeds the Suspicion Engine's brightness stability signal.
     */
    private fun computeLuminance(imageProxy: ImageProxy): Double {
        return try {
            val bitmap = imageProxy.toBitmap()
            val scaled = Bitmap.createScaledBitmap(bitmap, 64, 48, true)
            if (scaled !== bitmap) bitmap.recycle()

            var totalLuminance = 0.0
            val width = scaled.width
            val height = scaled.height
            val pixelCount = width * height
            val pixels = IntArray(pixelCount)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            scaled.recycle()

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
}
