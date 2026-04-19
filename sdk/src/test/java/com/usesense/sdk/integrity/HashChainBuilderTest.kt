package com.usesense.sdk.integrity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for HashChainBuilder. Phase 1 ticket A-1.
 */
class HashChainBuilderTest {

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun twoFrameChainMatchesSpec() {
        val token = "sess_tok_abc"
        val f0 = "frame-0".toByteArray()
        val f1 = "frame-1".toByteArray()

        val h0 = sha256(f0)
        val h1 = sha256(f1)
        val c0 = sha256(token.toByteArray() + h0)
        val expected = sha256(c0 + h1)

        val b = HashChainBuilder(token)
        b.append(f0)
        b.append(f1)

        assertArrayEquals(expected, b.terminal())
        assertEquals(2, b.length())
        assertEquals(2, b.frameHashes().size)
    }

    @Test
    fun singleFrame() {
        val token = "x"
        val f0 = "solo".toByteArray()
        val expected = sha256(token.toByteArray() + sha256(f0))
        val b = HashChainBuilder(token)
        b.append(f0)
        assertArrayEquals(expected, b.terminal())
    }

    @Test
    fun appendWithHashEquivalence() {
        val token = "same"
        val f0 = "abc".toByteArray()
        val f1 = "def".toByteArray()

        val b1 = HashChainBuilder(token)
        b1.append(f0)
        b1.append(f1)

        val b2 = HashChainBuilder(token)
        b2.appendWithHash(HashChainBuilder.bytesToHex(sha256(f0)))
        b2.appendWithHash(HashChainBuilder.bytesToHex(sha256(f1)))

        assertArrayEquals(b1.terminal(), b2.terminal())
    }

    @Test
    fun terminalBeforeAppendThrows() {
        val b = HashChainBuilder("t")
        assertThrows(IllegalStateException::class.java) { b.terminal() }
    }

    @Test
    fun reorderChangesTerminal() {
        val f0 = "a".toByteArray()
        val f1 = "b".toByteArray()

        val b1 = HashChainBuilder("t")
        b1.append(f0); b1.append(f1)

        val b2 = HashChainBuilder("t")
        b2.append(f1); b2.append(f0)

        assertNotEquals(
            HashChainBuilder.bytesToHex(b1.terminal()),
            HashChainBuilder.bytesToHex(b2.terminal())
        )
    }

    @Test
    fun base64UrlNoPadding() {
        val encoded = HashChainBuilder.base64UrlEncode(byteArrayOf(-1, -2, -3))
        assertFalse(encoded.contains('='))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
    }

    @Test
    fun hexBytesRoundTrip() {
        val original = byteArrayOf(0x00, 0x10, 0x7f, 0xff.toByte())
        val hex = HashChainBuilder.bytesToHex(original)
        val back = HashChainBuilder.hexToBytes(hex)
        assertArrayEquals(original, back)
    }
}
