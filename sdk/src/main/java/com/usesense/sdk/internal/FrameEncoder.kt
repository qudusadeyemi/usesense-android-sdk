package com.usesense.sdk.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

internal object FrameEncoder {

    private const val JPEG_QUALITY = 82

    /**
     * Longest-edge cap for encoded frames.
     *
     * Matches MAX_FRAME_LONG_EDGE in the web SDK and maxFrameLongEdge on iOS,
     * and the calibration table in the server's screen-replay-detector.tsx.
     * Chosen by measuring 198 real frames through the pipeline: face matching
     * is unaffected, and the binding constraint is Rekognition's sharpness
     * score, which stays clear of the screen-replay ceiling at 960 but not
     * below it. Keep all four in step.
     *
     * The legacy capture path already runs at 640x480 and is untouched by this;
     * it matters for the v4 path, which requests 1280x720.
     */
    const val MAX_FRAME_LONG_EDGE = 960

    /**
     * Encode a bitmap to JPEG, capping the longest edge at [MAX_FRAME_LONG_EDGE].
     *
     * Both capture paths funnel through here, so this is the single place the
     * cap needs to hold.
     */
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val source = if (longEdge > MAX_FRAME_LONG_EDGE && longEdge > 0) {
            val scale = MAX_FRAME_LONG_EDGE.toFloat() / longEdge
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            // filter=true so the downscale is resampled rather than dropped:
            // aliasing reads as lost sharpness to the server's screen-replay
            // detector, which is the one signal this can hurt.
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        if (source !== bitmap) source.recycle()
        return stream.toByteArray()
    }

    fun yuvToJpeg(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        format: Int = ImageFormat.NV21,
        quality: Int = JPEG_QUALITY,
    ): ByteArray {
        val yuvImage = YuvImage(yuvData, format, width, height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, stream)
        return stream.toByteArray()
    }

    fun jpegToDownscaled(jpegData: ByteArray, maxWidth: Int = 640, maxHeight: Int = 480): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, options)

        if (options.outWidth <= maxWidth && options.outHeight <= maxHeight) {
            return jpegData
        }

        val widthRatio = options.outWidth.toFloat() / maxWidth
        val heightRatio = options.outHeight.toFloat() / maxHeight
        val sampleSize = maxOf(widthRatio, heightRatio).toInt().coerceAtLeast(1)

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, decodeOptions)
            ?: return jpegData
        val result = bitmapToJpeg(bitmap)
        bitmap.recycle()
        return result
    }
}
