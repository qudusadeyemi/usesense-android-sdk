package com.usesense.sdk.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

internal object FrameEncoder {

    private const val JPEG_QUALITY = 82

    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
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
