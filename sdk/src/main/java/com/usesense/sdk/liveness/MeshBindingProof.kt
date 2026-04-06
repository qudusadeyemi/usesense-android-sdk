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
 * - Canonical JSON field order MUST be: s, p (y, p, r), d, l
 */
internal object MeshBindingProof {

    /**
     * Compute the mesh digest: SHA-256 of the canonical JSON representation.
     *
     * Canonical format: {"s":[...],"p":{"y":...,"p":...,"r":...},"d":...,"l":468}
     */
    fun computeMeshDigest(
        shapeParams: DoubleArray,
        pose: HeadPose,
        depthPlausibility: Int,
        landmarkCount: Int,
    ): String {
        val canonical = buildCanonicalJson(shapeParams, pose, depthPlausibility, landmarkCount)
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
     * Build the canonical JSON string with EXACTLY the right field order.
     * This must produce identical output to the server's canonical form.
     */
    internal fun buildCanonicalJson(
        shapeParams: DoubleArray,
        pose: HeadPose,
        depthPlausibility: Int,
        landmarkCount: Int,
    ): String {
        // Build manually to guarantee field order (JSONObject doesn't preserve order)
        val sb = StringBuilder()
        sb.append("{\"s\":[")
        shapeParams.forEachIndexed { index, param ->
            if (index > 0) sb.append(",")
            sb.append(param) // Double precision
        }
        sb.append("],\"p\":{\"y\":")
        sb.append(pose.yaw)
        sb.append(",\"p\":")
        sb.append(pose.pitch)
        sb.append(",\"r\":")
        sb.append(pose.roll)
        sb.append("},\"d\":")
        sb.append(depthPlausibility)
        sb.append(",\"l\":")
        sb.append(landmarkCount)
        sb.append("}")
        return sb.toString()
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
