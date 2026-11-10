package com.usesense.sdk.capture.scan

import kotlin.math.max

/**
 * Maps a point in the upright, unmirrored analysis image to the on-screen
 * preview: PreviewView's fill-centre scale and crop, then the front-camera
 * mirror. The gate checks "inside the oval" in this space, because the oval
 * is drawn on screen and the preview crops the image's sides.
 */
internal class PreviewMapper(
    imageWidth: Int,
    imageHeight: Int,
    private val viewWidth: Float,
    viewHeight: Float,
    private val mirror: Boolean = true,
) : PointMapper {
    private val scale = max(viewWidth / imageWidth.coerceAtLeast(1), viewHeight / imageHeight.coerceAtLeast(1))
    private val dx = (viewWidth - imageWidth * scale) / 2f
    private val dy = (viewHeight - imageHeight * scale) / 2f

    override fun map(x: Float, y: Float, out: FloatArray) {
        val sx = x * scale + dx
        out[0] = if (mirror) viewWidth - sx else sx
        out[1] = y * scale + dy
    }

    /** View pixels per image pixel. */
    val pixelScale: Float get() = scale
}
