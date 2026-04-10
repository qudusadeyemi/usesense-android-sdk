package com.usesense.sdk.liveness

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps MediaPipe FaceLandmarker for per-frame 468-landmark face mesh detection.
 * Runs on a background thread to avoid blocking the camera capture loop.
 *
 * The face_landmarker.task model is bundled in the AAR's assets/ directory and
 * managed by the mediapipe-sdk-sync workflow in qudusadeyemi/usesense-watchtower.
 * See MediaPipeModelInfo for the bundled bytes' version, sha256, and provenance.
 */
internal class FaceMeshManager(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null
    private val _frameMeshData = mutableListOf<FrameMeshData>()
    val frameMeshData: List<FrameMeshData> get() = _frameMeshData.toList()

    /**
     * Initialize the FaceLandmarker using the bundled face_landmarker.task asset.
     * Should be called before capture begins. Returns true if initialization
     * succeeded, false if MediaPipe failed to load the model. The most common
     * failure case is the bundled asset being missing, which happens between
     * this PR landing and the first mediapipe-sdk-sync workflow run delivering
     * the canonical bytes (or in a downstream consumer's repacked AAR that
     * stripped the assets/ directory). The SDK gracefully degrades to no face
     * mesh signals when this returns false; verification still completes with
     * degraded liveness scoring.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MediaPipeModelInfo.ASSET_FILENAME)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(true)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            false
        }
    }

    val isAvailable: Boolean get() = faceLandmarker != null

    /**
     * Process a single frame through MediaPipe FaceLandmarker.
     * Extracts head pose, eye aspect ratios, bounding box, and all 468 landmarks.
     * Returns null if no face is detected or MediaPipe is not available.
     */
    suspend fun processFrame(
        bitmap: Bitmap,
        frameIndex: Int,
        timestampMs: Long,
    ): FrameMeshData? = withContext(Dispatchers.Default) {
        val landmarker = faceLandmarker ?: return@withContext null

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.faceLandmarks().isEmpty()) return@withContext null

            val landmarks = result.faceLandmarks()[0]
            if (landmarks.size < 468) return@withContext null

            val headPose = extractHeadPose(landmarks)
            val leftEAR = computeEAR(landmarks, LEFT_EYE_INDICES)
            val rightEAR = computeEAR(landmarks, RIGHT_EYE_INDICES)
            val bbox = computeBoundingBox(landmarks)

            val landmarkPoints = landmarks.map { lm ->
                LandmarkPoint(lm.x().toDouble(), lm.y().toDouble(), lm.z().toDouble())
            }

            val data = FrameMeshData(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                headPose = headPose,
                leftEAR = leftEAR,
                rightEAR = rightEAR,
                bbox = bbox,
                landmarks = landmarkPoints,
            )

            synchronized(_frameMeshData) {
                _frameMeshData.add(data)
            }
            data
        } catch (e: Exception) {
            null
        }
    }

    private fun extractHeadPose(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): HeadPose {
        // Estimate head pose from key landmarks (nose tip, chin, eyes, forehead)
        val noseTip = landmarks[1]
        val chin = landmarks[152]
        val leftEyeOuter = landmarks[33]
        val rightEyeOuter = landmarks[263]
        val forehead = landmarks[10]

        // Simplified pose estimation from 2D/3D landmark positions
        val faceWidth = rightEyeOuter.x() - leftEyeOuter.x()
        val noseCenterX = noseTip.x()
        val faceCenterX = (leftEyeOuter.x() + rightEyeOuter.x()) / 2f

        // Yaw: horizontal displacement of nose from face center
        val yaw = if (faceWidth > 0.01f) {
            ((noseCenterX - faceCenterX) / faceWidth * 60f).toDouble()
        } else 0.0

        // Pitch: vertical displacement ratios
        val faceHeight = chin.y() - forehead.y()
        val noseRelative = (noseTip.y() - forehead.y()) / faceHeight.coerceAtLeast(0.01f)
        val pitch = ((noseRelative - 0.6f) * 80f).toDouble()

        // Roll: angle between eye corners
        val dx = (rightEyeOuter.x() - leftEyeOuter.x()).toDouble()
        val dy = (rightEyeOuter.y() - leftEyeOuter.y()).toDouble()
        val roll = Math.toDegrees(kotlin.math.atan2(dy, dx))

        return HeadPose(yaw = yaw, pitch = pitch, roll = roll)
    }

    /**
     * Eye Aspect Ratio (EAR) - ratio of vertical to horizontal eye dimensions.
     * ~0.2 = closed, ~0.4 = open.
     */
    private fun computeEAR(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        eyeIndices: IntArray,
    ): Double {
        if (eyeIndices.size < 6) return 0.3

        val p1 = landmarks[eyeIndices[0]]
        val p2 = landmarks[eyeIndices[1]]
        val p3 = landmarks[eyeIndices[2]]
        val p4 = landmarks[eyeIndices[3]]
        val p5 = landmarks[eyeIndices[4]]
        val p6 = landmarks[eyeIndices[5]]

        val verticalA = distance2D(p2, p6)
        val verticalB = distance2D(p3, p5)
        val horizontal = distance2D(p1, p4)

        return if (horizontal > 0.001) {
            (verticalA + verticalB) / (2.0 * horizontal)
        } else {
            0.3
        }
    }

    private fun distance2D(
        a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
    ): Double {
        val dx = (a.x() - b.x()).toDouble()
        val dy = (a.y() - b.y()).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun computeBoundingBox(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): FaceBoundingBox {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (lm in landmarks) {
            if (lm.x() < minX) minX = lm.x()
            if (lm.y() < minY) minY = lm.y()
            if (lm.x() > maxX) maxX = lm.x()
            if (lm.y() > maxY) maxY = lm.y()
        }
        return FaceBoundingBox(
            x = minX.toDouble(), y = minY.toDouble(),
            w = (maxX - minX).toDouble(), h = (maxY - minY).toDouble(),
        )
    }

    fun reset() {
        _frameMeshData.clear()
    }

    fun release() {
        faceLandmarker?.close()
        faceLandmarker = null
        _frameMeshData.clear()
    }

    companion object {
        // MediaPipe FaceMesh eye landmark indices for EAR computation
        val LEFT_EYE_INDICES = intArrayOf(33, 160, 158, 133, 153, 144)
        val RIGHT_EYE_INDICES = intArrayOf(362, 385, 387, 263, 380, 373)

        // 40 salient landmark indices for 3DMM fitting
        val SALIENT_LANDMARKS = intArrayOf(
            // Jaw outline (8 points)
            10, 338, 297, 332, 284, 251, 389, 356,
            // Left eyebrow (4 points)
            70, 63, 105, 66,
            // Right eyebrow (4 points)
            300, 293, 334, 296,
            // Nose (6 points)
            168, 6, 195, 5, 4, 1,
            // Left eye (4 points)
            33, 160, 158, 133,
            // Right eye (4 points)
            362, 385, 387, 263,
            // Outer lips (6 points)
            61, 291, 0, 17, 78, 308,
            // Inner lips (4 points)
            13, 14, 82, 312,
        )
    }
}

data class FrameMeshData(
    val frameIndex: Int,
    val timestampMs: Long,
    val headPose: HeadPose,
    val leftEAR: Double,
    val rightEAR: Double,
    val bbox: FaceBoundingBox,
    val landmarks: List<LandmarkPoint>,
)

data class HeadPose(
    val yaw: Double,
    val pitch: Double,
    val roll: Double,
)

data class FaceBoundingBox(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

data class LandmarkPoint(
    val x: Double,
    val y: Double,
    val z: Double,
)
