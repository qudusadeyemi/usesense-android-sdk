package com.usesense.sdk.capture

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * LiveSense v4 zoom-motion state machine. Phase 1 ticket A-1.
 *
 * Mirror of the web-sdk zoom-motion.ts state machine: consumes
 * per-frame (bbox, head-pose) observations and decides whether the
 * user is performing the 30cm to 18cm zoom motion. No CameraX or
 * ML Kit coupling; this is pure state machinery driven by the
 * LiveSenseV4Activity integration layer.
 */
enum class ZoomState { IDLE, WATCHING, MOVING, COMPLETE, FAILED }

enum class ZoomFailureReason(val wire: String) {
    TIMEOUT("timeout"),
    NO_MOTION("no_motion"),
    HEAD_TURN("head_turn"),
    FACE_LOST("face_lost")
}

data class HeadPoseDegrees(val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float)

data class FaceBoundingBox(val cx: Float, val cy: Float, val w: Float, val h: Float) {
    constructor(rect: RectF) : this(rect.centerX(), rect.centerY(), rect.width(), rect.height())
}

data class ZoomObservation(
    val timestampMs: Long,
    val bbox: FaceBoundingBox,
    val headPose: HeadPoseDegrees
)

data class ZoomMotionStats(
    val startBbox: FaceBoundingBox?,
    val endBbox: FaceBoundingBox?,
    val scaleRatio: Float,
    val durationMs: Long,
    val maxHeadYawAbsDeg: Float,
    val maxHeadPitchAbsDeg: Float,
    val observationCount: Int
)

data class ZoomMotionConfig(
    val timeoutMs: Long = 3000,
    val windowMs: Long = 200,
    val minGrowthInWindow: Float = 0.05f,
    val completionMinScaleRatio: Float = 1.7f,
    val completionMaxScaleRatio: Float = 2.4f,
    val completionDwellMs: Long = 200,
    val maxHeadPoseDeg: Float = 15f,
    val noMotionGraceMs: Long = 1500
)

fun interface ZoomTransitionListener {
    fun onTransition(
        next: ZoomState,
        prev: ZoomState,
        stats: ZoomMotionStats,
        failure: ZoomFailureReason?
    )
}

class ZoomMotionController(private val cfg: ZoomMotionConfig = ZoomMotionConfig()) {
    @Volatile private var currentState: ZoomState = ZoomState.IDLE
    private var startTs: Long? = null
    private var startBbox: FaceBoundingBox? = null
    private var latestBbox: FaceBoundingBox? = null
    private var completionDwellStartedAt: Long? = null
    private var lastMotionTs: Long? = null
    private var maxYaw: Float = 0f
    private var maxPitch: Float = 0f
    private var observationCount: Int = 0
    private val window: ArrayDeque<Pair<Long, Float>> = ArrayDeque()
    private val listeners = mutableListOf<ZoomTransitionListener>()

    fun state(): ZoomState = currentState

    fun start() {
        if (currentState != ZoomState.IDLE &&
            currentState != ZoomState.COMPLETE &&
            currentState != ZoomState.FAILED) return
        reset()
        transition(ZoomState.WATCHING, null)
    }

    fun stop() {
        if (currentState == ZoomState.COMPLETE || currentState == ZoomState.FAILED) return
        transition(ZoomState.FAILED, ZoomFailureReason.TIMEOUT)
    }

    /** Feed one observation. Returns true when state transitions. */
    fun observe(obs: ZoomObservation): Boolean {
        if (currentState != ZoomState.WATCHING && currentState != ZoomState.MOVING) return false
        val prev = currentState

        if (startTs == null) {
            startTs = obs.timestampMs
            startBbox = obs.bbox
            latestBbox = obs.bbox
            observationCount = 0
            window.clear()
        }

        val lastTs = window.lastOrNull()?.first ?: -1L
        if (obs.timestampMs < lastTs) return false

        observationCount += 1
        latestBbox = obs.bbox
        maxYaw = max(maxYaw, abs(obs.headPose.yawDeg))
        maxPitch = max(maxPitch, abs(obs.headPose.pitchDeg))

        val elapsed = obs.timestampMs - (startTs ?: obs.timestampMs)

        if (abs(obs.headPose.yawDeg) > cfg.maxHeadPoseDeg ||
            abs(obs.headPose.pitchDeg) > cfg.maxHeadPoseDeg) {
            transition(ZoomState.FAILED, ZoomFailureReason.HEAD_TURN)
            return true
        }
        if (elapsed > cfg.timeoutMs) {
            transition(ZoomState.FAILED, ZoomFailureReason.TIMEOUT)
            return true
        }

        val area = obs.bbox.w * obs.bbox.h
        window.addLast(obs.timestampMs to area)
        val lower = obs.timestampMs - cfg.windowMs
        while (window.size > 1 && (window.first().first < lower)) window.removeFirst()

        val startArea = max(1e-6f, (startBbox?.w ?: 0f) * (startBbox?.h ?: 0f))
        val windowStartArea = window.first().second
        val growthInWindow = (area - windowStartArea) / startArea

        if (growthInWindow >= cfg.minGrowthInWindow) {
            lastMotionTs = obs.timestampMs
            if (currentState == ZoomState.WATCHING) transition(ZoomState.MOVING, null)
        }

        val scaleRatio = area / startArea
        if (scaleRatio in cfg.completionMinScaleRatio..cfg.completionMaxScaleRatio) {
            if (completionDwellStartedAt == null) {
                completionDwellStartedAt = obs.timestampMs
            } else if (obs.timestampMs - completionDwellStartedAt!! >= cfg.completionDwellMs) {
                transition(ZoomState.COMPLETE, null)
                return true
            }
        } else {
            completionDwellStartedAt = null
        }

        if (lastMotionTs == null && elapsed >= cfg.noMotionGraceMs) {
            transition(ZoomState.FAILED, ZoomFailureReason.NO_MOTION)
            return true
        }

        return currentState != prev
    }

    fun stats(): ZoomMotionStats {
        val startArea = (startBbox?.w ?: 0f) * (startBbox?.h ?: 0f)
        val endArea = (latestBbox?.w ?: 0f) * (latestBbox?.h ?: 0f)
        val duration = if (startTs != null && window.isNotEmpty()) {
            window.last().first - startTs!!
        } else 0L
        return ZoomMotionStats(
            startBbox = startBbox,
            endBbox = latestBbox,
            scaleRatio = if (startArea > 0f) endArea / startArea else 0f,
            durationMs = duration,
            maxHeadYawAbsDeg = maxYaw,
            maxHeadPitchAbsDeg = maxPitch,
            observationCount = observationCount
        )
    }

    fun addListener(listener: ZoomTransitionListener) { listeners.add(listener) }
    fun removeListener(listener: ZoomTransitionListener) { listeners.remove(listener) }

    private fun reset() {
        startTs = null
        startBbox = null
        latestBbox = null
        completionDwellStartedAt = null
        lastMotionTs = null
        maxYaw = 0f
        maxPitch = 0f
        observationCount = 0
        window.clear()
    }

    private fun transition(next: ZoomState, failure: ZoomFailureReason?) {
        val prev = currentState
        if (prev == next) return
        currentState = next
        val s = stats()
        for (l in listeners) {
            runCatching { l.onTransition(next, prev, s, failure) }
        }
    }
}
