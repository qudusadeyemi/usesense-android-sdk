package com.usesense.sdk.signals

import java.security.MessageDigest
import java.util.Base64

/**
 * Binding a Play Integrity token to a server nonce (the `dn_` nonce of a
 * silent device check, or a Device Trust step's nonce).
 *
 * Server contract (usesense-watchtower,
 * modules/device-trust/play-integrity-nonce.ts):
 *
 *   Standard API: requestHash = lowercase hex of SHA-256(UTF-8 bytes of the nonce)
 *   Classic API:  nonce       = base64url, no padding, of the same digest
 *
 * The server accepts either encoding of the same 32 bytes in either field,
 * and nothing else: not the raw nonce, not a different hash. A token that is
 * not bound counts as a failed attestation on a silent device check.
 *
 * Pure (no Android types) so it is covered by plain JVM unit tests.
 */
object PlayIntegrityNonceBinding {
    /** The value declared in channel_integrity.play_integrity_nonce_binding. */
    const val BINDING = "sha256"

    /** Which Play Integrity API produced the token. */
    const val API_STANDARD = "standard"
    const val API_CLASSIC = "classic"

    private fun digest(nonce: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.UTF_8))

    /** Standard API `requestHash`: 64 lowercase hex characters. */
    @JvmStatic
    fun requestHash(nonce: String): String = digest(nonce).joinToString("") { "%02x".format(it) }

    /**
     * Classic API `nonce`: base64url without padding (43 characters), which
     * also meets the Classic API's own rule (URL-safe base64, no wrap,
     * 16 to 500 characters).
     */
    @JvmStatic
    fun classicNonce(nonce: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(nonce))
}
