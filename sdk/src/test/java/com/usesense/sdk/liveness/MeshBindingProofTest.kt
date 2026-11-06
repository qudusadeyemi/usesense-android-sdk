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
    fun `canonical JSON matches the server's JSON stringify form`() {
        val json = MeshBindingProof.buildCanonicalJson(
            doubleArrayOf(0.1, -0.2, 0.3), HeadPose(yaw = 1.5, pitch = -0.5, roll = 0.3), 78,
        )
        assertEquals("{\"s\":[0.1,-0.2,0.3],\"p\":[1.5,-0.5,0.3],\"d\":78,\"l\":\"none\"}", json)
    }

    @Test
    fun `mesh digest matches the server for a known vector`() {
        // Generated with Node: sha256(JSON.stringify({s, p: [y, p, r], d, l: "none"}))
        // — exactly what watchtower's computeMeshDigest hashes.
        val params = doubleArrayOf(0.1234, -0.0000123, 1e-7, 0.3, -0.05)
        val pose = HeadPose(yaw = 12.5, pitch = -3.25, roll = 0.0001)
        assertEquals(
            "{\"s\":[0.1234,-0.0000123,1e-7,0.3,-0.05],\"p\":[12.5,-3.25,0.0001],\"d\":87,\"l\":\"none\"}",
            MeshBindingProof.buildCanonicalJson(params, pose, 87),
        )
        assertEquals(
            "70bc00306e86a65db9cd1b88143bafea3ee2a3bf3a3c951b2270ca01c47f592d",
            MeshBindingProof.computeMeshDigest(params, pose, 87),
        )
    }

    @Test
    fun `numbers are formatted as JavaScript formats them`() {
        // Expected strings are Node's JSON.stringify output for each value.
        val cases = listOf(
            0.1 to "0.1", -0.25 to "-0.25", 1e-5 to "0.00001", 1.5e-6 to "0.0000015",
            1e-6 to "0.000001", 1e-7 to "1e-7", -3.2e-8 to "-3.2e-8", 123.0 to "123",
            1e20 to "100000000000000000000", 1e21 to "1e+21", 2.5e22 to "2.5e+22",
            0.000123456789 to "0.000123456789", 5e-324 to "5e-324",
            Double.MAX_VALUE to "1.7976931348623157e+308", -0.0 to "0",
            0.30000000000000004 to "0.30000000000000004", 100.0 to "100",
            1.0 / 3 to "0.3333333333333333", Double.NaN to "null",
        )
        for ((value, expected) in cases) {
            assertEquals("jsNumber($value)", expected, MeshBindingProof.jsNumber(value))
        }
    }

    @Test
    fun `mesh digest is deterministic`() {
        val params = doubleArrayOf(0.123, -0.456, 0.789)
        val pose = HeadPose(yaw = 2.1, pitch = -1.3, roll = 0.5)

        val digest1 = MeshBindingProof.computeMeshDigest(params, pose, 78)
        val digest2 = MeshBindingProof.computeMeshDigest(params, pose, 78)

        assertEquals(digest1, digest2)
        assertEquals(64, digest1.length) // SHA-256 hex
    }

    @Test
    fun `different params produce different digests`() {
        val pose = HeadPose(yaw = 0.0, pitch = 0.0, roll = 0.0)

        val digest1 = MeshBindingProof.computeMeshDigest(doubleArrayOf(0.1), pose, 50)
        val digest2 = MeshBindingProof.computeMeshDigest(doubleArrayOf(0.2), pose, 50)

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
        val json = MeshBindingProof.buildCanonicalJson(params, pose, 50)

        // Should contain full Double precision
        assertTrue(json.contains("0.123456789012345"))
    }
}
