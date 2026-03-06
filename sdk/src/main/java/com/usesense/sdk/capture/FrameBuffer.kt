package com.usesense.sdk.capture

import android.os.SystemClock

data class CapturedFrame(
    val index: Int,
    val jpegData: ByteArray,
    val timestampMs: Long,
)

class FrameBuffer(private val maxFrames: Int) {

    private val frames = mutableListOf<CapturedFrame>()
    private var captureStartMs: Long = 0L
    private var nextIndex = 0

    val frameCount: Int get() = frames.size
    val timestamps: List<Long> get() = frames.map { it.timestampMs }
    val currentIndex: Int get() = nextIndex

    fun startCapture() {
        captureStartMs = SystemClock.elapsedRealtime()
        frames.clear()
        nextIndex = 0
    }

    fun addFrame(jpegData: ByteArray): CapturedFrame? {
        if (frames.size >= maxFrames) return null

        val timestampMs = SystemClock.elapsedRealtime() - captureStartMs
        val frame = CapturedFrame(
            index = nextIndex,
            jpegData = jpegData,
            timestampMs = timestampMs,
        )
        frames.add(frame)
        nextIndex++
        return frame
    }

    fun getFrames(): List<CapturedFrame> = frames.toList()

    fun getJpegDataList(): List<ByteArray> = frames.map { it.jpegData }

    fun clear() {
        frames.clear()
        nextIndex = 0
    }
}
