package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.api.models.HeadTurnStep
import com.usesense.sdk.capture.scan.OvalSpec
import com.usesense.sdk.capture.scan.PointMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** The movements the scan screen can run as an extra check. */
internal enum class ExtraCheckKind {
    HEAD_TURN,
    FOLLOW_DOT,
    SPEAK_DIGITS,
    ;

    companion object {
        fun of(type: String?): ExtraCheckKind? =
            when (type) {
                ChallengeSpec.TYPE_HEAD_TURN -> HEAD_TURN
                ChallengeSpec.TYPE_FOLLOW_DOT -> FOLLOW_DOT
                ChallengeSpec.TYPE_SPEAK_PHRASE -> SPEAK_DIGITS
                else -> null
            }
    }
}

/**
 * When the extra check was asked for: known before the camera opened (the
 * session's policy has a challenge, so it follows straight on from the scan),
 * or after the scan (the server's step-up round 2, behind the "One more quick
 * check" sheet).
 */
internal enum class ExtraCheckTiming { BEFORE_SCAN, AFTER_SCAN }

/** One timed step: a head direction, a dot position (0..1 of the screen) or the whole digits read. */
internal data class ExtraCheckStep(
    val durationMs: Long,
    val direction: String? = null,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
)

/** What the screen measured on the latest gate frame. */
internal data class ExtraCheckInput(
    /** A face is found and sits in the oval. */
    val faceInOval: Boolean,
    /**
     * Head turn as the person sees it in the mirrored preview: the nose's
     * offset from the cheek midpoint in half cheek-widths. Negative = turned
     * to their left (the nose moves to the screen's left), positive = right.
     */
    val turn: Float? = null,
    /** Microphone level 0..1, Speak Digits only. */
    val micLevel: Float? = null,
)

internal sealed class ExtraCheckEvent {
    /** A step starts: show its direction / move the dot. [retry]: the one local retry of a missed turn. */
    data class Step(val index: Int, val retry: Boolean) : ExtraCheckEvent()

    /** The face left the oval: the timer stops, the dot waits, the ring turns amber. */
    object Paused : ExtraCheckEvent()

    object Resumed : ExtraCheckEvent()

    /** Speak Digits: the meter stayed flat for [ExtraCheckRunner.QUIET_MS]. */
    object Quiet : ExtraCheckEvent()

    object Heard : ExtraCheckEvent()

    object Completed : ExtraCheckEvent()

    override fun toString(): String = javaClass.simpleName
}

/**
 * The extra check's clock, pure Kotlin so every path is unit tested.
 *
 * Agreed behaviour (storyboard flows 4 and 5):
 *  - the face leaves the oval: pause (time stops, the dot waits), say what to
 *    fix, carry on when it is back. Never fails on the device;
 *  - a missed Head Turn (left or right not turned far enough) gets ONE local
 *    retry with a hint, then the result is left to the server;
 *  - Speak Digits: a flat meter for 2 s says "We can't hear you"; that holds
 *    the timer for at most [MAX_QUIET_HOLD_MS] in all, so a broken microphone
 *    can't hold the screen forever.
 *
 * The presenter only records which step each frame belongs to; this decides
 * when steps start and end.
 */
internal class ExtraCheckRunner(
    val kind: ExtraCheckKind,
    val steps: List<ExtraCheckStep>,
) {
    var stepIndex = 0
        private set

    /** The current step is the one retry. */
    var retrying = false
        private set
    var retryUsed = false
        private set
    var paused = false
        private set
    var quiet = false
        private set
    var completed = false
        private set
    var started = false
        private set

    private var stepElapsed = 0L
    private var activeMs = 0L
    private var plannedMs = steps.sumOf { it.durationMs }.coerceAtLeast(1L)
    private var lastTick = 0L
    private var badSince = -1L
    private var goodSince = -1L
    private var silentSince = 0L
    private var quietHeld = 0L

    /** Best turn toward the asked side during the current step, in half cheek-widths. */
    var peakTurn = 0f
        private set

    val currentStep: ExtraCheckStep? get() = steps.getOrNull(stepIndex)

    /** Ring fill, 0..1: active time over planned time (a retry adds its step). */
    val progress: Float get() = if (completed) 1f else (activeMs.toFloat() / plannedMs).coerceIn(0f, 1f)

    fun start(now: Long): List<ExtraCheckEvent> {
        if (started) return emptyList()
        started = true
        lastTick = now
        silentSince = now
        if (steps.isEmpty()) {
            completed = true
            return listOf(ExtraCheckEvent.Completed)
        }
        return listOf(ExtraCheckEvent.Step(0, retry = false))
    }

    /** Time stands still (a dialog is open): the next tick starts from [now]. */
    fun hold(now: Long) {
        lastTick = now
        if (badSince >= 0) badSince = now
        if (goodSince >= 0) goodSince = now
        silentSince = now
    }

    fun tick(
        now: Long,
        input: ExtraCheckInput,
    ): List<ExtraCheckEvent> {
        if (!started || completed) return emptyList()
        val events = mutableListOf<ExtraCheckEvent>()
        val dt = (now - lastTick).coerceIn(0L, MAX_TICK_MS)
        lastTick = now

        // Face in the oval, with a little hysteresis so one bad frame doesn't flash amber.
        if (!input.faceInOval) {
            goodSince = -1L
            if (badSince < 0) badSince = now
            if (!paused && now - badSince >= LOSE_MS) {
                paused = true
                events += ExtraCheckEvent.Paused
            }
        } else {
            badSince = -1L
            if (goodSince < 0) goodSince = now
            if (paused && now - goodSince >= REGAIN_MS) {
                paused = false
                // Silence while the face was away doesn't count.
                silentSince = now
                events += ExtraCheckEvent.Resumed
            }
        }

        var holding = paused
        if (kind == ExtraCheckKind.SPEAK_DIGITS && !paused) {
            val level = input.micLevel ?: 0f
            if (level >= HEARD_LEVEL) {
                silentSince = now
                if (quiet) {
                    quiet = false
                    events += ExtraCheckEvent.Heard
                }
            } else if (!quiet && now - silentSince >= QUIET_MS) {
                quiet = true
                events += ExtraCheckEvent.Quiet
            }
            if (quiet && quietHeld < MAX_QUIET_HOLD_MS) {
                quietHeld += dt
                holding = true
            }
        }

        if (kind == ExtraCheckKind.HEAD_TURN && !paused) {
            val t = input.turn
            if (t != null) {
                when (currentStep?.direction) {
                    HeadTurnStep.LEFT -> peakTurn = max(peakTurn, -t)
                    HeadTurnStep.RIGHT -> peakTurn = max(peakTurn, t)
                }
            }
        }

        if (!holding) {
            stepElapsed += dt
            activeMs += dt
        }

        val step = currentStep ?: return events
        if (stepElapsed >= step.durationMs) {
            if (needsRetry(step)) {
                retryUsed = true
                retrying = true
                plannedMs += step.durationMs
                stepElapsed = 0L
                peakTurn = 0f
                events += ExtraCheckEvent.Step(stepIndex, retry = true)
            } else {
                stepIndex++
                retrying = false
                stepElapsed = 0L
                peakTurn = 0f
                if (stepIndex >= steps.size) {
                    completed = true
                    paused = false
                    quiet = false
                    events += ExtraCheckEvent.Completed
                } else {
                    events += ExtraCheckEvent.Step(stepIndex, retry = false)
                }
            }
        }
        return events
    }

    /** Only a left/right turn is checked on the device, and only once per extra check. */
    private fun needsRetry(step: ExtraCheckStep): Boolean =
        kind == ExtraCheckKind.HEAD_TURN && !retryUsed &&
            (step.direction == HeadTurnStep.LEFT || step.direction == HeadTurnStep.RIGHT) &&
            peakTurn < TURN_ENOUGH

    companion object {
        /** The face must be out of the oval this long before the check pauses. */
        const val LOSE_MS = 300L

        /** ...and back this long before it carries on. */
        const val REGAIN_MS = 300L

        /** Speak Digits: flat meter for this long says "We can't hear you". */
        const val QUIET_MS = 2_000L
        const val HEARD_LEVEL = 0.15f

        /** Longest the quiet state holds the timer, in all. */
        const val MAX_QUIET_HOLD_MS = 6_000L

        /** About 15 degrees of yaw: the nose a fifth of a half cheek-width off the midline. */
        const val TURN_ENOUGH = 0.22f

        /** A stalled tick (app in the background) doesn't jump the timer. */
        const val MAX_TICK_MS = 250L

        fun stepsFor(spec: ChallengeSpec): List<ExtraCheckStep> =
            when (ExtraCheckKind.of(spec.type)) {
                ExtraCheckKind.HEAD_TURN ->
                    spec.sequence.orEmpty().sortedBy { it.index }
                        .map { ExtraCheckStep(it.durationMs.toLong(), direction = it.direction) }
                ExtraCheckKind.FOLLOW_DOT ->
                    spec.waypoints.orEmpty().sortedBy { it.index }
                        .map { ExtraCheckStep(it.durationMs.toLong(), x = it.x, y = it.y) }
                ExtraCheckKind.SPEAK_DIGITS -> listOf(ExtraCheckStep(spec.totalDurationMs.toLong().coerceAtLeast(MIN_DIGITS_MS)))
                null -> emptyList()
            }.ifEmpty {
                // A spec with no steps still runs for its length, so the capture always finishes.
                listOf(ExtraCheckStep(spec.totalDurationMs.toLong().coerceAtLeast(1_000L)))
            }

        private const val MIN_DIGITS_MS = 3_000L
    }
}

/** Face position and head turn for the extra check, from one gate frame's landmarks. */
internal object ExtraCheckFace {
    private const val NOSE_TIP = 1
    private const val FOREHEAD = 10
    private const val CHIN = 152
    private const val CHEEK_A = 234
    private const val CHEEK_B = 454

    /**
     * The face centre (mean of 10, 152, 234, 454, the gate's face centre) must
     * sit within this share of the oval's radii. Looser than the scan's gate:
     * turning or following the dot moves the face, and that is the point.
     */
    const val IN_OVAL_RADIUS = 0.8f

    /** [points] are x, y, z triples in image pixels; [mapper] maps them as the preview shows them. */
    fun read(
        points: FloatArray?,
        faceCount: Int,
        mapper: PointMapper,
        oval: OvalSpec,
    ): Pair<Boolean, Float?> {
        val n = (points?.size ?: 0) / 3
        if (points == null || faceCount < 1 || n <= CHEEK_B) return false to null
        val p = FloatArray(2)

        fun at(i: Int): Pair<Float, Float> {
            mapper.map(points[i * 3], points[i * 3 + 1], p)
            return p[0] to p[1]
        }
        val f = at(FOREHEAD)
        val c = at(CHIN)
        val a = at(CHEEK_A)
        val b = at(CHEEK_B)
        val nose = at(NOSE_TIP)
        val cx = (f.first + c.first + a.first + b.first) / 4f
        val cy = (f.second + c.second + a.second + b.second) / 4f
        val dx = (cx - oval.cx) / oval.rx
        val dy = (cy - oval.cy) / oval.ry
        val inOval = sqrt(dx * dx + dy * dy) <= IN_OVAL_RADIUS
        val half = abs(b.first - a.first) / 2f
        val turn = if (half > 1f) (nose.first - (a.first + b.first) / 2f) / half else null
        return inOval to turn
    }
}
