package com.usesense.sdk.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioCaptureManager(
    private val context: Context,
    private val cacheDir: File,
) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    fun startRecording(durationMs: Int): Boolean {
        return try {
            val file = File(cacheDir, "usesense_audio_${System.currentTimeMillis()}.webm")
            outputFile = file

            recorder = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(48000)
                setAudioChannels(1)
                setAudioEncodingBitRate(48000)
                setMaxDuration(durationMs)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecording()
                    }
                }
                prepare()
                start()
            }
            isRecording = true
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    fun stopRecording(): ByteArray? {
        if (!isRecording) return null
        return try {
            recorder?.stop()
            isRecording = false
            outputFile?.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            release()
        }
    }

    fun release() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        outputFile?.delete()
        outputFile = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
