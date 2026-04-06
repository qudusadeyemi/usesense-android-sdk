package com.usesense.sdk.capture

import android.os.SystemClock
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FrameBufferTest {

    private lateinit var buffer: FrameBuffer
    private var mockTime = 0L

    @Before
    fun setup() {
        mockkStatic(SystemClock::class)
        mockTime = 1000L
        every { SystemClock.elapsedRealtime() } answers { mockTime }
        buffer = FrameBuffer(maxFrames = 5)
    }

    @After
    fun teardown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `starts empty`() {
        assertEquals(0, buffer.frameCount)
        assertEquals(0, buffer.currentIndex)
    }

    @Test
    fun `addFrame increments index`() {
        buffer.startCapture()
        val frame = buffer.addFrame(byteArrayOf(1, 2, 3))
        assertNotNull(frame)
        assertEquals(0, frame!!.index)
        assertEquals(1, buffer.frameCount)
        assertEquals(1, buffer.currentIndex)
    }

    @Test
    fun `respects max frame limit`() {
        buffer.startCapture()
        repeat(5) {
            assertNotNull(buffer.addFrame(byteArrayOf(it.toByte())))
        }
        // 6th frame should be rejected
        assertNull(buffer.addFrame(byteArrayOf(99)))
        assertEquals(5, buffer.frameCount)
    }

    @Test
    fun `getJpegDataList returns all frame data`() {
        buffer.startCapture()
        buffer.addFrame(byteArrayOf(10))
        buffer.addFrame(byteArrayOf(20))
        buffer.addFrame(byteArrayOf(30))

        val data = buffer.getJpegDataList()
        assertEquals(3, data.size)
        assertArrayEquals(byteArrayOf(10), data[0])
        assertArrayEquals(byteArrayOf(20), data[1])
        assertArrayEquals(byteArrayOf(30), data[2])
    }

    @Test
    fun `clear resets state`() {
        buffer.startCapture()
        buffer.addFrame(byteArrayOf(1))
        buffer.addFrame(byteArrayOf(2))
        buffer.clear()
        assertEquals(0, buffer.frameCount)
        assertEquals(0, buffer.currentIndex)
    }

    @Test
    fun `timestamps are recorded`() {
        buffer.startCapture()
        buffer.addFrame(byteArrayOf(1))
        mockTime = 1050L
        buffer.addFrame(byteArrayOf(2))

        val timestamps = buffer.timestamps
        assertEquals(2, timestamps.size)
        assertTrue(timestamps[1] >= timestamps[0])
    }

    @Test
    fun `frame hashes are SHA-256 hex strings`() {
        buffer.startCapture()
        buffer.addFrame(byteArrayOf(1, 2, 3))

        val hashes = buffer.frameHashes
        assertEquals(1, hashes.size)
        // SHA-256 hex is 64 characters
        assertEquals(64, hashes[0].length)
        // Should be lowercase hex
        assertTrue(hashes[0].matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `computeSha256 produces consistent hashes`() {
        val data = byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f) // "Hello"
        val hash1 = FrameBuffer.computeSha256(data)
        val hash2 = FrameBuffer.computeSha256(data)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun `different data produces different hashes`() {
        val hash1 = FrameBuffer.computeSha256(byteArrayOf(1, 2, 3))
        val hash2 = FrameBuffer.computeSha256(byteArrayOf(4, 5, 6))
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `luminance is stored in frame`() {
        buffer.startCapture()
        val frame = buffer.addFrame(byteArrayOf(1, 2, 3), luminance = 128.5)
        assertNotNull(frame)
        assertEquals(128.5, frame!!.luminance, 0.01)
    }

    @Test
    fun `frameLuminances returns all luminance values`() {
        buffer.startCapture()
        buffer.addFrame(byteArrayOf(1), luminance = 100.0)
        buffer.addFrame(byteArrayOf(2), luminance = 120.0)
        buffer.addFrame(byteArrayOf(3), luminance = 110.0)

        val luminances = buffer.frameLuminances
        assertEquals(3, luminances.size)
        assertEquals(100.0, luminances[0], 0.01)
        assertEquals(120.0, luminances[1], 0.01)
        assertEquals(110.0, luminances[2], 0.01)
    }
}
