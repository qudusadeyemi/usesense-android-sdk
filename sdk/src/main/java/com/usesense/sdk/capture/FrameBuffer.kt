package com.usesense.sdk.capture

import android.graphics.BitmapFactory
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
    /** Encoded pixel size of [jpegData]. The frames_manifest used to report a
     *  hardcoded 640x480 regardless of what was captured, which is wrong for
     *  the v4 path. The server scales its sharpness thresholds off these. */
    val width: Int = 0,
    val height: Int = 0,
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
        // Header-only decode: reads the JPEG's SOF dimensions without
        // allocating any pixels, so it works whichever capture path produced
        // these bytes and costs nothing per frame.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, bounds)
        val frame = CapturedFrame(
            index = nextIndex,
            jpegData = jpegData,
            timestampMs = timestampMs,
            hash = hash,
            luminance = luminance,
            phase = currentPhase.value,
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
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
