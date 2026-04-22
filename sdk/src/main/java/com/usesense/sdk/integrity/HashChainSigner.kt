package com.usesense.sdk.integrity

import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * LiveSense v4 hash-chain builder.
 *
 * Phase 1 ticket A-1.
 *
 * Chain protocol (matches watchtower chain-signature.tsx exactly):
 *
 *     chain[0] = SHA-256(sessionToken.utf8 || frame[0])
 *     chain[i] = SHA-256(chain[i-1] || frame[i])   for i > 0
 */
class HashChainBuilder(sessionToken: String) {
    private val sessionTokenBytes: ByteArray = sessionToken.toByteArray(Charsets.UTF_8)
    private var current: ByteArray? = null
    private val frameHashesList: MutableList<String> = mutableListOf()
    private var count: Int = 0

    /** Append a frame. Returns the per-frame SHA-256 hex. */
    fun append(frameBytes: ByteArray): String {
        val frameHash = sha256(frameBytes)
        val hex = bytesToHex(frameHash)
        frameHashesList.add(hex)
        current = when (val prev = current) {
            null -> sha256(sessionTokenBytes + frameHash)
            else -> sha256(prev + frameHash)
        }
        count += 1
        return hex
    }

    /** Append a pre-computed per-frame hash. */
    fun appendWithHash(hexHash: String) {
        require(hexHash.length == 64) { "frame hash must be 64 hex chars" }
        val frameHash = hexToBytes(hexHash)
        frameHashesList.add(hexHash.lowercase())
        current = when (val prev = current) {
            null -> sha256(sessionTokenBytes + frameHash)
            else -> sha256(prev + frameHash)
        }
        count += 1
    }

    fun terminal(): ByteArray {
        val c = current ?: throw IllegalStateException("empty chain")
        return c.copyOf()
    }

    fun terminalHex(): String = bytesToHex(terminal())
    fun frameHashes(): List<String> = frameHashesList.toList()
    fun length(): Int = count

    companion object {
        fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xff))
            }
            return sb.toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd-length hex" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = (
                    Character.digit(hex[i * 2], 16).shl(4) or
                    Character.digit(hex[i * 2 + 1], 16)
                ).toByte()
            }
            return out
        }

        fun base64UrlEncode(data: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }
}

/**
 * Wraps a HardwareKeyManager key and signs the terminal hash with
 * SHA256withECDSA. The server's chain-signature.tsx accepts both DER
 * and raw R||S encodings; Android's Signature API emits DER.
 */
data class V4Signature(
    val signatureB64: String,
    val publicKeySpkiB64: String,
    val assuranceLevel: String
)

class V4ChainSigner(private val keyManager: HardwareKeyManager) {
    fun sign(terminal: ByteArray): V4Signature {
        val keyPair = keyManager.getOrGenerateSigningKey()
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(terminal)
        }.sign()
        val spki = keyManager.exportPublicKeySpki()
        val tier = keyManager.attestationTier()
        // Phase 1: map strongbox and tee to mobile_hardware.
        // Phase 2 may distinguish to allow server-side tier weighting.
        val assurance = when (tier) {
            "strongbox", "tee" -> "mobile_hardware"
            else -> "mobile_hardware"
        }
        return V4Signature(
            signatureB64 = HashChainBuilder.base64UrlEncode(sig),
            publicKeySpkiB64 = HashChainBuilder.base64UrlEncode(spki),
            assuranceLevel = assurance
        )
    }
}
