package com.usesense.sdk.capture.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.usesense.sdk.capture.ImageQualityAnalyzer
import com.usesense.sdk.liveness.FaceLandmarkerOutputs
import com.usesense.sdk.liveness.MediaPipeModelInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the quality gate's face mesh on the live preview, before and during
 * the scan. Separate from the session's mesh pipeline (which fits captured
 * frames for upload): this one is display and gating only and sends nothing.
 *
 * The camera thread hands over a frame only when the previous one is done,
 * downscaled and upright, so a slow device lowers the gate's frame rate
 * instead of the capture's. Detection runs on its own thread.
 */
internal class LiveFaceAnalyzer(
    private val context: Context,
    private val onFrame: (GateFrame) -> Unit,
) {
    enum class InitState { IDLE, PENDING, READY, FAILED }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "usesense-gate") }
    private val busy = AtomicBoolean(false)
    private val qualityAnalyzer = ImageQualityAnalyzer()
    /** The covered-face check's numbers for the upload (metadata.covered_check). */
    val coverStats = CoveredCheckStats()
    private val coverSegmenter = FaceCoverSegmenter(context, coverStats)

    /** The latest segmentation result and its age at [nowMs], or nulls. */
    fun latestCover(nowMs: Long): Pair<FaceCover?, Long?> {
        val c = coverSegmenter.latest ?: return null to null
        return c to (nowMs - coverSegmenter.latestAt)
    }

    @Volatile private var landmarker: FaceLandmarker? = null
    @Volatile private var released = false
    @Volatile private var lastTimestampMs = 0L

    /** The covered-face segmentation is running (its model is present). */
    val coverCheckOn: Boolean get() = coverSegmenter.available

    /** The segmenter was at least tried (loaded or failed), so its numbers are worth sending. */
    val coverStatsTouched: Boolean get() = coverStats.tried

    @Volatile var initState: InitState = InitState.IDLE
        private set

    /** Frames are only taken while active (align and passive scan). */
    @Volatile var active = false

    /** Rolling mean detection time, for the debug log and tuning. */
    @Volatile var meanDetectMs = 0.0
        private set
    private var frames = 0

    /** Load the landmarker off the main thread; [onDone] runs on the gate thread. */
    fun initialize(onDone: (Boolean) -> Unit) {
        if (initState == InitState.READY) { onDone(true); return }
        if (initState == InitState.PENDING) return
        initState = InitState.PENDING
        executor.execute {
            val ok = try {
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MediaPipeModelInfo.ASSET_FILENAME).build())
                    .setRunningMode(RunningMode.VIDEO)
                    // Two, so a second person in view can be caught.
                    .setNumFaces(2)
                    .setMinFaceDetectionConfidence(MIN_DETECTION)
                    // 0.85 presence/tracking was tried for covered faces on the A03:
                    // it didn't drop a half-covered face and made the tracker
                    // re-detect a face it was already tracking (two faces, "Only you
                    // in the picture"). Covered faces are caught by CoverWatch.
                    .setMinFacePresenceConfidence(MIN_PRESENCE)
                    .setMinTrackingConfidence(MIN_TRACKING)
                    // Must match the session mesh (FaceLandmarkerOutputs): MediaPipe
                    // keeps the output stream positions in static fields.
                    .setOutputFaceBlendshapes(FaceLandmarkerOutputs.BLENDSHAPES)
                    .setOutputFacialTransformationMatrixes(FaceLandmarkerOutputs.TRANSFORMATION_MATRICES)
                    .build()
                if (!released) landmarker = com.usesense.sdk.liveness.MediaPipeGraphs.create { FaceLandmarker.createFromOptions(context, options) }
                landmarker != null
            } catch (e: Throwable) {
                // Without MediaPipe the capture still works: the activity
                // falls back to the shutter button with no mesh gate.
                Log.w(TAG, "Quality gate unavailable: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            initState = if (ok) InitState.READY else InitState.FAILED
            // Optional: the covered-face segmenter (off when its model is absent).
            if (ok && !released) coverSegmenter.initialize()
            onDone(ok)
        }
    }

    /**
     * Offer a camera frame (camera thread). Takes a downscaled, upright copy
     * when the gate is free and returns at once. Does not close [image].
     */
    fun offer(image: ImageProxy) {
        if (!active || released || landmarker == null) return
        if (!busy.compareAndSet(false, true)) return
        val small = try {
            toUprightSmall(image)
        } catch (e: Throwable) {
            null
        }
        if (small == null) { busy.set(false); return }
        val ts = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                process(small, ts)
            } catch (e: Throwable) {
                Log.w(TAG, "Gate frame failed: ${e.message}")
            } finally {
                small.recycle()
                busy.set(false)
            }
        }
    }

    /** Offer an upright frame from a non-CameraX source (the debug fake camera). Does not recycle [upright]. */
    fun offerBitmap(upright: Bitmap) {
        if (!active || released || landmarker == null) return
        if (!busy.compareAndSet(false, true)) return
        val scale = (TARGET_LONG_EDGE / maxOf(upright.width, upright.height)).coerceAtMost(1f)
        val small = runCatching {
            Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt(), (upright.height * scale).toInt(), true)
        }.getOrNull()
        if (small == null) { busy.set(false); return }
        val ts = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                process(small, ts)
            } catch (e: Throwable) {
                Log.w(TAG, "Gate frame failed: ${e.message}")
            } finally {
                if (small !== upright) small.recycle()
                busy.set(false)
            }
        }
    }

    private fun toUprightSmall(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val longEdge = maxOf(raw.width, raw.height).toFloat()
        val scale = (TARGET_LONG_EDGE / longEdge).coerceAtMost(1f)
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    private fun process(bmp: Bitmap, ts: Long) {
        val lm = landmarker ?: return
        if (released) return
        // VIDEO mode needs strictly increasing timestamps.
        val stamp = if (ts <= lastTimestampMs) lastTimestampMs + 1 else ts
        lastTimestampMs = stamp
        val t0 = SystemClock.elapsedRealtime()
        val result = lm.detectForVideo(BitmapImageBuilder(bmp).build(), stamp)
        val dt = SystemClock.elapsedRealtime() - t0
        frames++
        meanDetectMs = if (frames == 1) dt.toDouble() else meanDetectMs * 0.9 + dt * 0.1
        if (frames % 60 == 0) Log.d(TAG, "gate detect ~${meanDetectMs.toInt()} ms at ${bmp.width}x${bmp.height}")

        val w = bmp.width
        val h = bmp.height
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            coverSegmenter.clear()
            onFrame(GateFrame(ts, w, h, 0, null, null, null))
            return
        }
        // The subject is the largest face (MediaPipe doesn't order by size), as iOS and Web.
        val boxes = faces.map { f ->
            var x0 = 1f; var x1 = 0f; var y0 = 1f; var y1 = 0f
            for (p in f) { x0 = minOf(x0, p.x()); x1 = maxOf(x1, p.x()); y0 = minOf(y0, p.y()); y1 = maxOf(y1, p.y()) }
            floatArrayOf(x0, y0, x1, y1)
        }
        val faceIndex0 = boxes.indices.maxByOrNull { (boxes[it][2] - boxes[it][0]) * (boxes[it][3] - boxes[it][1]) } ?: 0
        val face = faces[faceIndex0]
        // A second face that mostly overlaps the subject is the same face found
        // twice (tracking and re-detection), not another person.
        val distinctFaces = 1 + boxes.indices.count { it != faceIndex0 && iou(boxes[it], boxes[faceIndex0]) < SAME_FACE_IOU }
        val faceIndex = faces.indexOf(face)
        val pts = FloatArray(face.size * 3)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        face.forEachIndexed { i, p ->
            val x = p.x() * w
            val y = p.y() * h
            pts[i * 3] = x
            pts[i * 3 + 1] = y
            pts[i * 3 + 2] = p.z() * w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val pose = runCatching {
            result.facialTransformationMatrixes().orElse(null)?.getOrNull(faceIndex)?.let(HeadPoseMath::fromMatrix)
        }.getOrNull()

        var blinkL: Float? = null
        var blinkR: Float? = null
        runCatching {
            result.faceBlendshapes().orElse(null)?.getOrNull(faceIndex)?.forEach { c ->
                when (c.categoryName()) {
                    "eyeBlinkLeft" -> blinkL = c.score()
                    "eyeBlinkRight" -> blinkR = c.score()
                }
            }
        }

        // Light and sharpness on the face box (the landmark bounds), as iOS and Web.
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val region = qualityAnalyzer.analyzeFaceRegion(
            argb, w, h, minX.toInt(), minY.toInt(), maxX.toInt() + 1, maxY.toInt() + 1,
        )
        val regions = FaceRegions.measure(argb, w, h, pts, face.size)
        // Covered-face segmentation on its own thread whenever it is free; the
        // latest result holds until replaced (or the face is lost).
        var coverPending = false
        var cover: FaceCover? = null
        if (coverSegmenter.available) {
            if (coverSegmenter.ready) {
                runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()?.let {
                    coverSegmenter.offer(it, pts.copyOf(), face.size, ts)
                }
            }
            cover = coverSegmenter.latest?.takeIf { ts - coverSegmenter.latestAt <= COVER_MAX_AGE_MS }
            coverPending = cover == null
            if (frames % 60 == 0) Log.d(TAG, "cover segmentation ~${coverSegmenter.meanMs.toInt()} ms")
        }
        onFrame(GateFrame(ts, w, h, distinctFaces, pts, pose, region, blinkL, blinkR, regions, cover, coverPending, coverSegmenter.latestAt))
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = ix * iy
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun release() {
        released = true
        active = false
        executor.execute {
            runCatching { landmarker?.close() }
            landmarker = null
            coverSegmenter.release()
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "UseSenseGate"
        /**
         * Long edge of the analysed copy: the analysis stream's own size, so
         * the face crop for sharpness isn't softened by a downscale first
         * (a 320 px copy read the golden frame at 9.7 against the shared floor
         * of 12). MediaPipe resizes for its models itself.
         */
        const val TARGET_LONG_EDGE = 640f
        /** A covered-face result older than this no longer counts. */
        const val COVER_MAX_AGE_MS = 3000L
        const val MIN_DETECTION = 0.5f
        const val MIN_PRESENCE = 0.5f
        const val MIN_TRACKING = 0.5f
        /** A second face overlapping the subject by more than this is the same face found twice. */
        const val SAME_FACE_IOU = 0.3f
    }
}
