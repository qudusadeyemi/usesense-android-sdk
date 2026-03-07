package com.usesense.sdk.capture

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Real-time image quality analysis running at 4Hz on downsampled grayscale frames.
 *
 * Pipeline:
 * 1. Downsample to 160x120 grayscale (ITU-R BT.601)
 * 2. Blur detection via Laplacian variance
 * 3. Lighting analysis (mean brightness, contrast, exposure ratios)
 * 4. Build guidance messages with blur suppression when lighting is bad
 */
class ImageQualityAnalyzer {

    companion object {
        private const val ANALYSIS_WIDTH = 160
        private const val ANALYSIS_HEIGHT = 120

        // Blur thresholds (Laplacian variance)
        private const val BLUR_POOR = 30.0
        private const val BLUR_GOOD = 80.0

        // Lighting thresholds
        private const val BRIGHTNESS_TOO_DARK = 55.0
        private const val BRIGHTNESS_SLIGHTLY_DARK = 80.0
        private const val BRIGHTNESS_GOOD_MAX = 180.0
        private const val BRIGHTNESS_TOO_BRIGHT = 210.0

        // Contrast threshold
        private const val CONTRAST_LOW = 25.0
        private const val CONTRAST_VERY_LOW = 20.0

        // Exposure ratio thresholds
        private const val EXPOSURE_RATIO_BAD = 0.45
        private const val EXPOSURE_RATIO_ACCEPTABLE = 0.25

        // Under/over-exposure pixel value boundaries
        private const val UNDER_EXPOSED_THRESHOLD = 40
        private const val OVER_EXPOSED_THRESHOLD = 215

        // Overall score weights
        private const val BLUR_WEIGHT = 0.45
        private const val LIGHTING_WEIGHT = 0.55

        // Overall acceptable threshold
        private const val ACCEPTABLE_THRESHOLD = 35.0

        // Analysis interval (4Hz = 250ms)
        const val ANALYSIS_INTERVAL_MS = 250L
    }

    data class ImageQualityReport(
        val laplacianVariance: Double,
        val meanBrightness: Double,
        val contrastStdDev: Double,
        val underExposedRatio: Double,
        val overExposedRatio: Double,
        val isTooDark: Boolean,
        val isTooBright: Boolean,
        val blurLevel: QualityLevel,
        val lightingLevel: QualityLevel,
        val overallScore: Double,
        val isAcceptable: Boolean,
        val guidance: List<QualityGuidance>,
    )

    enum class QualityLevel { GOOD, ACCEPTABLE, POOR }

    enum class GuidanceSeverity { INFO, WARNING, CRITICAL }

    enum class GuidanceIcon { BLUR, DARK, BRIGHT, CONTRAST }

    data class QualityGuidance(
        val message: String,
        val severity: GuidanceSeverity,
        val icon: GuidanceIcon,
    )

    /**
     * Analyze a YUV_420_888 image from CameraX ImageAnalysis.
     */
    fun analyze(image: Image): ImageQualityReport {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${image.format}"
        }
        val yPlane = image.planes[0]
        val grayscale = downsampleYPlane(
            yPlane.buffer, image.width, image.height,
            yPlane.rowStride, yPlane.pixelStride
        )
        return analyzeGrayscale(grayscale, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
    }

    /**
     * Analyze a Bitmap (for testing or alternative capture paths).
     */
    fun analyze(bitmap: Bitmap): ImageQualityReport {
        val grayscale = downsampleBitmap(bitmap)
        return analyzeGrayscale(grayscale, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
    }

    private fun analyzeGrayscale(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ImageQualityReport {
        // Step 2: Blur detection (Laplacian variance)
        val laplacianVariance = computeLaplacianVariance(pixels, width, height)

        // Step 3: Lighting analysis
        val (mean, stdDev) = computeMeanAndStdDev(pixels)
        val (underRatio, overRatio) = computeExposureRatios(pixels)

        val isTooDark = mean < BRIGHTNESS_TOO_DARK
        val isTooBright = mean > BRIGHTNESS_TOO_BRIGHT

        // Blur level
        val blurLevel = when {
            laplacianVariance < BLUR_POOR -> QualityLevel.POOR
            laplacianVariance < BLUR_GOOD -> QualityLevel.ACCEPTABLE
            else -> QualityLevel.GOOD
        }

        // Lighting level
        val lightingLevel = when {
            isTooDark || isTooBright -> QualityLevel.POOR
            underRatio > EXPOSURE_RATIO_BAD || overRatio > EXPOSURE_RATIO_BAD -> QualityLevel.POOR
            mean < BRIGHTNESS_SLIGHTLY_DARK || stdDev < CONTRAST_LOW -> QualityLevel.ACCEPTABLE
            else -> QualityLevel.GOOD
        }

        // Normalize scores to 0-100
        val blurScore = ((laplacianVariance / BLUR_GOOD) * 100).coerceIn(0.0, 100.0)
        val lightingScore = computeLightingScore(mean, stdDev, underRatio, overRatio)
        val overallScore = BLUR_WEIGHT * blurScore + LIGHTING_WEIGHT * lightingScore

        // Step 4: Build guidance
        val guidance = buildGuidance(
            laplacianVariance, mean, stdDev, underRatio, overRatio,
            isTooDark, isTooBright, overallScore
        )

        return ImageQualityReport(
            laplacianVariance = laplacianVariance,
            meanBrightness = mean,
            contrastStdDev = stdDev,
            underExposedRatio = underRatio,
            overExposedRatio = overRatio,
            isTooDark = isTooDark,
            isTooBright = isTooBright,
            blurLevel = blurLevel,
            lightingLevel = lightingLevel,
            overallScore = overallScore,
            isAcceptable = overallScore >= ACCEPTABLE_THRESHOLD,
            guidance = guidance,
        )
    }

    /**
     * Laplacian variance using 4-connected kernel: [0,1,0; 1,-4,1; 0,1,0]
     */
    private fun computeLaplacianVariance(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Double {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = pixels[y * width + x]
                val top = pixels[(y - 1) * width + x]
                val bottom = pixels[(y + 1) * width + x]
                val left = pixels[y * width + (x - 1)]
                val right = pixels[y * width + (x + 1)]

                val laplacian = (top + bottom + left + right - 4 * center).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    private fun computeMeanAndStdDev(pixels: IntArray): Pair<Double, Double> {
        if (pixels.isEmpty()) return Pair(0.0, 0.0)
        var sum = 0L
        for (p in pixels) sum += p
        val mean = sum.toDouble() / pixels.size

        var sumSq = 0.0
        for (p in pixels) {
            val diff = p - mean
            sumSq += diff * diff
        }
        val stdDev = sqrt(sumSq / pixels.size)
        return Pair(mean, stdDev)
    }

    private fun computeExposureRatios(pixels: IntArray): Pair<Double, Double> {
        if (pixels.isEmpty()) return Pair(0.0, 0.0)
        var underCount = 0
        var overCount = 0
        for (p in pixels) {
            if (p < UNDER_EXPOSED_THRESHOLD) underCount++
            if (p > OVER_EXPOSED_THRESHOLD) overCount++
        }
        return Pair(
            underCount.toDouble() / pixels.size,
            overCount.toDouble() / pixels.size
        )
    }

    private fun computeLightingScore(
        mean: Double,
        stdDev: Double,
        underRatio: Double,
        overRatio: Double,
    ): Double {
        // Brightness score: ideal range 80-180
        val brightnessScore = when {
            mean < BRIGHTNESS_TOO_DARK -> (mean / BRIGHTNESS_TOO_DARK) * 40.0
            mean < BRIGHTNESS_SLIGHTLY_DARK -> 40.0 + ((mean - BRIGHTNESS_TOO_DARK) / (BRIGHTNESS_SLIGHTLY_DARK - BRIGHTNESS_TOO_DARK)) * 30.0
            mean <= BRIGHTNESS_GOOD_MAX -> 70.0 + ((mean - BRIGHTNESS_SLIGHTLY_DARK) / (BRIGHTNESS_GOOD_MAX - BRIGHTNESS_SLIGHTLY_DARK)) * 30.0
            mean <= BRIGHTNESS_TOO_BRIGHT -> 100.0 - ((mean - BRIGHTNESS_GOOD_MAX) / (BRIGHTNESS_TOO_BRIGHT - BRIGHTNESS_GOOD_MAX)) * 30.0
            else -> 30.0 // too bright
        }

        // Contrast score
        val contrastScore = when {
            stdDev < CONTRAST_VERY_LOW -> (stdDev / CONTRAST_VERY_LOW) * 40.0
            stdDev < CONTRAST_LOW -> 40.0 + ((stdDev - CONTRAST_VERY_LOW) / (CONTRAST_LOW - CONTRAST_VERY_LOW)) * 30.0
            else -> 100.0
        }

        // Exposure penalty
        val exposurePenalty = ((underRatio + overRatio) * 50.0).coerceAtMost(50.0)

        return ((brightnessScore * 0.5 + contrastScore * 0.5) - exposurePenalty).coerceIn(0.0, 100.0)
    }

    /**
     * Build guidance messages with blur suppression when lighting is bad.
     *
     * CRITICAL: When the image is too dark or too bright, Laplacian variance is
     * unreliable (uniformly dark/bright frames have near-zero edge content). The
     * guidance builder suppresses all blur-related guidance when lighting is bad.
     */
    private fun buildGuidance(
        laplacianVariance: Double,
        mean: Double,
        stdDev: Double,
        underRatio: Double,
        overRatio: Double,
        isTooDark: Boolean,
        isTooBright: Boolean,
        overallScore: Double,
    ): List<QualityGuidance> {
        val guidance = mutableListOf<QualityGuidance>()

        // Lighting issues first (higher priority)
        if (isTooDark) {
            guidance.add(QualityGuidance(
                "Turn on the lights or move to a bright area",
                GuidanceSeverity.CRITICAL, GuidanceIcon.DARK
            ))
        } else if (isTooBright) {
            guidance.add(QualityGuidance(
                "Too bright \u2014 move away from direct light",
                GuidanceSeverity.CRITICAL, GuidanceIcon.BRIGHT
            ))
        }

        if (underRatio > EXPOSURE_RATIO_BAD) {
            guidance.add(QualityGuidance(
                "Image is too dark \u2014 add more lighting",
                GuidanceSeverity.CRITICAL, GuidanceIcon.DARK
            ))
        }

        if (overRatio > EXPOSURE_RATIO_BAD) {
            guidance.add(QualityGuidance(
                "Too much glare \u2014 reduce backlighting",
                GuidanceSeverity.CRITICAL, GuidanceIcon.BRIGHT
            ))
        }

        if (!isTooDark && !isTooBright && mean < BRIGHTNESS_SLIGHTLY_DARK && overallScore < 50) {
            guidance.add(QualityGuidance(
                "A bit dark \u2014 more light would help",
                GuidanceSeverity.WARNING, GuidanceIcon.DARK
            ))
        }

        if (stdDev < CONTRAST_VERY_LOW) {
            guidance.add(QualityGuidance(
                "Low contrast \u2014 adjust your lighting",
                GuidanceSeverity.WARNING, GuidanceIcon.CONTRAST
            ))
        }

        // Blur guidance: SUPPRESS when lighting is bad
        val lightingIsBad = isTooDark || isTooBright ||
            underRatio > EXPOSURE_RATIO_BAD || overRatio > EXPOSURE_RATIO_BAD
        if (!lightingIsBad) {
            if (laplacianVariance < BLUR_POOR) {
                guidance.add(QualityGuidance(
                    "Clean your camera lens or hold your device steady",
                    GuidanceSeverity.CRITICAL, GuidanceIcon.BLUR
                ))
            } else if (laplacianVariance < BLUR_GOOD && overallScore < 50) {
                guidance.add(QualityGuidance(
                    "Image is slightly blurry \u2014 hold still",
                    GuidanceSeverity.WARNING, GuidanceIcon.BLUR
                ))
            }
        }

        // Sort by severity: critical > warning > info
        guidance.sortBy { it.severity.ordinal }

        return guidance
    }

    /**
     * Downsample the Y plane of a YUV_420_888 image to a grayscale IntArray.
     */
    private fun downsampleYPlane(
        yBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        pixelStride: Int,
    ): IntArray {
        val result = IntArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
        val xStep = srcWidth.toFloat() / ANALYSIS_WIDTH
        val yStep = srcHeight.toFloat() / ANALYSIS_HEIGHT

        for (dy in 0 until ANALYSIS_HEIGHT) {
            val srcY = (dy * yStep).toInt().coerceAtMost(srcHeight - 1)
            for (dx in 0 until ANALYSIS_WIDTH) {
                val srcX = (dx * xStep).toInt().coerceAtMost(srcWidth - 1)
                val index = srcY * rowStride + srcX * pixelStride
                result[dy * ANALYSIS_WIDTH + dx] = (yBuffer.get(index).toInt() and 0xFF)
            }
        }
        return result
    }

    /**
     * Downsample a Bitmap to a grayscale IntArray using ITU-R BT.601.
     */
    private fun downsampleBitmap(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, true)
        val pixels = IntArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
        val argb = IntArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
        scaled.getPixels(argb, 0, ANALYSIS_WIDTH, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        for (i in argb.indices) {
            val r = (argb[i] shr 16) and 0xFF
            val g = (argb[i] shr 8) and 0xFF
            val b = argb[i] and 0xFF
            // ITU-R BT.601: Y = 0.299*R + 0.587*G + 0.114*B
            pixels[i] = ((0.299 * r + 0.587 * g + 0.114 * b)).toInt().coerceIn(0, 255)
        }
        if (scaled !== bitmap) scaled.recycle()
        return pixels
    }
}
