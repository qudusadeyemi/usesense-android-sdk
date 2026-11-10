package com.usesense.sdk.capture.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Head pose in degrees, for the quality gate (its limits are symmetric, so signs don't matter there). */
internal data class PoseAngles(val yaw: Double, val pitch: Double, val roll: Double)

/**
 * Head pose from MediaPipe's facial transformation matrix, as ZYX Euler angles.
 *
 * The matrix is the canonical face model's pose in camera space: a 4x4 rigid
 * transform (rotation, possibly with uniform scale, plus translation). With
 * R = Rz(roll) * Ry(yaw) * Rx(pitch), the angles are
 *
 *   yaw   = asin(-R20)        rotation about the vertical axis (turning the head)
 *   pitch = atan2(R21, R22)   rotation about the horizontal axis (nodding)
 *   roll  = atan2(R10, R00)   rotation in the image plane (tilting)
 *
 * The old face-width x 60 estimate under-reads a 30 degree turn as about 11
 * degrees, too loose for a 15 degree gate.
 *
 * The packed float layout differs by MediaPipe build (column-major in the Java
 * task, row-major in tasks-vision JS), so it is detected from the translation:
 * the face sits tens of centimetres in front of the camera, so the z
 * translation is by far the largest translation component, and it lives at
 * index 14 when column-major and 11 when row-major.
 *
 * Used by the quality gate only. The upload's headPose keeps the landmark
 * estimate until Phase 2 moves every SDK to the matrix together, as iOS and
 * Web do.
 */
internal object HeadPoseMath {

    fun isColumnMajor(m: FloatArray): Boolean = abs(m[14]) >= abs(m[11])

    /** Element (row, col) of the 4x4 [m], whichever layout it is packed in. */
    private fun at(m: FloatArray, row: Int, col: Int, columnMajor: Boolean): Double =
        (if (columnMajor) m[col * 4 + row] else m[row * 4 + col]).toDouble()

    fun fromMatrix(m: FloatArray): PoseAngles? {
        if (m.size < 16) return null
        val cm = isColumnMajor(m)
        // Remove any uniform scale so the 3x3 is a pure rotation.
        val sx = sqrt(sq(at(m, 0, 0, cm)) + sq(at(m, 1, 0, cm)) + sq(at(m, 2, 0, cm)))
        if (sx < 1e-9 || sx.isNaN()) return null
        val r00 = at(m, 0, 0, cm) / sx
        val r10 = at(m, 1, 0, cm) / sx
        val r20 = at(m, 2, 0, cm) / sx
        val r21 = at(m, 2, 1, cm) / sx
        val r22 = at(m, 2, 2, cm) / sx
        val r11 = at(m, 1, 1, cm) / sx
        val r12 = at(m, 1, 2, cm) / sx

        val cy = sqrt(r00 * r00 + r10 * r10)
        val yaw: Double
        val pitch: Double
        val roll: Double
        if (cy > 1e-6) {
            yaw = atan2(-r20, cy)
            pitch = atan2(r21, r22)
            roll = atan2(r10, r00)
        } else {
            // Gimbal lock (yaw near +/-90): roll is indeterminate.
            yaw = atan2(-r20, cy)
            pitch = atan2(-r12, r11)
            roll = 0.0
        }
        return PoseAngles(
            yaw = Math.toDegrees(yaw),
            pitch = Math.toDegrees(pitch),
            roll = Math.toDegrees(roll),
        )
    }

    private fun sq(v: Double) = v * v
}
