package com.usesense.sdk.capture.scan

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** How much of the face area the segmenter sees as something other than face. */
internal data class FaceCover(
    /** Share labelled body skin: a hand or an arm in front of the face. */
    val bodySkin: Float,
    /** Share labelled background, clothes or other objects (a phone, paper). */
    val other: Float,
) {
    val covered: Boolean get() = bodySkin >= BODY_SKIN_MAX || other >= OTHER_MAX

    companion object {
        const val BODY_SKIN_MAX = 0.12f
        const val OTHER_MAX = 0.25f

        // selfie_multiclass_256x256 categories.
        const val BACKGROUND = 0
        const val HAIR = 1
        const val BODY_SKIN = 2
        const val FACE_SKIN = 3
        const val CLOTHES = 4
        const val OTHERS = 5

        /** Shrink of the face silhouette toward its centre, so edge pixels don't count. */
        const val INNER = 0.85f

        /**
         * Share of the face area by category, from a category [mask]
         * ([mw] x [mh], one byte per pixel) of a [imgW] x [imgH] frame, inside
         * the FACE_OVAL silhouette of [pts] (x, y, z triples in image px)
         * shrunk to [INNER]. Pure, so tested.
         */
        fun measure(mask: (Int) -> Int, mw: Int, mh: Int, imgW: Int, imgH: Int, pts: FloatArray, count: Int): FaceCover? {
            if (count < 468) return null
            val ring = QualityGate.FACE_OVAL
            var cx = 0f; var cy = 0f
            for (k in ring) { cx += pts[k * 3]; cy += pts[k * 3 + 1] }
            cx /= ring.size; cy /= ring.size
            val px = FloatArray(ring.size) { cx + (pts[ring[it] * 3] - cx) * INNER }
            val py = FloatArray(ring.size) { cy + (pts[ring[it] * 3 + 1] - cy) * INNER }
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in px.indices) {
                minX = minOf(minX, px[i]); maxX = maxOf(maxX, px[i])
                minY = minOf(minY, py[i]); maxY = maxOf(maxY, py[i])
            }
            val sx = mw.toFloat() / imgW
            val sy = mh.toFloat() / imgH
            var total = 0; var body = 0; var other = 0
            val step = maxOf(1f, (maxX - minX) / 40f)
            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    if (inside(px, py, x, y)) {
                        val mx = (x * sx).toInt().coerceIn(0, mw - 1)
                        val my = (y * sy).toInt().coerceIn(0, mh - 1)
                        when (mask(my * mw + mx)) {
                            BODY_SKIN -> body++
                            BACKGROUND, CLOTHES, OTHERS -> other++
                        }
                        total++
                    }
                    x += step
                }
                y += step
            }
            if (total == 0) return null
            return FaceCover(body.toFloat() / total, other.toFloat() / total)
        }

        private fun inside(px: FloatArray, py: FloatArray, x: Float, y: Float): Boolean {
            var c = false
            var j = px.size - 1
            for (i in px.indices) {
                if ((py[i] > y) != (py[j] > y) && x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i]) c = !c
                j = i
            }
            return c
        }
    }
}

/**
 * MediaPipe's multiclass selfie segmentation (hair, face skin, body skin,
 * clothes, other), run on the gate thread every few frames to tell whether
 * the face is covered: a hand reads as body skin, a phone or paper as other.
 * Works from a single frame, so it also catches a face that was covered
 * before the scan started (CoverWatch only sees changes during the scan).
 *
 * The 16 MB model ships in the SDK's assets ([MODEL], SegmenterModelInfo).
 * Debug and repacked builds may drop it; then it is loaded from the app's
 * external files dir if present, and without it the check is off and capture
 * works as before.
 */
internal class FaceCoverSegmenter(private val context: Context) {
    private var segmenter: ImageSegmenter? = null
    @Volatile var available = false; private set
    /**
     * Segmentation takes ~900 ms on a Galaxy A03, so it runs on its own
     * low-priority thread and never holds the gate; the latest result is kept.
     */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "usesense-cover")
    }
    private val busy = AtomicBoolean(false)
    @Volatile var latest: FaceCover? = null; private set
    @Volatile var latestAt = 0L; private set
    @Volatile private var released = false

    /** True when [offer] would start a run now. */
    val ready: Boolean get() = available && !released && !busy.get()
    /** Rolling mean run time, for the debug log. */
    var meanMs = 0.0; private set
    private var runs = 0

    /** Call on the gate thread. */
    fun initialize(): Boolean {
        if (segmenter != null) return true
        return try {
            val base = BaseOptions.builder().apply {
                val asset = runCatching { context.assets.open(MODEL).close(); true }.getOrDefault(false)
                if (asset) {
                    setModelAssetPath(MODEL)
                } else {
                    val file = File(context.getExternalFilesDir(null), MODEL)
                    if (!file.isFile) return false
                    setModelAssetBuffer(map(file))
                }
            }.build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            segmenter = ImageSegmenter.createFromOptions(context, options)
            available = true
            Log.i(TAG, "Face cover segmentation on ($MODEL)")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Face cover segmentation unavailable: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Start a segmentation of [bmp] (taken over, recycled here) for the face in
     * [pts] if none is running; returns false (and recycles) when busy.
     * [stampMs] is the frame's time, kept with the result.
     */
    fun offer(bmp: Bitmap, pts: FloatArray, count: Int, stampMs: Long): Boolean {
        if (!available || released || !busy.compareAndSet(false, true)) {
            bmp.recycle()
            return false
        }
        executor.execute {
            try {
                if (!released) measure(bmp, pts, count)?.let { latest = it; latestAt = stampMs }
            } catch (e: Throwable) {
                Log.w(TAG, "Cover segmentation failed: ${e.message}")
            } finally {
                bmp.recycle()
                busy.set(false)
            }
        }
        return true
    }

    /** Forget the last result (the face was lost). */
    fun clear() {
        latest = null
        latestAt = 0L
    }

    /** Segment [bmp] and measure the face area of [pts]. Runs on the cover thread. */
    fun measure(bmp: Bitmap, pts: FloatArray, count: Int): FaceCover? {
        val seg = segmenter ?: return null
        val t0 = SystemClock.elapsedRealtime()
        val result = seg.segment(BitmapImageBuilder(bmp).build())
        val mask = result.categoryMask().orElse(null) ?: return null
        val buf = ByteBufferExtractor.extract(mask)
        val cover = FaceCover.measure({ buf.get(it).toInt() and 0xFF }, mask.width, mask.height, bmp.width, bmp.height, pts, count)
        val dt = SystemClock.elapsedRealtime() - t0
        runs++
        meanMs = if (runs == 1) dt.toDouble() else meanMs * 0.9 + dt * 0.1
        return cover
    }

    fun release() {
        released = true
        available = false
        executor.execute {
            runCatching { segmenter?.close() }
            segmenter = null
        }
        executor.shutdown()
    }

    private fun map(file: File): ByteBuffer = FileInputStream(file).use { s ->
        s.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    companion object {
        private const val TAG = "UseSenseGate"
        const val MODEL = com.usesense.sdk.liveness.SegmenterModelInfo.ASSET_FILENAME
    }
}
