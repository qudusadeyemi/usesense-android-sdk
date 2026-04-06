package com.usesense.sdk.liveness

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Client-side Presentation Attack Detection (PAD) engine.
 *
 * Runs alongside the face detection loop during capture, analyzing every 2nd frame.
 * Produces a 0-100 suspicion score (0=live, 100=screen replay).
 *
 * Four weighted signals:
 * - Pose Micro-Tremor (0.35): Real faces have irregular micro-jitter
 * - Temporal Smoothness (0.25): Screens produce unnaturally smooth motion
 * - Brightness Stability (0.20): Screens have very stable luminance
 * - Sharpness Pattern (0.20): Screens show low sharpness with high brightness
 *
 * MUST run on a background thread to avoid blocking the capture loop.
 */
internal class SuspicionEngine(
    private val suspicionThreshold: Int = 55,
) {
    // Sliding window of last 30 analysis frames
    private val windowSize = 30
    private val poseHistory = ArrayDeque<HeadPose>(windowSize)
    private val luminanceHistory = ArrayDeque<Double>(windowSize)
    private val sharpnessHistory = ArrayDeque<Double>(windowSize)
    private val timestampHistory = ArrayDeque<Long>(windowSize)

    private var framesAnalyzed = 0
    private var frameCounter = 0
    private var smoothedScore = 0.0

    @Volatile
    var currentScore: Int = 0
        private set

    @Volatile
    var triggered: Boolean = false
        private set

    private var latestSnapshot: SuspicionSnapshot? = null

    /**
     * Feed a new frame's data to the engine. Only processes every 2nd frame.
     *
     * @param pose Head pose from MediaPipe/ML Kit
     * @param luminance Average frame luminance (0-255)
     * @param sharpness Laplacian variance of the frame
     * @param timestampMs Frame capture timestamp
     */
    fun analyzeFrame(
        pose: HeadPose,
        luminance: Double,
        sharpness: Double,
        timestampMs: Long,
    ) {
        frameCounter++
        // Only analyze every 2nd frame
        if (frameCounter % 2 != 0) return

        addToWindow(pose, luminance, sharpness, timestampMs)
        framesAnalyzed++

        if (framesAnalyzed < 3) return // Need minimum data

        val microTremorScore = computeMicroTremorScore()
        val temporalSmoothnessScore = computeTemporalSmoothnessScore()
        val brightnessStabilityScore = computeBrightnessStabilityScore()
        val sharpnessPatternScore = computeSharpnessPatternScore()

        // Weighted average
        val instantScore = (
            microTremorScore * WEIGHT_MICRO_TREMOR +
                temporalSmoothnessScore * WEIGHT_TEMPORAL_SMOOTHNESS +
                brightnessStabilityScore * WEIGHT_BRIGHTNESS_STABILITY +
                sharpnessPatternScore * WEIGHT_SHARPNESS_PATTERN
            ).toInt().coerceIn(0, 100)

        // Exponential moving average
        smoothedScore = EMA_ALPHA * instantScore + (1 - EMA_ALPHA) * smoothedScore
        currentScore = smoothedScore.toInt()

        latestSnapshot = SuspicionSnapshot(
            score = currentScore,
            signals = listOf(
                SuspicionSignal("micro_tremor", microTremorScore.toInt(), WEIGHT_MICRO_TREMOR,
                    "median_delta=${computeMedianPoseDelta()}"),
                SuspicionSignal("temporal_smoothness", temporalSmoothnessScore.toInt(), WEIGHT_TEMPORAL_SMOOTHNESS,
                    "jerk_score=${computeJerkScore()}"),
                SuspicionSignal("brightness_stability", brightnessStabilityScore.toInt(), WEIGHT_BRIGHTNESS_STABILITY,
                    "cv=${computeLuminanceCV()}"),
                SuspicionSignal("sharpness_pattern", sharpnessPatternScore.toInt(), WEIGHT_SHARPNESS_PATTERN,
                    "avg_sharpness=${sharpnessHistory.average()}"),
            ),
            framesAnalyzed = framesAnalyzed,
            reliable = framesAnalyzed >= 6,
            timestamp = timestampMs,
        )

        // Trigger check
        if (currentScore >= suspicionThreshold && framesAnalyzed >= 6) {
            triggered = true
        }
    }

    fun getSnapshot(): SuspicionSnapshot? = latestSnapshot

    /**
     * Pose Micro-Tremor: Real faces have irregular 0.08-0.8 degree jitter.
     * Screens are stable (<0.05 deg) or unnaturally smooth.
     * Returns 0 (live) to 100 (screen).
     */
    private fun computeMicroTremorScore(): Double {
        if (poseHistory.size < 3) return 50.0

        val deltas = mutableListOf<Double>()
        val poses = poseHistory.toList()
        for (i in 1 until poses.size) {
            val dy = abs(poses[i].yaw - poses[i - 1].yaw)
            val dp = abs(poses[i].pitch - poses[i - 1].pitch)
            val dr = abs(poses[i].roll - poses[i - 1].roll)
            deltas.add(dy + dp + dr)
        }

        val medianDelta = deltas.sorted()[deltas.size / 2]

        // Screen: very stable (<0.05) or unnaturally smooth
        // Live: irregular 0.08-0.8 degree jitter
        return when {
            medianDelta < 0.05 -> 90.0  // Too stable → likely screen
            medianDelta < 0.08 -> 70.0
            medianDelta > 3.0 -> 60.0    // Extreme motion → suspicious
            else -> {
                // Natural range: score inversely with variance
                val variance = deltas.map { (it - deltas.average()).let { d -> d * d } }.average()
                val varianceScore = if (variance > 0.01) 10.0 else 50.0
                varianceScore
            }
        }
    }

    private fun computeMedianPoseDelta(): Double {
        if (poseHistory.size < 2) return 0.0
        val poses = poseHistory.toList()
        val deltas = (1 until poses.size).map { i ->
            abs(poses[i].yaw - poses[i - 1].yaw) +
                abs(poses[i].pitch - poses[i - 1].pitch) +
                abs(poses[i].roll - poses[i - 1].roll)
        }
        return if (deltas.isEmpty()) 0.0 else deltas.sorted()[deltas.size / 2]
    }

    /**
     * Temporal Smoothness: Screens produce smooth motion (low jerk, low zero-crossing rate).
     * Real faces have frequent direction reversals.
     */
    private fun computeTemporalSmoothnessScore(): Double {
        if (poseHistory.size < 4) return 50.0

        val jerk = computeJerkScore()
        val zeroCrossings = computeZeroCrossingRate()

        // Low jerk + low zero-crossing = smooth (screen-like)
        val jerkScore = if (jerk < 0.01) 80.0 else if (jerk < 0.05) 40.0 else 10.0
        val zcrScore = if (zeroCrossings < 0.2) 80.0 else if (zeroCrossings < 0.4) 40.0 else 10.0

        return (jerkScore * 0.5 + zcrScore * 0.5)
    }

    private fun computeJerkScore(): Double {
        val poses = poseHistory.toList()
        if (poses.size < 4) return 0.0

        // Compute second derivative (acceleration changes) of yaw
        val velocities = (1 until poses.size).map { i -> poses[i].yaw - poses[i - 1].yaw }
        val accelerations = (1 until velocities.size).map { i -> velocities[i] - velocities[i - 1] }
        val jerks = (1 until accelerations.size).map { i -> abs(accelerations[i] - accelerations[i - 1]) }

        return if (jerks.isEmpty()) 0.0 else jerks.average()
    }

    private fun computeZeroCrossingRate(): Double {
        val poses = poseHistory.toList()
        if (poses.size < 3) return 0.5

        val velocities = (1 until poses.size).map { i -> poses[i].yaw - poses[i - 1].yaw }
        var crossings = 0
        for (i in 1 until velocities.size) {
            if ((velocities[i] > 0 && velocities[i - 1] < 0) ||
                (velocities[i] < 0 && velocities[i - 1] > 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / velocities.size.coerceAtLeast(1)
    }

    /**
     * Brightness Stability: Screens have very stable luminance (CV < 0.02).
     * Real faces have natural fluctuation (CV > 0.05).
     */
    private fun computeBrightnessStabilityScore(): Double {
        if (luminanceHistory.size < 3) return 50.0

        val cv = computeLuminanceCV()
        val avgLuminance = luminanceHistory.average()

        // Very stable brightness, especially in screen-typical range (65-90)
        val stabilityScore = when {
            cv < 0.02 -> 85.0
            cv < 0.05 -> 50.0
            else -> 10.0
        }

        // Bonus suspicion if in screen-typical luminance range
        val rangePenalty = if (avgLuminance in 65.0..90.0 && cv < 0.03) 10.0 else 0.0

        return (stabilityScore + rangePenalty).coerceIn(0.0, 100.0)
    }

    private fun computeLuminanceCV(): Double {
        if (luminanceHistory.size < 2) return 0.0
        val mean = luminanceHistory.average()
        if (mean < 1.0) return 0.0
        val variance = luminanceHistory.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance) / mean
    }

    /**
     * Sharpness Pattern: Screens show low sharpness (<45) with high brightness
     * and uniform sharpness across frames.
     */
    private fun computeSharpnessPatternScore(): Double {
        if (sharpnessHistory.size < 3) return 50.0

        val avgSharpness = sharpnessHistory.average()
        val avgLuminance = luminanceHistory.average()
        val sharpnessVariance = sharpnessHistory.map { (it - avgSharpness).let { d -> d * d } }.average()

        // Low sharpness + high brightness + uniform = screen
        val baseScore = when {
            avgSharpness < 45 && avgLuminance > 60 -> 80.0
            avgSharpness < 50 -> 50.0
            else -> 15.0
        }

        // Uniform sharpness across frames is suspicious
        val uniformityPenalty = if (sharpnessVariance < 5.0) 15.0 else 0.0

        return (baseScore + uniformityPenalty).coerceIn(0.0, 100.0)
    }

    private fun addToWindow(pose: HeadPose, luminance: Double, sharpness: Double, timestampMs: Long) {
        if (poseHistory.size >= windowSize) poseHistory.removeFirst()
        if (luminanceHistory.size >= windowSize) luminanceHistory.removeFirst()
        if (sharpnessHistory.size >= windowSize) sharpnessHistory.removeFirst()
        if (timestampHistory.size >= windowSize) timestampHistory.removeFirst()

        poseHistory.addLast(pose)
        luminanceHistory.addLast(luminance)
        sharpnessHistory.addLast(sharpness)
        timestampHistory.addLast(timestampMs)
    }

    fun reset() {
        poseHistory.clear()
        luminanceHistory.clear()
        sharpnessHistory.clear()
        timestampHistory.clear()
        framesAnalyzed = 0
        frameCounter = 0
        smoothedScore = 0.0
        currentScore = 0
        triggered = false
        latestSnapshot = null
    }

    companion object {
        const val WEIGHT_MICRO_TREMOR = 0.35
        const val WEIGHT_TEMPORAL_SMOOTHNESS = 0.25
        const val WEIGHT_BRIGHTNESS_STABILITY = 0.20
        const val WEIGHT_SHARPNESS_PATTERN = 0.20
        const val EMA_ALPHA = 0.3
    }
}
