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
            imageProxy.close()

            if (jpegData != null) {
                val frame = frameBuffer.addFrame(jpegData)
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
            val bitmap = imageProxy.toBitmap()
            // CRITICAL: Do NOT mirror. The preview is mirrored for the user,
            // but captured frames must be raw/non-mirrored for server analysis.
            FrameEncoder.bitmapToJpeg(bitmap).also {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        isCapturing.set(false)
        frameBuffer.clear()
    }
}
