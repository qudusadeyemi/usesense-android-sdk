package com.usesense.sdk.debug

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.usesense.sdk.internal.DebugCaptureHooks
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * DEBUG BUILDS ONLY. Fake camera for the capture test rig.
 *
 * Switched on by a file the host app can read without any permission:
 *   adb push face-portrait.mp4 /sdcard/Android/data/<app id>/files/
 *   adb push usesense_fake_camera.json /sdcard/Android/data/<app id>/files/
 * with
 *   { "video": "face-portrait.mp4", "freezeAt": 4.0, "forceState": "scan", "forceProgress": 0.5 }
 * All keys but "video" are optional. Delete the json to get the real camera back.
 */
internal class FakeCameraHooks(context: Context) : DebugCaptureHooks, DebugCaptureHooks.Enablable {
    private val dir = context.getExternalFilesDir(null)
    private val config: JSONObject? = dir?.let { File(it, CONFIG) }
        ?.takeIf { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
    private val video: File? = config?.optString("video")?.takeIf { it.isNotBlank() }
        ?.let { name -> File(name).takeIf { it.isAbsolute } ?: File(dir, name) }
        ?.takeIf { it.isFile }
    private val freezeAtS: Double? = config?.takeIf { it.has("freezeAt") }?.optDouble("freezeAt")
    @Volatile private var running = false

    override val enabled: Boolean get() = video != null
    override val forceState: String? = config?.optString("forceState")?.takeIf { it.isNotBlank() }
    override val forceProgress: Float? = config?.takeIf { it.has("forceProgress") }?.optDouble("forceProgress")?.toFloat()
    override val frozen: Boolean get() = freezeAtS != null

    override fun start(onFrame: (Bitmap) -> Unit) {
        val file = video ?: return
        running = true
        Log.w(TAG, "FAKE CAMERA from ${file.name} (debug build), freezeAt=$freezeAtS forceState=$forceState")
        thread(name = "usesense-fake-camera", isDaemon = true) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L) * 1000
                val held = freezeAtS?.let { mmr.getFrameAtTime((it * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) }
                var t = 0L
                while (running) {
                    val started = System.nanoTime()
                    val frame = held?.copy(Bitmap.Config.ARGB_8888, false)
                        ?: mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) onFrame(frame)
                    val spentMs = (System.nanoTime() - started) / 1_000_000
                    Thread.sleep((FRAME_MS - spentMs).coerceAtLeast(5))
                    // Advance by real time, so a slow decode plays at speed rather than in slow motion.
                    t = (t + maxOf(FRAME_MS, spentMs) * 1000) % durationUs.coerceAtLeast(1)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Fake camera stopped", e)
            } finally {
                runCatching { mmr.release() }
            }
        }
    }

    override fun stop() {
        running = false
    }

    private companion object {
        const val TAG = "UseSenseFakeCamera"
        const val CONFIG = "usesense_fake_camera.json"
        const val FRAME_MS = 66L
    }
}
