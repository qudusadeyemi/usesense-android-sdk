package com.usesense.sdk.upload

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * H.264 + MP4 encoder backed by MediaCodec + MediaMuxer.
 * Phase 1 ticket A-1.
 *
 * Phase 1 uses `COLOR_FormatYUV420Flexible` with a Bitmap-to-NV12
 * conversion on the writer side. This is slower than the Surface
 * input path but works portably across mid-tier SoCs. Phase 2 can
 * swap in the Surface + OpenGL path for lower CPU cost.
 *
 * 1280x720, 30fps, ~4 Mbps, keyframe every 30 frames.
 *
 * TODO: device-verify encoder stability under sustained 30fps on
 * Pixel 7 and Samsung A54. Some Xiaomi/Redmi MediaCodec encoders
 * prefer NV21; add a fallback if COLOR_FormatYUV420Flexible fails.
 */
class VideoEncoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 30,
    private val bitrate: Int = 4_000_000,
) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var outputFile: File? = null
    private var frameIndex: Long = 0
    private var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    /** Start the encoder and muxer. Writes to a temp file in the cache dir. */
    fun start(cacheDir: File) {
        require(codec == null) { "VideoEncoder already started" }
        val out = File(cacheDir, "usesense-v4-${System.currentTimeMillis()}.mp4")
        outputFile = out

        // OEMs disagree on color format preference: most accept
        // COLOR_FormatYUV420Flexible, but some Xiaomi / Redmi encoders
        // reject it and require COLOR_FormatYUV420SemiPlanar (NV12).
        // We probe the codec capabilities and pick a supported format;
        // encoder-input bytes are produced accordingly.
        val supportedFormats = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        )
        val codecInfo = pickEncoderFor(MediaFormat.MIMETYPE_VIDEO_AVC)
            ?: throw IllegalStateException("no H.264 encoder available")
        colorFormat = pickColorFormat(codecInfo, supportedFormats)
            ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        Log.d(TAG, "encoder=${codecInfo.name} colorFormat=$colorFormat")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }
        codec = MediaCodec.createByCodecName(codecInfo.name).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        frameIndex = 0
    }

    /**
     * Append one frame as a Bitmap. Caller provides monotonic presentation
     * time in microseconds.
     */
    fun appendBitmap(bitmap: Bitmap, presentationTimeUs: Long) {
        val codec = codec ?: throw IllegalStateException("encoder not started")
        val yuv = bitmapToI420(bitmap, width, height)
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val input = codec.getInputBuffer(inputIndex) ?: return
        input.clear()
        input.put(yuv)
        codec.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
        frameIndex += 1
        drainOutput(endOfStream = false)
    }

    /**
     * Finalise and return the MP4 bytes. Deletes the temp file on the way out.
     */
    fun finish(): ByteArray {
        val codec = codec ?: throw IllegalStateException("encoder not started")
        val input = codec.dequeueInputBuffer(10_000)
        if (input >= 0) {
            codec.queueInputBuffer(input, 0, 0, frameIndex * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainOutput(endOfStream = true)

        codec.stop()
        codec.release()
        this.codec = null

        muxer?.let { mx ->
            if (muxerStarted) {
                try { mx.stop() } catch (_: Exception) {}
            }
            mx.release()
        }
        muxer = null
        muxerStarted = false

        val out = outputFile ?: throw IllegalStateException("output file missing")
        val bytes = out.readBytes()
        out.delete()
        outputFile = null
        return bytes
    }

    private fun drainOutput(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // when ending, keep looping until we see EOS
                    continue
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex < 0 -> {
                    // Unhandled negative status; skip
                }
                else -> {
                    val encoded: ByteBuffer = codec.getOutputBuffer(outputIndex)
                        ?: run { codec.releaseOutputBuffer(outputIndex, false); continue }
                    if (info.size != 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ── YUV conversion ───────────────────────────────────────────────────────
    //
    // ARGB_8888 Bitmap -> I420 (YUV420 planar). Good enough for Phase 1;
    // replace with a Surface + GLES pipeline in Phase 2 if CPU cost matters.

    private fun bitmapToI420(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap
            else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val frameSize = w * h
        val chromaSize = frameSize / 4
        val out = ByteArray(frameSize + 2 * chromaSize)

        // Interleaving differs by colour format:
        //   I420 / COLOR_FormatYUV420Planar:       Y... U... V...
        //   COLOR_FormatYUV420Flexible (I420-ish): same layout
        //   NV12 / COLOR_FormatYUV420SemiPlanar:   Y... UVUVUV...
        val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + chromaSize
        var uvIndex = frameSize

        for (j in 0 until h) {
            for (i in 0 until w) {
                val argb = pixels[j * w + i]
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    if (semiPlanar) {
                        out[uvIndex++] = u.coerceIn(0, 255).toByte()
                        out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        out[uIndex++] = u.coerceIn(0, 255).toByte()
                        out[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    /** Return the first encoder advertising the given MIME type, or null. */
    private fun pickEncoderFor(mime: String): MediaCodecInfo? {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            return info
        }
        return null
    }

    /** Pick the first color format from [preferred] that [info] supports. */
    private fun pickColorFormat(info: MediaCodecInfo, preferred: IntArray): Int? {
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val supported = caps.colorFormats.toSet()
        for (fmt in preferred) if (fmt in supported) return fmt
        return null
    }

    companion object {
        private const val TAG = "UseSense.V4Encoder"
    }
}
