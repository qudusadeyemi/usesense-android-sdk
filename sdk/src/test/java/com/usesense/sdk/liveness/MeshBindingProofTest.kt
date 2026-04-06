package com.usesense.sdk.liveness

import org.junit.Assert.*
import org.junit.Test

class MeshBindingProofTest {

    @Test
    fun `hexToBytes correctly decodes hex string`() {
        val bytes = MeshBindingProof.hexToBytes("48656c6c6f")
        assertArrayEquals(byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f), bytes)
    }

    @Test
    fun `bytesToHex correctly encodes bytes`() {
        val hex = MeshBindingProof.bytesToHex(byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f))
        assertEquals("48656c6c6f", hex)
    }

    @Test
    fun `hexToBytes and bytesToHex are inverse operations`() {
        val original = "a1b2c3d4e5f6a7b8"
        val bytes = MeshBindingProof.hexToBytes(original)
        val result = MeshBindingProof.bytesToHex(bytes)
        assertEquals(original, result)
    }

    @Test
    fun `canonical JSON has correct field order`() {
        val shapeParams = doubleArrayOf(0.1, -0.2, 0.3)
        val pose = HeadPose(yaw = 1.5, pitch = -0.5, roll = 0.3)
        val json = MeshBindingProof.buildCanonicalJson(shapeParams, pose, 78, 468)

        // Verify field order: s, p (y, p, r), d, l
        assertTrue(json.startsWith("{\"s\":["))
        assertTrue(json.contains("\"p\":{\"y\":"))
        assertTrue(json.contains(",\"d\":78,\"l\":468}"))

        // Verify it contains all shape params
        assertTrue(json.contains("0.1"))
        assertTrue(json.contains("-0.2"))
        assertTrue(json.contains("0.3"))
    }

    @Test
    fun `mesh digest is deterministic`() {
        val params = doubleArrayOf(0.123, -0.456, 0.789)
        val pose = HeadPose(yaw = 2.1, pitch = -1.3, roll = 0.5)

        val digest1 = MeshBindingProof.computeMeshDigest(params, pose, 78, 468)
        val digest2 = MeshBindingProof.computeMeshDigest(params, pose, 78, 468)

        assertEquals(digest1, digest2)
        assertEquals(64, digest1.length) // SHA-256 hex
    }

    @Test
    fun `different params produce different digests`() {
        val pose = HeadPose(yaw = 0.0, pitch = 0.0, roll = 0.0)

        val digest1 = MeshBindingProof.computeMeshDigest(doubleArrayOf(0.1), pose, 50, 468)
        val digest2 = MeshBindingProof.computeMeshDigest(doubleArrayOf(0.2), pose, 50, 468)

        assertNotEquals(digest1, digest2)
    }

    @Test
    fun `binding proof is deterministic`() {
        val challenge = "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8"
        val frameHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val meshDigest = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"

        val proof1 = MeshBindingProof.computeBindingProof(challenge, frameHash, meshDigest)
        val proof2 = MeshBindingProof.computeBindingProof(challenge, frameHash, meshDigest)

        assertEquals(proof1, proof2)
        assertEquals(64, proof1.length) // HMAC-SHA256 hex
    }

    @Test
    fun `binding proof changes with different frame hash`() {
        val challenge = "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8"
        val meshDigest = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"

        val proof1 = MeshBindingProof.computeBindingProof(challenge,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", meshDigest)
        val proof2 = MeshBindingProof.computeBindingProof(challenge,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", meshDigest)

        assertNotEquals(proof1, proof2)
    }

    @Test
    fun `uses Double precision not Float for shape params`() {
        // This tests that we use Double (64-bit) precision in canonical JSON
        // Float would truncate 0.123456789012345 to ~0.12345679
        val params = doubleArrayOf(0.123456789012345)
        val pose = HeadPose(yaw = 0.0, pitch = 0.0, roll = 0.0)
        val json = MeshBindingProof.buildCanonicalJson(params, pose, 50, 468)

        // Should contain full Double precision
        assertTrue(json.contains("0.123456789012345"))
    }
}
