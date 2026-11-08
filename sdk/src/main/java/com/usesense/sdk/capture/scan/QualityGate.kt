package com.usesense.sdk.capture.scan

import com.usesense.sdk.capture.ImageQualityAnalyzer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The capture quality gate, in priority order. Only the highest failing
 * check is shown as a hint, and capture starts only when none fails.
 * The order and every threshold are shared with the iOS and Web SDKs
 * (LiveSense capture proposal, "Quality gate").
 */
internal enum class GateCheck {
    FACE_FOUND, ONE_PERSON, IN_OVAL, DISTANCE, POSE, LIGHT, SHARPNESS, EYES_OPEN, STILLNESS,
}

/** One hint per failure. Several hints can belong to one check (closer / back). */
internal enum class GateHint(val check: GateCheck) {
    SHOW_FACE(GateCheck.FACE_FOUND),
    ONE_PERSON(GateCheck.ONE_PERSON),
    CENTRE(GateCheck.IN_OVAL),
    MOVE_CLOSER(GateCheck.DISTANCE),
    MOVE_BACK(GateCheck.DISTANCE),
    LOOK_STRAIGHT(GateCheck.POSE),
    /** Too dark while the screen fill light is still coming up. */
    BRIGHTENING(GateCheck.LIGHT),
    /** Still too dark with the fill light on. */
    FACE_LIGHT(GateCheck.LIGHT),
    TOO_BRIGHT(GateCheck.LIGHT),
    HOLD_STEADY(GateCheck.SHARPNESS),
    EYES_OPEN(GateCheck.EYES_OPEN),
    HOLD_STILL(GateCheck.STILLNESS),
}

/** One analysed preview frame, in the unmirrored, upright analysis image. */
internal class GateFrame(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Faces the landmarker found (each above its 0.5 detection/presence score). */
    val faceCount: Int,
    /** Primary face landmarks as x, y, z triples; x/y in image pixels, z in the same pixel scale. */
    val points: FloatArray?,
    val pose: PoseAngles?,
    val faceRegion: ImageQualityAnalyzer.FaceRegionReport?,
    val eyeBlinkLeft: Float? = null,
    val eyeBlinkRight: Float? = null,
) {
    val landmarkCount: Int get() = (points?.size ?: 0) / 3
}

/** The guide oval in view pixels. */
internal data class OvalSpec(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/** Maps an image-pixel point to view pixels, writing x then y into [out]. */
internal fun interface PointMapper {
    fun map(x: Float, y: Float, out: FloatArray)
}

internal data class GateResult(
    /** One hint per failing check, highest priority first. Empty when every check passes. */
    val failing: List<GateHint>,
    /** The face is too dark: the screen fill light should come on. */
    val faceTooDark: Boolean,
    /** The measured values, for the debug log and threshold tuning. */
    val metrics: GateMetrics? = null,
) {
    val allPass: Boolean get() = failing.isEmpty()
    val top: GateHint? get() = failing.firstOrNull()
}

/** What the gate measured on one frame (debug log, tuning). */
internal data class GateMetrics(
    /** Largest landmark radius in units of the margin-shrunk oval (<= 1 is inside). */
    val ovalFit: Float,
    val eyeDistanceRatio: Float,
    val jitter: Float,
    val pose: PoseAngles?,
    val luma: Double?,
    val clipped: Double?,
    val sharpness: Double?,
) {
    override fun toString(): String =
        "oval=%.2f dist=%.3f jitter=%.3f yaw=%.1f pitch=%.1f roll=%.1f luma=%.0f clip=%.3f sharp=%.1f".format(
            java.util.Locale.US, ovalFit, eyeDistanceRatio, jitter,
            pose?.yaw ?: Double.NaN, pose?.pitch ?: Double.NaN, pose?.roll ?: Double.NaN,
            luma ?: Double.NaN, clipped ?: Double.NaN, sharpness ?: Double.NaN,
        )
}

/**
 * Evaluates [GateFrame]s against the shared quality spec. Keeps the little
 * history the temporal checks need (face found for 3 frames, sharpness over
 * 3 frames, stillness over 300 ms). Pure Kotlin, so it is unit tested.
 */
internal class QualityGate(
    private val sharpnessThreshold: () -> Double = { MIN_SHARPNESS },
) {
    private var consecutiveFaceFrames = 0
    private val sharpnessHistory = ArrayDeque<Double>()
    private val stillHistory = ArrayDeque<Pair<Long, FloatArray>>()
    private val mapped = FloatArray(2)
    private var lastJitter = Float.NaN

    fun reset() {
        consecutiveFaceFrames = 0
        sharpnessHistory.clear()
        stillHistory.clear()
    }

    /**
     * @param fillLightOnForMs how long the screen fill light has been on, or
     *   null when it is off. Decides between "Brightening your screen" and
     *   "Face a light".
     */
    fun evaluate(
        frame: GateFrame,
        mapper: PointMapper,
        oval: OvalSpec,
        fillLightOnForMs: Long? = null,
    ): GateResult {
        val pts = frame.points
        val n = frame.landmarkCount
        if (frame.faceCount < 1 || pts == null || n < MIN_LANDMARKS) {
            reset()
            return GateResult(listOf(GateHint.SHOW_FACE), faceTooDark = false)
        }
        consecutiveFaceFrames++
        val failing = ArrayList<GateHint>(4)
        var tooDark = false

        // 1. Face found, steadily.
        if (consecutiveFaceFrames < FACE_FOUND_FRAMES) failing += GateHint.SHOW_FACE

        // 2. One person.
        if (frame.faceCount >= 2) failing += GateHint.ONE_PERSON

        // 3. Fully inside the oval (4% margin), in view space.
        val rx = oval.rx * (1f - OVAL_MARGIN)
        val ry = oval.ry * (1f - OVAL_MARGIN)
        var outside = false
        var worstFit = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            mapper.map(pts[i * 3], pts[i * 3 + 1], mapped)
            val vx = mapped[0]
            val vy = mapped[1]
            if (vx < minX) minX = vx
            if (vx > maxX) maxX = vx
            if (vy < minY) minY = vy
            if (vy > maxY) maxY = vy
            val dx = (vx - oval.cx) / rx
            val dy = (vy - oval.cy) / ry
            val fit = dx * dx + dy * dy
            if (fit > worstFit) worstFit = fit
            if (fit > 1f) outside = true
        }
        // A face as big as the oval (90% of its width or height) can't be
        // centred into it: that is a distance problem, so the hint is
        // "Move back a little", not "Move your face to the centre".
        val tooBigForOval = (maxX - minX) > 2 * rx * FILLS_OVAL || (maxY - minY) > 2 * ry * FILLS_OVAL
        if (outside && !tooBigForOval) failing += GateHint.CENTRE

        // 4. Distance: eye distance / frame width.
        val eyeDist = eyeDistance(pts, n)
        val ratio = eyeDist / frame.imageWidth.coerceAtLeast(1)
        when {
            (tooBigForOval && outside) || ratio > DISTANCE_MAX -> failing += GateHint.MOVE_BACK
            ratio < DISTANCE_MIN -> failing += GateHint.MOVE_CLOSER
        }

        // 5. Pose.
        frame.pose?.let { p ->
            if (abs(p.yaw) >= MAX_YAW || abs(p.pitch) >= MAX_PITCH || abs(p.roll) >= MAX_ROLL) {
                failing += GateHint.LOOK_STRAIGHT
            }
        }

        // 6. Light, on the face only.
        val region = frame.faceRegion
        var lightOk = true
        if (region != null) {
            val shadowRatio = region.clippedRatio - region.highlightRatio
            val dark = region.meanLuma < LUMA_MIN ||
                (region.clippedRatio >= CLIPPED_MAX && shadowRatio > region.highlightRatio)
            val bright = !dark && (region.meanLuma > LUMA_MAX || region.clippedRatio >= CLIPPED_MAX)
            if (dark) {
                tooDark = true
                lightOk = false
                val litLongEnough = fillLightOnForMs != null && fillLightOnForMs >= FILL_LIGHT_SETTLE_MS
                failing += if (litLongEnough) GateHint.FACE_LIGHT else GateHint.BRIGHTENING
            } else if (bright) {
                lightOk = false
                failing += GateHint.TOO_BRIGHT
            }
        }

        // 7. Sharpness. Skipped in bad light, where edges vanish anyway.
        if (region != null && lightOk) {
            sharpnessHistory.addLast(region.sharpness)
            while (sharpnessHistory.size > SHARPNESS_FRAMES) sharpnessHistory.removeFirst()
            val median = sharpnessHistory.sorted()[sharpnessHistory.size / 2]
            if (median < sharpnessThreshold()) failing += GateHint.HOLD_STEADY
        }

        // 8. Eyes open (when blendshapes are on).
        val bl = frame.eyeBlinkLeft
        val br = frame.eyeBlinkRight
        if (bl != null && br != null && (bl >= EYE_BLINK_MAX || br >= EYE_BLINK_MAX)) {
            failing += GateHint.EYES_OPEN
        }

        // 9. Stillness over 300 ms.
        if (!isStill(frame.timestampMs, pts, n, eyeDist)) failing += GateHint.HOLD_STILL

        val metrics = GateMetrics(
            ovalFit = kotlin.math.sqrt(worstFit),
            eyeDistanceRatio = ratio,
            jitter = lastJitter,
            pose = frame.pose,
            luma = region?.meanLuma,
            clipped = region?.clippedRatio,
            sharpness = sharpnessHistory.takeIf { it.isNotEmpty() }?.sorted()?.get(sharpnessHistory.size / 2),
        )
        return GateResult(failing, tooDark, metrics)
    }

    /** Iris-centre distance in pixels, or the eye-corner midpoints without iris points. */
    private fun eyeDistance(pts: FloatArray, n: Int): Float {
        return if (n >= 478) {
            dist(pts, LEFT_IRIS, RIGHT_IRIS)
        } else {
            val lx = (pts[33 * 3] + pts[133 * 3]) / 2
            val ly = (pts[33 * 3 + 1] + pts[133 * 3 + 1]) / 2
            val rx = (pts[362 * 3] + pts[263 * 3]) / 2
            val ry = (pts[362 * 3 + 1] + pts[263 * 3 + 1]) / 2
            hypot(rx - lx, ry - ly)
        }
    }

    private fun dist(pts: FloatArray, a: Int, b: Int): Float =
        hypot(pts[a * 3] - pts[b * 3], pts[a * 3 + 1] - pts[b * 3 + 1])

    /**
     * Landmark jitter over the last [STILL_WINDOW_MS]: for each stable point,
     * its largest distance from its own mean in the window, averaged over the
     * points and divided by eye distance. Needs at least [STILL_WINDOW_MS] of
     * history, so a face that just appeared is not yet "still".
     */
    private fun isStill(now: Long, pts: FloatArray, n: Int, eyeDist: Float): Boolean {
        val stable = FloatArray(STABLE_POINTS.size * 2)
        STABLE_POINTS.forEachIndexed { i, idx ->
            if (idx < n) {
                stable[i * 2] = pts[idx * 3]
                stable[i * 2 + 1] = pts[idx * 3 + 1]
            }
        }
        stillHistory.addLast(now to stable)
        // Keep one frame older than the window, so its start is covered.
        while (stillHistory.size > 2 && stillHistory[1].first <= now - STILL_WINDOW_MS) {
            stillHistory.removeFirst()
        }
        if (stillHistory.size < 2) return false
        if (now - stillHistory.first().first < STILL_WINDOW_MS - STILL_WINDOW_SLACK_MS) return false
        if (eyeDist <= 0f) return false

        var total = 0f
        val k = STABLE_POINTS.size
        for (p in 0 until k) {
            var mx = 0f
            var my = 0f
            for ((_, s) in stillHistory) {
                mx += s[p * 2]
                my += s[p * 2 + 1]
            }
            mx /= stillHistory.size
            my /= stillHistory.size
            var worst = 0f
            for ((_, s) in stillHistory) {
                worst = max(worst, hypot(s[p * 2] - mx, s[p * 2 + 1] - my))
            }
            total += worst
        }
        lastJitter = (total / k) / eyeDist
        return lastJitter < MAX_JITTER
    }

    companion object {
        const val MIN_LANDMARKS = 468
        const val FACE_FOUND_FRAMES = 3
        const val OVAL_MARGIN = 0.04f
        /** A face box this share of the (shrunk) oval is "too big", not "off centre". */
        const val FILLS_OVAL = 0.9f
        const val DISTANCE_MIN = 0.17f
        const val DISTANCE_MAX = 0.30f
        const val MAX_YAW = 15.0
        const val MAX_PITCH = 15.0
        const val MAX_ROLL = 12.0
        const val LUMA_MIN = 70.0
        const val LUMA_MAX = 200.0
        const val CLIPPED_MAX = 0.08
        const val SHARPNESS_FRAMES = 3
        const val EYE_BLINK_MAX = 0.6f
        const val STILL_WINDOW_MS = 300L
        /** Gate frames arrive every ~70-150 ms; allow one frame interval of slack. */
        const val STILL_WINDOW_SLACK_MS = 50L
        const val MAX_JITTER = 0.04f

        /** How long the fill light gets before "Face a light" replaces "Brightening your screen". */
        const val FILL_LIGHT_SETTLE_MS = 1500L

        const val LEFT_IRIS = 468
        const val RIGHT_IRIS = 473

        /**
         * Laplacian variance floor on the 96x96 face crop, below which a face is
         * plainly soft. Same value on iOS and Web; to tune from session data.
         */
        const val MIN_SHARPNESS = 12.0

        /** Rigid anchors (as iOS and Web): nose tip, outer eye corners, chin, forehead. */
        val STABLE_POINTS = intArrayOf(1, 33, 263, 152, 10)
    }
}
