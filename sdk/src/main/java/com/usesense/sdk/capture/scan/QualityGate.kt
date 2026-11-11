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
    // v1.2: distance runs before in-frame, so size problems get size guidance.
    FACE_FOUND, ONE_PERSON, COVERED, DISTANCE, IN_OVAL, POSE, LIGHT, SHARPNESS, EYES_OPEN, STILLNESS,
}

/** One hint per failure. Several hints can belong to one check (closer / back). */
internal enum class GateHint(val check: GateCheck) {
    SHOW_FACE(GateCheck.FACE_FOUND),
    ONE_PERSON(GateCheck.ONE_PERSON),
    /** During the scan the face changed as if something came in front of it (a hand, a phone). */
    FACE_COVERED(GateCheck.COVERED),
    MOVE_CLOSER(GateCheck.DISTANCE),
    MOVE_BACK(GateCheck.DISTANCE),
    CENTRE(GateCheck.IN_OVAL),
    /** In-frame fails but the face is centred: it is too big for the oval. */
    TOO_BIG(GateCheck.IN_OVAL),
    LOOK_STRAIGHT(GateCheck.POSE),
    /** Too dark while the screen fill light is still coming up (no text: the light works silently). */
    BRIGHTENING(GateCheck.LIGHT),
    /** Still too dark with the fill light on: "Find more light". */
    FACE_LIGHT(GateCheck.LIGHT),
    TOO_BRIGHT(GateCheck.LIGHT),
    /**
     * No face in a dark frame (the landmarker can't find one in the dark):
     * the fill light is coming up, so no text yet. Blocks like [SHOW_FACE].
     */
    FILLING_NO_FACE(GateCheck.FACE_FOUND),
    /** No face in a dark frame with the fill light on and settled: "Find more light" instead of "Show your face". */
    DARK_NO_FACE(GateCheck.FACE_FOUND),
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
    /** Appearance of six face regions, for the covered-face check during the scan. */
    val regions: FaceRegions? = null,
    /** Segmentation of the face area (null when the segmenter is off or stale). */
    val cover: FaceCover? = null,
    /** The segmenter is on but has no result for this face yet: don't pass. */
    val coverPending: Boolean = false,
    /** Time of the frame the [cover] result was segmented from. */
    val coverAt: Long = 0L,
    /**
     * Mean luma of the middle 60% of the frame (x and y 0.2 to 0.8), measured
     * on frames with no face: the face box can't say it is too dark then.
     */
    val frameLuma: Double? = null,
) {
    val landmarkCount: Int get() = (points?.size ?: 0) / 3
}

/**
 * A movement the extra check asks for (Head Turn, Follow Dot). Every other
 * check still runs as in the scan; only these give way:
 *  - pose may go to [MAX_YAW] / [MAX_PITCH] on the axis the step asks for;
 *  - while [moving], stillness and the covered-face change watch are off (the
 *    face is meant to move; the segmenter's covered check still runs).
 */
internal data class GateMotion(
    val yawAllowed: Boolean,
    val pitchAllowed: Boolean,
    val moving: Boolean,
) {
    companion object {
        const val MAX_YAW = 50.0
        const val MAX_PITCH = 35.0
    }
}

/** The guide oval in view pixels. */
internal data class OvalSpec(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/** Maps an image-pixel point to view pixels, writing x then y into [out]. */
internal fun interface PointMapper {
    fun map(x: Float, y: Float, out: FloatArray)
}

internal data class GateResult(
    /**
     * One hint per failing check, highest priority first. Light hints are
     * advice only: they never block the scan (a darker face in ordinary room
     * light reads under the luma floor, and must not be locked out).
     */
    val failing: List<GateHint>,
    /** The face is too dark: the screen fill light should come on. */
    val faceTooDark: Boolean,
    /** The measured values, for the debug log and threshold tuning. */
    val metrics: GateMetrics? = null,
    /** Stillness failed on this frame (motion wins over covered). */
    val moving: Boolean = false,
) {
    /**
     * A drop on this frame counts as a drop for cover only when covered was the
     * reason and the face wasn't moving (COVER-v1 "Steady baseline and motion" 3).
     */
    val coverDrop: Boolean get() = GateHint.FACE_COVERED in failing && !moving

    /** The failures that hold the scan: everything except light. */
    val blocking: List<GateHint> get() = failing.filter { it.check != GateCheck.LIGHT }
    val allPass: Boolean get() = blocking.isEmpty()
    val top: GateHint? get() = failing.firstOrNull()
}

/** What the gate measured on one frame (debug log, tuning). */
internal data class GateMetrics(
    /** Largest landmark radius in units of the margin-shrunk oval (<= 1 is inside). */
    val ovalFit: Float,
    val eyeDistanceRatio: Float,
    /** Eye distance on screen, px (independent of the oval's size; the closer step compares it). */
    val eyeDistancePx: Float = 0f,
    val jitter: Float,
    val pose: PoseAngles?,
    val luma: Double?,
    val clipped: Double?,
    val sharpness: Double?,
    /** Covered-face change score during the scan (>= 1 fails) and its region. */
    val cover: Float = 0f,
    val coverRegion: Int = -1,
    /** Segmenter shares of the face area: body skin (a hand) and other. */
    val segBodySkin: Float? = null,
    val segOther: Float? = null,
) {
    override fun toString(): String =
        "oval=%.2f dist=%.3f jitter=%.3f yaw=%.1f pitch=%.1f roll=%.1f luma=%.0f clip=%.3f sharp=%.1f".format(
            java.util.Locale.US, ovalFit, eyeDistanceRatio, jitter,
            pose?.yaw ?: Double.NaN, pose?.pitch ?: Double.NaN, pose?.roll ?: Double.NaN,
            luma ?: Double.NaN, clipped ?: Double.NaN, sharpness ?: Double.NaN,
        ) + (if (coverRegion >= 0) " cover=%.2f@%s".format(java.util.Locale.US, cover, FaceRegions.NAMES[coverRegion]) else "") +
            (if (segBodySkin != null) " seg=hand %.2f other %.2f".format(java.util.Locale.US, segBodySkin, segOther ?: 0f) else "")
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
    private var vx = FloatArray(478)
    private var vy = FloatArray(478)
    private var lastJitter = Float.NaN
    private val coverWatch = CoverWatch()
    private var wasScanning = false
    private var changeTripped = false

    // Fill light vs the change watch (COVER-v1 "Fill light and the change watch").
    private var lightChangedAt = Long.MIN_VALUE
    private var handledLightAt = Long.MIN_VALUE
    private var scanStartedAt = 0L

    /**
     * The fill light was switched at [atMs] (frame clock). If that falls in a
     * running scan (or within [LIGHT_SETTLE_MS] of its start, inside the
     * baseline), the change watch is ignored for [LIGHT_SETTLE_MS] and then
     * re-baselined, only while the segmenter says the face is clear.
     * Never re-baselined on a brightness change alone: a hand moves it too.
     */
    fun onLightChanged(atMs: Long) {
        lightChangedAt = atMs
    }

    /** Receives the change watch's numbers for the upload (metadata.covered_check). */
    var coverStats: CoveredCheckStats? = null

    /** When the frame first read dark with no face (null: it doesn't). */
    private var darkNoFaceSince: Long? = null

    /**
     * No face. In the dark the landmarker finds none, so the face-box light
     * check never runs and the fill light never came on (iPhone 17 Pro, as
     * iOS PR #102). On align only: a frame darker than [LUMA_MIN] for
     * [DARK_NO_FACE_AFTER_MS] turns the fill light on; nothing shows while it
     * settles, then "Find more light" replaces "Show your face". During a scan
     * or a movement nothing changes (the fill light is frozen there).
     */
    private fun noFace(frame: GateFrame, fillLightOnForMs: Long?, frozen: Boolean): GateResult {
        val luma = frame.frameLuma
        if (frozen || luma == null || luma >= LUMA_MIN) {
            darkNoFaceSince = null
            return GateResult(listOf(GateHint.SHOW_FACE), faceTooDark = false)
        }
        val since = darkNoFaceSince ?: frame.timestampMs.also { darkNoFaceSince = it }
        val settled = fillLightOnForMs != null && fillLightOnForMs >= FILL_LIGHT_SETTLE_MS
        return GateResult(
            listOf(if (settled) GateHint.DARK_NO_FACE else GateHint.FILLING_NO_FACE),
            faceTooDark = frame.timestampMs - since >= DARK_NO_FACE_AFTER_MS,
        )
    }

    fun reset() {
        consecutiveFaceFrames = 0
        sharpnessHistory.clear()
        stillHistory.clear()
        coverWatch.reset()
    }

    /**
     * @param fillLightOnForMs how long the screen fill light has been on, or
     *   null when it is off. Decides between "Brightening your screen" and
     *   "Face a light".
     * @param scanning the scan is running: the distance band widens by
     *   [DISTANCE_HOLD_MARGIN] each side, so a face sitting on a band edge
     *   doesn't flicker in and out and drop the scan.
     */
    fun evaluate(
        frame: GateFrame,
        mapper: PointMapper,
        oval: OvalSpec,
        fillLightOnForMs: Long? = null,
        scanning: Boolean = false,
        motion: GateMotion? = null,
    ): GateResult {
        // A new scan takes a new baseline for the covered-face check.
        if (scanning && !wasScanning) {
            coverWatch.reset()
            changeTripped = false
            scanStartedAt = frame.timestampMs
        }
        wasScanning = scanning
        val pts = frame.points
        val n = frame.landmarkCount
        if (frame.faceCount < 1 || pts == null || n < MIN_LANDMARKS) {
            reset()
            return noFace(frame, fillLightOnForMs, scanning || motion != null)
        }
        darkNoFaceSince = null
        consecutiveFaceFrames++
        val failing = ArrayList<GateHint>(4)
        var tooDark = false

        // 1. Face found, steadily.
        if (consecutiveFaceFrames < FACE_FOUND_FRAMES) failing += GateHint.SHOW_FACE

        // 2. One person.
        if (frame.faceCount >= 2) failing += GateHint.ONE_PERSON

        // 2b. Covered: the segmenter sees a hand or an object over the face (any
        // stage), or, during the scan, a face region changed sharply since it
        // started.
        val segCovered = frame.cover?.covered == true
        // A fill-light change in this scan: hold the change watch off while the
        // exposure settles, then re-baseline it, but only on a clear face.
        var watchOff = false
        if (scanning && lightChangedAt != handledLightAt && lightChangedAt >= scanStartedAt - LIGHT_SETTLE_MS) {
            watchOff = true
            if (frame.timestampMs >= lightChangedAt + LIGHT_SETTLE_MS) {
                val segmenterOn = frame.cover != null || frame.coverPending
                val clear = !segmenterOn || (frame.cover != null && !frame.cover.covered)
                if (clear) {
                    coverWatch.reset()
                    changeTripped = false
                    handledLightAt = lightChangedAt
                    coverStats?.changeWatchRebaseline()
                    watchOff = false
                } else {
                    // Covered or no result yet: wait, with the scan paused.
                    failing += GateHint.HOLD_STILL
                }
            }
        }
        if (segCovered) failing += GateHint.FACE_COVERED
        // The change watch runs at the end (after stillness and position), see below.

        // No cover result yet for this face: hold (shown as "Hold still"), so a
        // scan can't start before the covered check has seen the face.
        if (frame.coverPending) failing += GateHint.HOLD_STILL

        // Map once to screen space (aspect-fill + mirror, as the preview).
        val n0 = n
        if (vx.size < n0) { vx = FloatArray(n0); vy = FloatArray(n0) }
        for (i in 0 until n0) {
            mapper.map(pts[i * 3], pts[i * 3 + 1], mapped)
            vx[i] = mapped[0]
            vy[i] = mapped[1]
        }

        // 3. Distance: eye ratio = eye distance / (oval width / 0.72), in screen space.
        val eyeDistView = eyeDistance(vx, vy, n)
        val ratio = eyeDistView / (2f * oval.rx / OVAL_WIDTH_OF_SCREEN)
        val margin = if (scanning) DISTANCE_HOLD_MARGIN else 0f
        when {
            ratio > DISTANCE_MAX + margin -> failing += GateHint.MOVE_BACK
            ratio < DISTANCE_MIN - margin -> failing += GateHint.MOVE_CLOSER
        }

        // 4. In frame: the face silhouette within 1.15 x the oval radius.
        var worstFit = 0f
        for (idx in FACE_OVAL) {
            if (idx >= n) continue
            val dx = (vx[idx] - oval.cx) / oval.rx
            val dy = (vy[idx] - oval.cy) / oval.ry
            worstFit = max(worstFit, kotlin.math.sqrt(dx * dx + dy * dy))
        }
        if (worstFit > IN_FRAME_LIMIT) {
            // Position vs size: a face centred where a well-placed face sits
            // (low in the oval, which leaves room for hair) is too big, not off-centre.
            val fcx = (vx[10] + vx[152] + vx[234] + vx[454]) / 4f
            val fcy = (vy[10] + vy[152] + vy[234] + vy[454]) / 4f
            val ex = kotlin.math.abs(fcx - oval.cx) / oval.rx
            val ey = kotlin.math.abs(fcy - (oval.cy + EXPECTED_FACE_DROP * oval.ry)) / oval.ry
            if (ex <= CENTRED_TOLERANCE && ey <= CENTRED_TOLERANCE) {
                if (GateHint.MOVE_BACK !in failing) failing += GateHint.TOO_BIG
            } else {
                failing += GateHint.CENTRE
            }
        }
        val eyeDist = eyeDistance(pts, n)

        // 5. Pose.
        frame.pose?.let { p ->
            val maxYaw = if (motion?.yawAllowed == true) GateMotion.MAX_YAW else MAX_YAW
            val maxPitch = if (motion?.pitchAllowed == true) GateMotion.MAX_PITCH else MAX_PITCH
            if (abs(p.yaw) >= maxYaw || abs(p.pitch) >= maxPitch || abs(p.roll) >= MAX_ROLL) {
                failing += GateHint.LOOK_STRAIGHT
            }
        }

        // 6. Light, on the face only. Advice, never a block: it turns the
        // fill light on and gives a hint, but the scan goes ahead.
        val region = frame.faceRegion
        if (region != null) {
            val shadowRatio = region.clippedRatio - region.highlightRatio
            val dark = region.meanLuma < LUMA_MIN ||
                (region.clippedRatio >= CLIPPED_MAX && shadowRatio > region.highlightRatio)
            val bright = !dark && (region.meanLuma > LUMA_MAX || region.clippedRatio >= CLIPPED_MAX)
            if (dark) {
                tooDark = true
                val litLongEnough = fillLightOnForMs != null && fillLightOnForMs >= FILL_LIGHT_SETTLE_MS
                failing += if (litLongEnough) GateHint.FACE_LIGHT else GateHint.BRIGHTENING
            } else if (bright) {
                failing += GateHint.TOO_BRIGHT
            }
        }

        // 7. Sharpness.
        if (region != null) {
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
        val still = isStill(frame.timestampMs, pts, n, eyeDist)
        val moving = motion?.moving == true
        if (!still && !moving) failing += GateHint.HOLD_STILL

        // 2b (continued). The change watch (scan only). Its baseline is taken only
        // from settled frames, the face still, in the oval, at the distance and
        // facing forward, so a scan started from the shutter or while the face was
        // still moving in doesn't freeze a bad baseline (A03: 11 s false
        // "covered" from a baseline taken mid-movement). A change while the face
        // is moving reads "Hold still" (stillness already pauses the scan), not
        // "Keep your face uncovered".
        val settled = still && failing.none {
            it.check == GateCheck.FACE_FOUND || it.check == GateCheck.DISTANCE ||
                it.check == GateCheck.IN_OVAL || it.check == GateCheck.POSE
        }
        if (scanning && !watchOff && !moving) {
            val changed = coverWatch.covered(frame.regions, baselineAllowed = settled, counting = still)
            if (coverWatch.baselineJustTaken) coverStats?.changeWatchBaselineTaken(frame.timestampMs - scanStartedAt)
            if (coverWatch.compared) {
                coverStats?.changeWatchFrame(coverWatch.lastLumaDelta, coverWatch.lastChromaDelta, coverWatch.lastEdgeFactor)
                if (coverWatch.lastSingleFrameFlags > 0) coverStats?.changeWatchSingleFrameFlags(coverWatch.lastSingleFrameFlags)
                val pausesForCover = changed && still
                if (pausesForCover && !changeTripped) {
                    coverStats?.changeWatchTrip(FaceRegions.NAMES.getOrNull(coverWatch.lastRegion))
                }
                changeTripped = pausesForCover
                if (pausesForCover && GateHint.FACE_COVERED !in failing) insertInOrder(failing, GateHint.FACE_COVERED)
            }
        }

        val metrics = GateMetrics(
            ovalFit = worstFit,
            eyeDistanceRatio = ratio,
            eyeDistancePx = eyeDistView,
            jitter = lastJitter,
            pose = frame.pose,
            luma = region?.meanLuma,
            clipped = region?.clippedRatio,
            sharpness = sharpnessHistory.takeIf { it.isNotEmpty() }?.sorted()?.get(sharpnessHistory.size / 2),
            cover = if (scanning) coverWatch.lastScore else 0f,
            coverRegion = if (scanning) coverWatch.lastRegion else -1,
            segBodySkin = frame.cover?.bodySkin,
            segOther = frame.cover?.other,
        )
        return GateResult(failing, tooDark, metrics, moving = !still)
    }

    /** Iris-centre distance on screen ([xs], [ys]), or the eye-corner midpoints without iris points. */
    private fun eyeDistance(xs: FloatArray, ys: FloatArray, n: Int): Float {
        return if (n >= 478) {
            hypot(xs[LEFT_IRIS] - xs[RIGHT_IRIS], ys[LEFT_IRIS] - ys[RIGHT_IRIS])
        } else {
            hypot((xs[362] + xs[263]) / 2 - (xs[33] + xs[133]) / 2, (ys[362] + ys[263]) / 2 - (ys[33] + ys[133]) / 2)
        }
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

    /** Insert [hint] keeping the list in check order (the first is the one shown). */
    private fun insertInOrder(list: MutableList<GateHint>, hint: GateHint) {
        val at = list.indexOfFirst { it.check.ordinal > hint.check.ordinal }
        if (at < 0) list += hint else list.add(at, hint)
    }

    companion object {
        const val MIN_LANDMARKS = 468
        const val FACE_FOUND_FRAMES = 3
        /** Silhouette landmarks may sit up to this many oval radii out (v1.2.2). */
        const val IN_FRAME_LIMIT = 1.15f
        /** A well-placed face centre sits this many radii below the oval centre (v1.2.1). */
        const val EXPECTED_FACE_DROP = 0.25f
        /** Within this share of rx / ry of the expected centre, a failing face is too big, not off-centre. */
        const val CENTRED_TOLERANCE = 0.08f
        /** The oval is this share of the screen width on a phone: eye ratio is screen-width relative. */
        const val OVAL_WIDTH_OF_SCREEN = 0.72f
        const val DISTANCE_MIN = 0.17f
        const val DISTANCE_MAX = 0.32f
        /** How long the change watch is held off after a fill-light change (exposure settles). */
        const val LIGHT_SETTLE_MS = 500L
        /** Extra room each side of the distance band once the scan runs (hysteresis). */
        const val DISTANCE_HOLD_MARGIN = 0.02f
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

        /** A dark frame with no face for this long (the hint delay) turns the fill light on. */
        const val DARK_NO_FACE_AFTER_MS = 400L

        /** Share of the frame left out on each side when measuring [GateFrame.frameLuma]. */
        const val FRAME_LUMA_INSET = 0.2f
        private const val FRAME_LUMA_GRID = 24

        /** Mean luma (BT.601) of the middle 60% of an ARGB frame, on a 24 x 24 grid. */
        fun centreLuma(argb: IntArray, width: Int, height: Int): Double? {
            if (width <= 0 || height <= 0 || argb.size < width * height) return null
            val x0 = width * FRAME_LUMA_INSET
            val y0 = height * FRAME_LUMA_INSET
            val sx = width * (1 - 2 * FRAME_LUMA_INSET) / FRAME_LUMA_GRID
            val sy = height * (1 - 2 * FRAME_LUMA_INSET) / FRAME_LUMA_GRID
            var sum = 0L
            for (gy in 0 until FRAME_LUMA_GRID) {
                val y = (y0 + (gy + 0.5f) * sy).toInt().coerceIn(0, height - 1)
                for (gx in 0 until FRAME_LUMA_GRID) {
                    val x = (x0 + (gx + 0.5f) * sx).toInt().coerceIn(0, width - 1)
                    val c = argb[y * width + x]
                    sum += (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
                }
            }
            return sum.toDouble() / (FRAME_LUMA_GRID * FRAME_LUMA_GRID)
        }

        const val LEFT_IRIS = 468
        const val RIGHT_IRIS = 473

        /**
         * Laplacian variance floor on the 96x96 face crop, below which a face is
         * plainly soft. Same value on iOS and Web; to tune from session data.
         */
        const val MIN_SHARPNESS = 12.0

        /** MediaPipe FACE_OVAL: the face silhouette ring. */
        val FACE_OVAL = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
        )

        /** Rigid anchors (as iOS and Web): nose tip, outer eye corners, chin, forehead. */
        val STABLE_POINTS = intArrayOf(1, 33, 263, 152, 10)
    }
}
