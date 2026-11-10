package com.usesense.sdk.liveness

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic frame-mesh binding per spec Chapter 6.5.
 *
 * Every frame in the verification package includes a bindingProof that ties
 * the mesh data to the actual JPEG frame, preventing fabricated mesh data.
 *
 * CRITICAL implementation notes:
 * - The challenge MUST be hex-decoded (not text-encoded) as the HMAC key
 * - Shape params MUST use Double precision (64-bit), not Float (32-bit)
 * - The canonical JSON MUST be byte-identical to the server's
 *   `computeMeshDigest` (usesense-watchtower mesh-integrity-validator.tsx),
 *   which is `JSON.stringify({ s, p: [yaw, pitch, roll], d, l: "none" })`:
 *   `p` is an array, `l` is the hex SHA-256 of the uploaded landmarks as
 *   Float64 when the frame carries them (EVIDENCE-v1), else the literal "none",
 *   and every number is formatted as JavaScript would format it.
 *   Until 2026-11 this built `p` as an object and `l` as the landmark count,
 *   so every Android binding proof failed server-side with
 *   `mesh_integrity_failed` at tier "binding".
 */
internal object MeshBindingProof {

    /**
     * Compute the mesh digest: SHA-256 of the canonical JSON representation.
     *
     * Canonical format: {"s":[...],"p":[yaw,pitch,roll],"d":...,"l":"none"}
     */
    fun computeMeshDigest(
        shapeParams: DoubleArray,
        pose: HeadPose,
        depthPlausibility: Int,
        /** Hex SHA-256 of the uploaded landmarks as Float64 (EVIDENCE-v1), or null for the literal "none". */
        landmarksHash: String? = null,
    ): String {
        val canonical = buildCanonicalJson(shapeParams, pose, depthPlausibility, landmarksHash)
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * Compute the HMAC-SHA256 binding proof.
     *
     * @param meshBindingChallenge 32-byte hex string from session creation
     * @param frameHash SHA-256 hex of the JPEG frame bytes
     * @param meshDigest SHA-256 hex of the canonical mesh JSON
     * @return Hex-encoded HMAC-SHA256 binding proof
     */
    fun computeBindingProof(
        meshBindingChallenge: String,
        frameHash: String,
        meshDigest: String,
    ): String {
        // CRITICAL: hex-decode the challenge, do NOT use TextEncoder/string encoding
        val challengeBytes = hexToBytes(meshBindingChallenge)
        val message = "$frameHash:$meshDigest"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(challengeBytes, "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))

        return bytesToHex(signature)
    }

    /**
     * Build the canonical JSON string, byte-identical to the server's
     * `JSON.stringify({ s, p: [yaw, pitch, roll], d, l: "none" })`.
     */
    internal fun buildCanonicalJson(
        shapeParams: DoubleArray,
        pose: HeadPose,
        depthPlausibility: Int,
        landmarksHash: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"s\":[")
        shapeParams.forEachIndexed { index, param ->
            if (index > 0) sb.append(",")
            sb.append(jsNumber(param))
        }
        sb.append("],\"p\":[")
        sb.append(jsNumber(pose.yaw)).append(",")
        sb.append(jsNumber(pose.pitch)).append(",")
        sb.append(jsNumber(pose.roll))
        sb.append("],\"d\":")
        sb.append(depthPlausibility)
        sb.append(",\"l\":\"").append(landmarksHash ?: "none").append("\"}")
        return sb.toString()
    }

    /**
     * Format a Double exactly as JavaScript's `JSON.stringify` does
     * (ECMAScript Number::toString): the shortest digits that round-trip,
     * fixed notation for 1e-7 < |x| < 1e21, otherwise `1.5e-7` / `1e+21`.
     * NaN and infinities become `null`, and -0 becomes `0`.
     *
     * Kotlin's `Double.toString` differs ("1.0", "1.0E-5", "1.0E21"), and
     * any single differing character breaks the binding proof.
     */
    internal fun jsNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        if (value == 0.0) return "0"

        // Shortest correctly-rounded decimal that parses back to the same Double.
        val exact = java.math.BigDecimal(value)
        var shortest = exact
        for (precision in 1..17) {
            val candidate = exact.round(java.math.MathContext(precision, java.math.RoundingMode.HALF_EVEN))
            if (candidate.toDouble() == value) { shortest = candidate; break }
        }
        shortest = shortest.stripTrailingZeros()

        val digits = shortest.unscaledValue().abs().toString()
        val k = digits.length
        // value = 0.digits × 10^n
        val n = k - shortest.scale()
        val sign = if (value < 0) "-" else ""

        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (e >= 0) "+" else "-") + kotlin.math.abs(e)
            }
        }
        return sign + body
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return bytesToHex(digest)
    }

    internal fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    internal fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
