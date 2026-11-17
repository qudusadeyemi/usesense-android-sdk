package com.usesense.sdk.signals

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The Play Integrity nonce binding, pinned to the server's contract
 * (usesense-watchtower modules/device-trust/play-integrity-nonce.ts):
 * Standard requestHash = lowercase hex SHA-256 of the nonce's UTF-8 bytes,
 * Classic nonce = base64url without padding of the same digest.
 * Vectors computed independently (Python hashlib).
 */
class PlayIntegrityNonceBindingTest {
    private val nonce = "dn_2b7d9f00aa11"

    @Test
    fun `requestHash is lowercase hex SHA-256 of the nonce`() {
        assertEquals("f4a32564477290945228e31710fbef3b562fe31ccf16ffd550fb91c3449c0bea", PlayIntegrityNonceBinding.requestHash(nonce))
    }

    @Test
    fun `classic nonce is base64url without padding of the same digest`() {
        val classic = PlayIntegrityNonceBinding.classicNonce(nonce)
        assertEquals("9KMlZEdykJRSKOMXEPvvO1Yv4xzPFv_VUPuRw0ScC-o", classic)
        assertFalse(classic.contains('='))
        assertFalse(classic.contains('+') || classic.contains('/'))
        // Within the Classic API's own 16 to 500 character rule.
        assertTrue(classic.length in 16..500)
    }

    @Test
    fun `both encodings carry exactly the same 32 bytes`() {
        val fromHex = PlayIntegrityNonceBinding.requestHash(nonce).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val fromB64 = Base64.getUrlDecoder().decode(PlayIntegrityNonceBinding.classicNonce(nonce))
        assertEquals(32, fromHex.size)
        assertArrayEquals(fromHex, fromB64)
    }

    @Test
    fun `the nonce is hashed as UTF-8`() {
        assertEquals("aa407d35cb3f9f434c18e48674eaf9bd1fb779f69448a111eb9cebb64d8d42bf", PlayIntegrityNonceBinding.requestHash("dn_é"))
    }

    @Test
    fun `never the raw nonce`() {
        assertNotEquals(nonce, PlayIntegrityNonceBinding.requestHash(nonce))
        assertNotEquals(nonce, PlayIntegrityNonceBinding.classicNonce(nonce))
        assertEquals("sha256", PlayIntegrityNonceBinding.BINDING)
    }
}
