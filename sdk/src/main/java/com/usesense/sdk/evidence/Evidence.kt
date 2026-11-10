package com.usesense.sdk.evidence

import com.usesense.sdk.liveness.HeadPose
import com.usesense.sdk.liveness.LandmarkPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The upload side of EVIDENCE-v1 that is plain arithmetic: rounding, the
 * landmarks hash, matrix pose, eye distance. Pure, so the wire format is unit
 * tested without a camera.
 */
internal object Evidence {
    const val POSE_METHOD_V3 = "matrix_zyx_v3"
    const val POSE_METHOD_LEGACY = "mediapipe_zyx_v2"
    const val CLOCK = "capture_monotonic_ms"

    /**
     * Round half away from zero to [places] decimals, never -0. The server
     * hashes the numbers it reads, so the SDK rounds before hashing and sending.
     */
    fun round(v: Double, places: Int): Double {
        if (!v.isFinite()) return 0.0
        val f = POW10[places]
        val r = floor(abs(v) * f + 0.5) / f
        return if (r == 0.0) 0.0 else if (v < 0) -r else r
    }

    private val POW10 = DoubleArray(10) { var p = 1.0; repeat(it) { p *= 10.0 }; p }

    /** Flat `[x0, y0, z0, x1, ...]`, 5 decimals, in the space the points are given in. */
    fun flatLandmarks(points: List<LandmarkPoint>): DoubleArray {
        val out = DoubleArray(points.size * 3)
        points.forEachIndexed { i, p ->
            out[i * 3] = round(p.x, 5)
            out[i * 3 + 1] = round(p.y, 5)
            out[i * 3 + 2] = round(p.z, 5)
        }
        return out
    }

    /**
     * Lowercase hex SHA-256 of the values as little-endian Float64: the server's
     * `new Float64Array(frame.landmarks)` (computeMeshDigest).
     */
    fun landmarksHash(flat: DoubleArray): String? {
        if (flat.isEmpty()) return null
        val buf = ByteBuffer.allocate(flat.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (v in flat) buf.putDouble(v)
        return hex(MessageDigest.getInstance("SHA-256").digest(buf.array()))
    }

    /**
     * EVIDENCE-v1 section 5, exactly the server's `poseFromMatrix`, degrees.
     * [m] is the row-major 4x4 facial transformation matrix.
     */
    fun matrixPose(m: DoubleArray): HeadPose? {
        if (m.size != 16 || m.any { !it.isFinite() }) return null
        fun r(row: Int, col: Int) = m[row * 4 + col]
        val deg = 180.0 / Math.PI
        return HeadPose(
            yaw = atan2(-r(2, 0), sqrt(r(0, 0) * r(0, 0) + r(1, 0) * r(1, 0))) * deg,
            pitch = atan2(r(2, 1), r(2, 2)) * deg,
            roll = atan2(r(1, 0), r(0, 0)) * deg,
        )
    }

    /**
     * Eye distance in units of the image width: iris centres (468, 473) when
     * the mesh has them, else the eye-corner midpoints. [aspect] = height / width
     * of the image the points are normalised to, so x and y share one unit.
     */
    fun eyeDistance(points: List<LandmarkPoint>, aspect: Double): Double? {
        if (points.size < 468) return null
        val d = if (points.size >= 478) {
            hypot(points[468].x - points[473].x, (points[468].y - points[473].y) * aspect)
        } else {
            val lx = (points[33].x + points[133].x) / 2
            val ly = (points[33].y + points[133].y) / 2
            val rx = (points[362].x + points[263].x) / 2
            val ry = (points[362].y + points[263].y) / 2
            hypot(rx - lx, (ry - ly) * aspect)
        }
        return d.takeIf { it.isFinite() && it > 0 }
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun sha256Hex(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
