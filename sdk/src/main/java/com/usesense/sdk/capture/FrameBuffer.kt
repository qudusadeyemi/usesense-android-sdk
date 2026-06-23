package com.usesense.sdk.capture

import android.os.SystemClock
import java.security.MessageDigest

data class CapturedFrame(
    val index: Int,
    val jpegData: ByteArray,
    val timestampMs: Long,
    val hash: String,
    val luminance: Double,
    /** v4: capture phase tag. Lets the server's SfM perspective validator
     *  filter to the zoom-motion subset (PRD section 4). */
    val phase: String = "other",
)

/** Capture phase tags, matching the iOS FrameBuffer.CapturePhase enum. */
enum class CapturePhase(val value: String) {
    FRAMING("framing"),
    BASELINE("baseline"),
    ZOOM("zoom"),
    CHALLENGE("challenge"),
    OTHER("other"),
}

class FrameBuffer(private val maxFrames: Int) {

    private val frames = mutableListOf<CapturedFrame>()
    private var captureStartMs: Long = 0L
    private var nextIndex = 0
    @Volatile private var currentPhase: CapturePhase = CapturePhase.OTHER

    val frameCount: Int get() = frames.size
    val timestamps: List<Long> get() = frames.map { it.timestampMs }
    val currentIndex: Int get() = nextIndex
    val frameHashes: List<String> get() = frames.map { it.hash }
    val frameLuminances: List<Double> get() = frames.map { it.luminance }
    val framePhases: List<String> get() = frames.map { it.phase }

    fun setCapturePhase(phase: CapturePhase) {
        currentPhase = phase
    }

    fun startCapture() {
        captureStartMs = SystemClock.elapsedRealtime()
        frames.clear()
        nextIndex = 0
    }

    fun addFrame(jpegData: ByteArray, luminance: Double = 0.0): CapturedFrame? {
        if (frames.size >= maxFrames) return null

        val timestampMs = SystemClock.elapsedRealtime() - captureStartMs
        val hash = computeSha256(jpegData)
        val frame = CapturedFrame(
            index = nextIndex,
            jpegData = jpegData,
            timestampMs = timestampMs,
            hash = hash,
            luminance = luminance,
            phase = currentPhase.value,
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

    companion object {
        fun computeSha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
