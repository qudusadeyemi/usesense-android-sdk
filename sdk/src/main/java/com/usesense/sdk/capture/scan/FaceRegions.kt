package com.usesense.sdk.capture.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Appearance of six face regions on one gate frame, so the gate can tell when
 * something comes in front of the face during the scan (a hand, a phone, a
 * sheet of paper). MediaPipe keeps predicting a plausible face under an
 * occluder, so the landmarks alone can't show it; the pixels can.
 *
 * Per region: luma relative to the mean of all regions (robust to the fill
 * light or a global exposure change), the red and blue chromaticity, and edge
 * energy (mean |dx| + |dy| of luma; fingers and object edges raise it, a
 * smooth palm or paper lowers it).
 */
internal class FaceRegions(val values: FloatArray) {

    fun luma(i: Int) = values[i * STRIDE]
    fun red(i: Int) = values[i * STRIDE + 1]
    fun blue(i: Int) = values[i * STRIDE + 2]
    fun edge(i: Int) = values[i * STRIDE + 3]

    companion object {
        /** Forehead, two cheeks, nose, mouth, chin (MediaPipe 478-point mesh). */
        val ANCHORS = intArrayOf(151, 50, 280, 5, 13, 199)
        /** Names as in COVER-v1 section 3 (landmark 50 is the subject's right cheek, 280 the left). */
        val NAMES = arrayOf("forehead", "cheek_r", "cheek_l", "nose", "mouth", "chin")
        const val COUNT = 6
        const val STRIDE = 4
        /** Patch half-size as a fraction of face width (cheek to cheek). */
        const val PATCH_OF_FACE_W = 0.07f

        /**
         * @param argb the analysis frame, [w] x [h]
         * @param pts landmarks as x, y, z triples in image px
         */
        fun measure(argb: IntArray, w: Int, h: Int, pts: FloatArray, count: Int): FaceRegions? {
            if (count < 468) return null
            val faceW = abs(pts[454 * 3] - pts[234 * 3])
            val half = max(2, (PATCH_OF_FACE_W * faceW).toInt())
            val out = FloatArray(COUNT * STRIDE)
            var lumaSum = 0f
            for ((r, idx) in ANCHORS.withIndex()) {
                val cx = pts[idx * 3].toInt()
                val cy = pts[idx * 3 + 1].toInt()
                val x0 = max(1, cx - half); val x1 = min(w - 2, cx + half)
                val y0 = max(1, cy - half); val y1 = min(h - 2, cy + half)
                if (x1 <= x0 || y1 <= y0) return null
                var sl = 0f; var sr = 0f; var sb = 0f; var se = 0f; var n = 0
                var y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val p = argb[y * w + x]
                        val red = (p shr 16) and 0xFF
                        val green = (p shr 8) and 0xFF
                        val blue = p and 0xFF
                        val l = luma(p)
                        val sum = (red + green + blue).coerceAtLeast(1)
                        sl += l
                        sr += red.toFloat() / sum
                        sb += blue.toFloat() / sum
                        se += abs(luma(argb[y * w + x + 1]) - luma(argb[y * w + x - 1])) +
                            abs(luma(argb[(y + 1) * w + x]) - luma(argb[(y - 1) * w + x]))
                        n++
                        x += 2
                    }
                    y += 2
                }
                if (n == 0) return null
                out[r * STRIDE] = sl / n
                out[r * STRIDE + 1] = sr / n
                out[r * STRIDE + 2] = sb / n
                out[r * STRIDE + 3] = se / n
                lumaSum += sl / n
            }
            // Luma relative to the face's mean, so a global change cancels.
            val mean = (lumaSum / COUNT).coerceAtLeast(1f)
            for (r in 0 until COUNT) out[r * STRIDE] /= mean
            return FaceRegions(out)
        }

        private fun luma(p: Int): Float =
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
    }
}

/**
 * Watches the face regions through one scan: the first [BASELINE_FRAMES]
 * frames set a baseline, after which a region that changes beyond the limits
 * means something covered the face (or the face moved a lot). Pure, so tested.
 */
internal class CoverWatch {
    private var baseline: FloatArray? = null
    private var frames = 0
    /** The largest change seen on the last frame, for the debug log. */
    var lastScore = 0f; private set
    var lastRegion = -1; private set
    /** Raw changes on the last frame after the baseline (max over regions), for the upload scores. */
    var lastLumaDelta = 0f; private set
    var lastChromaDelta = 0f; private set
    var lastEdgeFactor = 1f; private set
    /** The baseline is set and the last frame was compared against it. */
    var compared = false; private set
    /**
     * Consecutive failing frames per region. COVER-v1 section 2: a region must
     * fail on [REQUIRED_FRAMES] frames in a row (the same region; a pass resets
     * it) to count as covered, so a single-frame exceedance (a clear face on an
     * iPhone 17 Pro hit luma 0.235 for one frame, 7 times) is ignored.
     */
    private val streak = IntArray(FaceRegions.COUNT)
    /** Single-frame exceedances that ended on the last frame (failed once, then passed). */
    var lastSingleFrameFlags = 0; private set

    fun reset() {
        baseline = null
        frames = 0
        lastScore = 0f
        lastRegion = -1
        compared = false
        streak.fill(0)
        lastSingleFrameFlags = 0
    }

    /** True when the face looks covered compared with the start of the scan. */
    fun covered(regions: FaceRegions?): Boolean {
        compared = false
        lastSingleFrameFlags = 0
        if (regions == null) return false
        val v = regions.values
        val base = baseline
        if (base == null || frames < BASELINE_FRAMES) {
            val b = base ?: FloatArray(v.size).also { baseline = it }
            // Running mean of the first frames.
            for (i in v.indices) b[i] += (v[i] - b[i]) / (frames + 1)
            frames++
            lastScore = 0f
            return false
        }
        var worst = 0f
        var worstRegion = -1
        var covering = -1
        var coveringScore = 0f
        var singles = 0
        var maxLuma = 0f; var maxChroma = 0f; var maxEdge = 1f
        for (r in 0 until FaceRegions.COUNT) {
            val o = r * FaceRegions.STRIDE
            val lumaDelta = abs(v[o] - base[o])
            val chromaDelta = max(abs(v[o + 1] - base[o + 1]), abs(v[o + 2] - base[o + 2]))
            val e0 = base[o + 3] + EDGE_FLOOR
            val e1 = v[o + 3] + EDGE_FLOOR
            val edgeFactor = max(e1 / e0, e0 / e1)
            maxLuma = max(maxLuma, lumaDelta); maxChroma = max(maxChroma, chromaDelta); maxEdge = max(maxEdge, edgeFactor)
            val score = maxOf(lumaDelta / LUMA_LIMIT, chromaDelta / CHROMA_LIMIT, (edgeFactor - 1f) / (EDGE_RATIO_LIMIT - 1f))
            if (score > worst) { worst = score; worstRegion = r }
            if (score >= 1f) {
                streak[r]++
                if (streak[r] >= REQUIRED_FRAMES && score > coveringScore) { covering = r; coveringScore = score }
            } else {
                if (streak[r] == 1) singles++
                streak[r] = 0
            }
        }
        lastScore = worst
        // The region that is covering, else the worst one (debug log).
        lastRegion = if (covering >= 0) covering else worstRegion
        lastSingleFrameFlags = singles
        lastLumaDelta = maxLuma
        lastChromaDelta = maxChroma
        lastEdgeFactor = maxEdge
        compared = true
        return covering >= 0
    }

    companion object {
        const val BASELINE_FRAMES = 3
        /** Consecutive failing frames on one region before it counts as covered. */
        const val REQUIRED_FRAMES = 2
        /** Change in a region's luma relative to the face mean. */
        const val LUMA_LIMIT = 0.22f
        /** Change in red or blue chromaticity. */
        const val CHROMA_LIMIT = 0.035f
        /** Edge energy may rise or fall by this factor. */
        const val EDGE_RATIO_LIMIT = 2.2f
        const val EDGE_FLOOR = 4f
    }
}
