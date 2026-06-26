package com.usesense.sdk.ui.compose.screens

import android.graphics.Bitmap

/**
 * Client-side document quality check, ported from the hosted page's
 * assessDocumentQuality (frontend/.../document-quality.ts) with the same metrics
 * and calibrated thresholds: variance-of-Laplacian (sharpness), mean luma, and
 * source short-side. Returns the highest-priority issue, or null if the capture
 * looks fine. Never throws.
 */
object DocumentQuality {
    private const val WORKING_W = 320
    private const val BLUR_VARIANCE_THRESHOLD = 1000.0
    private const val DARK_LUMA = 55.0
    private const val BRIGHT_LUMA = 245.0
    private const val MIN_SHORT_SIDE = 500

    data class Issue(val code: String, val title: String, val detail: String)

    fun assess(bitmap: Bitmap): Issue? {
        val natW = bitmap.width
        val natH = bitmap.height
        if (natW <= 0 || natH <= 0) return null
        val shortSide = minOf(natW, natH)

        val w = WORKING_W
        val h = maxOf(1, Math.round(WORKING_W.toDouble() * natH / natW).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)

        val gray = DoubleArray(w * h)
        var lumaSum = 0.0
        for (i in 0 until w * h) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val v = 0.299 * r + 0.587 * g + 0.114 * b
            gray[i] = v
            lumaSum += v
        }
        val meanLuma = lumaSum / (w * h)

        var s = 0.0
        var s2 = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                s += lap
                s2 += lap * lap
                n++
            }
        }
        val sharpness = if (n > 0) s2 / n - (s / n) * (s / n) else 9999.0

        // Priority: lighting first, then size, then sharpness.
        if (meanLuma < DARK_LUMA) {
            return Issue("too_dark", "Too dark", "Find brighter, even lighting and try again.")
        }
        if (meanLuma > BRIGHT_LUMA) {
            return Issue("too_bright", "Too bright", "Move out of direct light or reduce glare.")
        }
        if (shortSide < MIN_SHORT_SIDE) {
            return Issue("low_res", "Image looks small", "Capture at a higher resolution so the text is readable.")
        }
        if (sharpness < BLUR_VARIANCE_THRESHOLD) {
            return Issue("blurry", "Photo may be unclear", "Make sure all details are sharp and readable.")
        }
        return null
    }
}
