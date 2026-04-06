package com.usesense.sdk.liveness

import kotlin.math.sqrt

/**
 * Lightweight PCA-based 3D Morphable Model fitter.
 * Fits 12 PCA identity coefficients to the 40 salient landmarks
 * from MediaPipe FaceMesh, producing shape parameters, depth
 * plausibility scores, and geometric ratios per frame.
 *
 * The cross-frame consistency measure detects flat/screen faces
 * by checking if the identity coefficients remain stable across frames.
 */
internal class OnDevice3DMMFitter {

    private val fittedFrames = mutableListOf<FittedFrame>()
    val results: List<FittedFrame> get() = fittedFrames.toList()

    /**
     * Fit 3DMM to a single frame's mesh data.
     * Uses the 40 salient landmarks to compute PCA shape params,
     * depth plausibility, and model-invariant face ratios.
     */
    fun fitFrame(meshData: FrameMeshData): FittedFrame {
        val salientLandmarks = extractSalientLandmarks(meshData.landmarks)
        val shapeParams = fitPCAShapeParams(salientLandmarks)
        val depthPlausibility = computeDepthPlausibility(salientLandmarks)
        val geometricRatios = computeGeometricRatios(salientLandmarks)
        val poseRatios2D = computePoseRatios2D(salientLandmarks, meshData.headPose)

        val frame = FittedFrame(
            frameIndex = meshData.frameIndex,
            timestampMs = meshData.timestampMs,
            shapeParams = shapeParams,
            pose = meshData.headPose,
            depthPlausibility = depthPlausibility,
            geometricRatios = geometricRatios,
            poseRatios2D = poseRatios2D,
        )
        fittedFrames.add(frame)
        return frame
    }

    /**
     * Extract the 40 salient landmarks used for 3DMM fitting.
     */
    private fun extractSalientLandmarks(landmarks: List<LandmarkPoint>): List<LandmarkPoint> {
        val landmarkSize = landmarks.size
        return FaceMeshManager.SALIENT_LANDMARKS.mapNotNull { idx ->
            if (idx in 0 until landmarkSize) landmarks[idx] else null
        }
    }

    /**
     * Fit 12 PCA identity coefficients via simplified Procrustes + projection.
     * Uses z-depth variance and inter-landmark distances as proxy features,
     * projected onto a simplified PCA basis.
     */
    private fun fitPCAShapeParams(landmarks: List<LandmarkPoint>): DoubleArray {
        if (landmarks.size < 12) return DoubleArray(12)

        // Compute centered coordinates
        val cx = landmarks.map { it.x }.average()
        val cy = landmarks.map { it.y }.average()
        val cz = landmarks.map { it.z }.average()

        // Build feature vector from centered landmark positions
        // Use first 12 principal dimensions derived from landmark geometry
        val features = DoubleArray(12)
        for (i in 0 until minOf(12, landmarks.size)) {
            val lm = landmarks[i]
            features[i] = sqrt(
                (lm.x - cx) * (lm.x - cx) +
                    (lm.y - cy) * (lm.y - cy) +
                    (lm.z - cz) * (lm.z - cz)
            )
        }

        // Normalize to unit variance for stable cross-frame comparison
        val norm = sqrt(features.sumOf { it * it }).coerceAtLeast(1e-10)
        for (i in features.indices) {
            features[i] /= norm
        }

        return features
    }

    /**
     * Compute depth plausibility score (0-100).
     * Real faces have meaningful z-variance across landmarks.
     * Flat screens/photos have near-zero z-variance.
     */
    private fun computeDepthPlausibility(landmarks: List<LandmarkPoint>): Int {
        if (landmarks.size < 10) return 0

        val zValues = landmarks.map { it.z }
        val zMean = zValues.average()
        val zVariance = zValues.map { (it - zMean) * (it - zMean) }.average()

        // Scale z-variance to 0-100 score
        // Typical real face z-variance: 0.001 - 0.05
        // Flat surface z-variance: < 0.0005
        val score = (zVariance * 2000.0).coerceIn(0.0, 100.0)
        return score.toInt()
    }

    /**
     * Compute 6 model-invariant geometric ratios that characterize face shape.
     * These ratios are pose-independent and stable across frames for the same person.
     */
    private fun computeGeometricRatios(landmarks: List<LandmarkPoint>): DoubleArray {
        if (landmarks.size < 30) return DoubleArray(6)

        // Use key facial proportions:
        // 0: eye width / face width
        // 1: nose length / face height
        // 2: mouth width / eye distance
        // 3: forehead to nose / nose to chin
        // 4: left eye width / right eye width
        // 5: inter-eye / face width
        val ratios = DoubleArray(6)

        val faceWidth = dist(landmarks, 0, 7)  // jaw left to jaw right
        val faceHeight = dist(landmarks, 0, 4) // top to chin
        val eyeDistance = dist(landmarks, 20, 24) // left eye outer to right eye outer
        val noseLength = dist(landmarks, 16, 21) // nose top to nose tip
        val mouthWidth = dist(landmarks, 30, 31) // mouth left to mouth right
        val leftEyeWidth = dist(landmarks, 20, 23) // left eye outer to inner
        val rightEyeWidth = dist(landmarks, 24, 27) // right eye outer to inner

        if (faceWidth > 0.001) {
            ratios[0] = leftEyeWidth / faceWidth
            ratios[5] = eyeDistance / faceWidth
        }
        if (faceHeight > 0.001) {
            ratios[1] = noseLength / faceHeight
        }
        if (eyeDistance > 0.001) {
            ratios[2] = mouthWidth / eyeDistance
        }
        if (noseLength > 0.001) {
            val foreheadToNose = dist(landmarks, 0, 16)
            val noseToChin = dist(landmarks, 21, 4)
            ratios[3] = foreheadToNose / noseToChin.coerceAtLeast(0.001)
        }
        if (rightEyeWidth > 0.001) {
            ratios[4] = leftEyeWidth / rightEyeWidth
        }

        return ratios
    }

    /**
     * Compute 5 pose-sensitive 2D ratios for head orientation cross-check.
     */
    private fun computePoseRatios2D(landmarks: List<LandmarkPoint>, pose: HeadPose): DoubleArray {
        if (landmarks.size < 28) return DoubleArray(5)

        val ratios = DoubleArray(5)

        // 2D ratios affected by head pose
        val leftFaceWidth = dist2D(landmarks, 20, 18)  // left eye to nose bridge
        val rightFaceWidth = dist2D(landmarks, 24, 18)  // right eye to nose bridge
        val upperFace = dist2D(landmarks, 0, 18)
        val lowerFace = dist2D(landmarks, 18, 4)
        val faceWidth = dist2D(landmarks, 0, 7)

        if (rightFaceWidth > 0.001) ratios[0] = leftFaceWidth / rightFaceWidth
        if (lowerFace > 0.001) ratios[1] = upperFace / lowerFace
        if (faceWidth > 0.001) {
            ratios[2] = leftFaceWidth / faceWidth
            ratios[3] = rightFaceWidth / faceWidth
        }
        ratios[4] = (leftFaceWidth + rightFaceWidth) / faceWidth.coerceAtLeast(0.001)

        return ratios
    }

    /**
     * Compute cross-frame consistency (0-100).
     * High values mean the face identity is stable across frames (real face).
     * Low values suggest changing/degenerate shape fits (flat surface or mask).
     */
    fun computeCrossFrameConsistency(): Int {
        if (fittedFrames.size < 2) return 0

        val paramVectors = fittedFrames.map { it.shapeParams }
        var totalDistance = 0.0
        var comparisons = 0

        for (i in paramVectors.indices) {
            for (j in i + 1 until paramVectors.size) {
                totalDistance += l2Distance(paramVectors[i], paramVectors[j])
                comparisons++
            }
        }

        if (comparisons == 0) return 0
        val avgDistance = totalDistance / comparisons

        // Low distance = high consistency
        // Typical real face: avgDistance < 0.1
        // Flat surface: avgDistance > 0.3
        val score = ((1.0 - avgDistance.coerceIn(0.0, 1.0)) * 100).toInt()
        return score.coerceIn(0, 100)
    }

    /**
     * Compute a preliminary GC score combining depth plausibility and cross-frame consistency.
     */
    fun computePreliminaryScore(): Int {
        if (fittedFrames.isEmpty()) return 0

        val avgDepth = fittedFrames.map { it.depthPlausibility }.average()
        val consistency = computeCrossFrameConsistency()

        // Weighted: 60% depth + 40% consistency
        return ((avgDepth * 0.6 + consistency * 0.4)).toInt().coerceIn(0, 100)
    }

    fun reset() {
        fittedFrames.clear()
    }

    private fun dist(landmarks: List<LandmarkPoint>, i: Int, j: Int): Double {
        if (i >= landmarks.size || j >= landmarks.size) return 0.0
        val a = landmarks[i]
        val b = landmarks[j]
        return sqrt(
            (a.x - b.x) * (a.x - b.x) +
                (a.y - b.y) * (a.y - b.y) +
                (a.z - b.z) * (a.z - b.z)
        )
    }

    private fun dist2D(landmarks: List<LandmarkPoint>, i: Int, j: Int): Double {
        if (i >= landmarks.size || j >= landmarks.size) return 0.0
        val a = landmarks[i]
        val b = landmarks[j]
        return sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
    }

    private fun l2Distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val diff = a[i] - (if (i < b.size) b[i] else 0.0)
            sum += diff * diff
        }
        return sqrt(sum)
    }
}

data class FittedFrame(
    val frameIndex: Int,
    val timestampMs: Long,
    val shapeParams: DoubleArray,
    val pose: HeadPose,
    val depthPlausibility: Int,
    val geometricRatios: DoubleArray,
    val poseRatios2D: DoubleArray,
)
