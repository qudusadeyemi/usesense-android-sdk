package com.usesense.sdk.signals

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes screen detection signals from captured frames per spec Chapter 5.2.
 *
 * These signals help the server detect screen replay attacks:
 * - luminance_histogram_spread: Width of luminance distribution (0-1)
 * - edge_energy_ratio: High-frequency to total energy ratio (0-1)
 * - frame_luminance_cv: Coefficient of variation of luminance across frames
 * - color_channel_uniformity: How uniform RGB channels are (0-1)
 */
internal class ScreenDetectionAnalyzer {

    private val frameLuminances = mutableListOf<Double>()
    private var accumulatedHistogramSpread = 0.0
    private var accumulatedEdgeEnergy = 0.0
    private var accumulatedColorUniformity = 0.0
    private var frameCount = 0

    /**
     * Analyze a single frame for screen detection signals.
     * Should be called on a background thread.
     */
    fun analyzeFrame(bitmap: Bitmap, luminance: Double) {
        frameLuminances.add(luminance)

        // Downscale for efficient analysis
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 48, true)
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        accumulatedHistogramSpread += computeHistogramSpread(pixels)
        accumulatedEdgeEnergy += computeEdgeEnergyRatio(pixels, w, h)
        accumulatedColorUniformity += computeColorChannelUniformity(pixels)
        frameCount++
    }

    /**
     * Build the screen_detection JSON object for channel_integrity.
     */
    fun toJson(): JSONObject {
        val json = JSONObject()

        val avgSpread = if (frameCount > 0) accumulatedHistogramSpread / frameCount else 0.0
        val avgEdge = if (frameCount > 0) accumulatedEdgeEnergy / frameCount else 0.0
        val avgUniformity = if (frameCount > 0) accumulatedColorUniformity / frameCount else 0.0
        val luminanceCV = computeFrameLuminanceCV()

        json.put("luminance_histogram_spread", avgSpread)
        json.put("edge_energy_ratio", avgEdge)
        json.put("frame_luminance_cv", luminanceCV)
        json.put("color_channel_uniformity", avgUniformity)

        return json
    }

    /**
     * Luminance histogram spread: how wide the distribution of brightness values is.
     * Screens tend to have narrow distributions (low spread).
     */
    private fun computeHistogramSpread(pixels: IntArray): Double {
        val luminances = pixels.map { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b)
        }

        val min = luminances.minOrNull() ?: 0.0
        val max = luminances.maxOrNull() ?: 0.0

        // Spread normalized to 0-1
        return ((max - min) / 255.0).coerceIn(0.0, 1.0)
    }

    /**
     * Edge energy ratio: Laplacian-like high-frequency energy to total energy.
     * Screens showing photos/videos tend to have lower edge energy due to
     * re-capture blur and moiré patterns.
     */
    private fun computeEdgeEnergyRatio(pixels: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.5

        var edgeEnergy = 0.0
        var totalEnergy = 0.0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = luminanceOf(pixels[y * w + x])
                val left = luminanceOf(pixels[y * w + (x - 1)])
                val right = luminanceOf(pixels[y * w + (x + 1)])
                val top = luminanceOf(pixels[(y - 1) * w + x])
                val bottom = luminanceOf(pixels[(y + 1) * w + x])

                // Laplacian approximation
                val laplacian = abs(4 * center - left - right - top - bottom)
                edgeEnergy += laplacian
                totalEnergy += center
            }
        }

        return if (totalEnergy > 0) {
            (edgeEnergy / totalEnergy).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
    }

    /**
     * Color channel uniformity: how similar R, G, B channels are to each other.
     * Screens tend to produce more uniform (less distinct) color channels.
     */
    private fun computeColorChannelUniformity(pixels: IntArray): Double {
        var totalR = 0.0
        var totalG = 0.0
        var totalB = 0.0
        val count = pixels.size.coerceAtLeast(1)

        for (pixel in pixels) {
            totalR += (pixel shr 16) and 0xFF
            totalG += (pixel shr 8) and 0xFF
            totalB += pixel and 0xFF
        }

        val avgR = totalR / count
        val avgG = totalG / count
        val avgB = totalB / count

        // Compute variance between channels
        val mean = (avgR + avgG + avgB) / 3.0
        val variance = ((avgR - mean) * (avgR - mean) +
            (avgG - mean) * (avgG - mean) +
            (avgB - mean) * (avgB - mean)) / 3.0

        // High uniformity = low variance between channels
        // Normalize: variance of 0 → uniformity 1.0, variance of 2500+ → uniformity 0.0
        return (1.0 - (variance / 2500.0)).coerceIn(0.0, 1.0)
    }

    /**
     * Coefficient of variation of per-frame luminance.
     * Screens have very stable luminance (low CV).
     */
    private fun computeFrameLuminanceCV(): Double {
        if (frameLuminances.size < 2) return 0.0
        val mean = frameLuminances.average()
        if (mean < 1.0) return 0.0
        val variance = frameLuminances.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance) / mean
    }

    private fun luminanceOf(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    fun reset() {
        frameLuminances.clear()
        accumulatedHistogramSpread = 0.0
        accumulatedEdgeEnergy = 0.0
        accumulatedColorUniformity = 0.0
        frameCount = 0
    }
}
