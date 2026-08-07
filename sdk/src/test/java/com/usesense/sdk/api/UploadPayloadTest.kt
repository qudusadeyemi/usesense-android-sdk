package com.usesense.sdk.api

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * metadata.json goes out on every session and was never compressed. The server
 * detects compression from the gzip magic bytes rather than the filename, so
 * these pin the framing: a malformed payload would 400 the upload and fail the
 * capture outright.
 */
class UploadPayloadTest {

    /** Representative of the real payload: repetitive JSON with long float arrays. */
    private fun sampleMetadata(): ByteArray {
        val frames = (0 until 30).joinToString(",") {
            """{"frame_index":$it,"resolution_w":540,"resolution_h":960,"hash":"${"a".repeat(64)}"}"""
        }
        val ratios = (0 until 60).joinToString(",") { (it * 0.017).toString() }
        return """{"frames_manifest":[$frames],"verification_package":{"shapeParams":[$ratios]}}"""
            .toByteArray()
    }

    @Test
    fun `emits gzip magic bytes the server can sniff`() {
        val out = UseSenseApiClient.gzip(sampleMetadata())
        assertNotNull(out)
        // The server keys off exactly these two bytes.
        assertEquals(0x1f.toByte(), out!![0])
        assertEquals(0x8b.toByte(), out[1])
    }

    @Test
    fun `round-trips back to the identical bytes`() {
        val source = sampleMetadata()
        val out = UseSenseApiClient.gzip(source)!!
        val restored = GZIPInputStream(ByteArrayInputStream(out)).readBytes()
        assertArrayEquals(source, restored)
    }

    @Test
    fun `actually shrinks a realistic payload`() {
        val source = sampleMetadata()
        val out = UseSenseApiClient.gzip(source)!!
        assertTrue(
            "expected better than 50% on repetitive JSON, got ${out.size} from ${source.size}",
            out.size < source.size / 2,
        )
    }

    @Test
    fun `returns null for empty input so the caller sends the payload as-is`() {
        assertNull(UseSenseApiClient.gzip(ByteArray(0)))
    }

    @Test
    fun `never grows incompressible input`() {
        // Random bytes cannot be deflated; the caller must not pay a size
        // penalty for the gzip framing.
        val random = ByteArray(4096).also { java.util.Random(42).nextBytes(it) }
        val out = UseSenseApiClient.gzip(random)
        if (out != null) assertTrue(out.size < random.size)
    }

    @Test
    fun `upload write timeout leaves headroom for a slow uplink`() {
        // A measured production session uploaded at 14.6 KB/s. The previous 30s
        // write timeout could not carry a multi-megabyte body at that rate.
        assertTrue(UseSenseApiClient.UPLOAD_WRITE_TIMEOUT_SECONDS >= 120L)
    }
}
