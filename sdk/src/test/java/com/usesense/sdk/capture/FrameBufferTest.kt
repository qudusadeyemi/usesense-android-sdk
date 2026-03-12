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
}
