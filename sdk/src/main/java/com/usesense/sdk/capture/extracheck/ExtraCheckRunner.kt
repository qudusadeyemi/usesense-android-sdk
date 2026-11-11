package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.api.models.ChallengeSpec
import com.usesense.sdk.api.models.HeadTurnStep
import com.usesense.sdk.capture.scan.OvalSpec
import com.usesense.sdk.capture.scan.PointMapper
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
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
    /**
     * The quality gate passes, as in the scan (face found, one person,
     * uncovered, distance, in frame, sharp, eyes open; pose relaxed toward
     * the movement asked for). False pauses the movement.
     */
    val gatePasses: Boolean,
    /**
     * Head turn as the person sees it in the mirrored preview: the nose's
     * offset from the cheek midpoint in half cheek-widths. Negative = turned
     * to their left (the nose moves to the screen's left), positive = right.
     */
    val turn: Float? = null,
    /** Microphone level 0..1, Speak Digits only. */
    val micLevel: Float? = null,
    /**
     * Head pose in degrees from the face transform ([com.usesense.sdk.capture.scan.HeadPoseMath]),
     * when known. With a pose, Head Turn and Follow Dot are closed loop (a step
     * ends once the head is on target and held there); without one, each step
     * runs for the server's time.
     */
    val yaw: Double? = null,
    val pitch: Double? = null,
    /**
     * Every failing gate check is one the turn itself causes (the face off
     * centre, the pose past the scan's limit, the face looking nearer as it
     * tilts). With the head on target this doesn't pause the check: holding
     * a big turn is the point (A03 run, 2026-11-11: right turns of 23 to 34
     * degrees failed CENTRE and reset every hold).
     */
    val turnOnlyFailing: Boolean = false,
)

internal sealed class ExtraCheckEvent {
    /** A step starts: show its direction / move the dot. [retry]: the one hint of the check (the same step again). */
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
 * How one Head Turn / Follow Dot step went, for the server's timing checks
 * (`challenge_response.step_events`, same meaning as iOS and Web). A live
 * person moves after the prompt; a replayed or looping video doesn't line up
 * with prompts it can't see. Times are on the runner's clock (ms).
 */
internal data class ExtraCheckStepLog(
    /** The step's index (the key of `step_frames` / `waypoint_frames`). */
    val index: Int,
    val promptedAt: Long,
    val endedAt: Long,
    /** Start of the on-target hold that ended the step (reached steps only). */
    val reachedAt: Long?,
    /** Ended on target (true), timed out after the hint (false), or by the server's time with no pose (null). */
    val reached: Boolean?,
    val hinted: Boolean,
    /**
     * step_events v2: the movement along the step's main axis, toward the
     * target (+) or away (-), at the first pose more than
     * [ExtraCheckRunner.FIRST_MOVE_DEG] from the pose at the prompt; and when
     * (ms after the prompt). Null: never moved that far, or nothing to move
     * toward (Follow Dot's first waypoint).
     */
    val firstMoveDeg: Double? = null,
    val firstMoveMs: Long? = null,
    /** The furthest away from the target along the main axis before reaching it (or the end), >= 0. */
    val maxAwayDeg: Double? = null,
)

/**
 * The extra check's clock, pure Kotlin so every path is unit tested.
 *
 * Agreed behaviour (storyboard flows 4 and 5, iOS `ChallengeRunner` parity):
 *  - the face leaves the oval: pause (time stops, the dot waits), say what to
 *    fix, carry on when it is back. Never fails on the device;
 *  - Head Turn and Follow Dot are closed loop: a step ends once the head has
 *    reached the step's target and held it for [HOLD_MS]. Not there after
 *    [HINT_AFTER_MS] (or the server's step, if longer): ONE hint per check,
 *    with the same time again; still not there: on to the next step, the
 *    result left to the server. A Follow Dot waypoint can't be reached before
 *    the dot's glide ends ([DOT_GLIDE_MS]). No pose from the tracker: the
 *    server's step time (and the old nose-offset check for the one retry);
 *  - Speak Digits: a flat meter for 2 s says "We can't hear you"; that holds
 *    the timer for at most [MAX_QUIET_HOLD_MS] in all, so a broken microphone
 *    can't hold the screen forever.
 *
 * The presenter only records which step each frame belongs to (and, via
 * [banksNow], whether a frame is evidence for it); this decides when steps
 * start and end, and logs each step's timing ([stepLog]).
 */
internal class ExtraCheckRunner(
    val kind: ExtraCheckKind,
    val steps: List<ExtraCheckStep>,
) {
    var stepIndex = 0
        private set

    /** The current step is the one retry (the hint shows). */
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

    /** Best turn toward the asked side during the current step, in half cheek-widths (no-pose fallback). */
    var peakTurn = 0f
        private set

    // Closed loop (see the class comment).
    /** A pose was seen during the check: the ring fills per step done. */
    private var posed = false
    private var stepPoseSeen = false
    private var atTargetMs = 0L
    private var lastAtTarget: Boolean? = null
    private var reachedSteps = 0

    /** Time the current step has run (the glide; not reset by the hint). */
    private var stepAge = 0L

    /** Follow Dot: the pose held at the previous waypoint (the next move is judged from it). */
    private var refYaw = 0.0
    private var refPitch = 0.0
    private var tickYaw: Double? = null
    private var tickPitch: Double? = null

    // Step timing.
    private val log = mutableListOf<ExtraCheckStepLog>()
    private var promptedAt = -1L
    private var reachedAt = -1L
    private var stepHinted = false

    // First move, from the pose on every processed frame ([observePose]).
    private var poseYaw: Double? = null
    private var posePitch: Double? = null
    private var promptYaw: Double? = null
    private var promptPitch: Double? = null
    private var firstMoveDeg: Double? = null
    private var firstMoveAt = -1L
    private var maxAwayDeg: Double? = null

    /** The head reached the target: what it does after that isn't the way there. */
    private var moveSettled = false

    val currentStep: ExtraCheckStep? get() = steps.getOrNull(stepIndex)

    /** Every finished Head Turn / Follow Dot step, in order. */
    val stepLog: List<ExtraCheckStepLog> get() = log.toList()

    /**
     * Ring fill, 0..1. With a pose: one segment per step done (a step that
     * timed out leaves its segment empty, so moving on never looks like a
     * confirmation) plus the hold on the current one. Without: active time
     * over planned time (a retry adds its step).
     */
    val progress: Float
        get() {
            if (completed) return 1f
            if (posed && kind != ExtraCheckKind.SPEAK_DIGITS && steps.isNotEmpty()) {
                val hold = min(1f, atTargetMs.toFloat() / HOLD_MS)
                return ((reachedSteps + hold) / steps.size).coerceIn(0f, 1f)
            }
            return (activeMs.toFloat() / plannedMs).coerceIn(0f, 1f)
        }

    /**
     * Frames taken now are evidence for the current step. Head Turn: only
     * while the head is on target (frames from the way there, still turned
     * from the last step, fail steps the person did). Follow Dot: on target,
     * or once the dot has arrived and settled (the server judges each
     * waypoint from a few frames even when the phone thinks it was missed).
     * No pose to judge, or Speak Digits: always.
     */
    val banksNow: Boolean
        get() =
            when {
                completed || kind == ExtraCheckKind.SPEAK_DIGITS -> true
                kind == ExtraCheckKind.HEAD_TURN -> lastAtTarget ?: true
                else -> (lastAtTarget ?: true) || stepAge >= DOT_GLIDE_MS + DOT_SETTLE_MS
            }

    fun start(now: Long): List<ExtraCheckEvent> {
        if (started) return emptyList()
        started = true
        lastTick = now
        silentSince = now
        // The first step shows now; the check starts with the face in the oval (straight from the scan or its sheet).
        promptedAt = now
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

    /**
     * The tracker's pose on one processed frame (every frame, not only those
     * banked), for the step's first move. [now] on the same clock as [tick].
     */
    fun observePose(
        now: Long,
        yaw: Double,
        pitch: Double,
    ) {
        poseYaw = yaw
        posePitch = pitch
        if (!started || completed || promptedAt < 0 || moveSettled) return
        if (promptYaw == null) {
            // No pose at the prompt: the first pose after it stands in.
            promptYaw = yaw
            promptPitch = pitch
        }
        val py = promptYaw ?: return
        val pp = promptPitch ?: return
        val t = toward(yaw, pitch, py, pp) ?: return
        maxAwayDeg = max(maxAwayDeg ?: 0.0, if (t < 0) -t else 0.0)
        if (firstMoveAt < 0 && hypot(yaw - py, pitch - pp) > FIRST_MOVE_DEG) {
            firstMoveDeg = t
            firstMoveAt = now
        }
    }

    fun tick(
        now: Long,
        input: ExtraCheckInput,
    ): List<ExtraCheckEvent> {
        if (!started || completed) return emptyList()
        val events = mutableListOf<ExtraCheckEvent>()
        val dt = (now - lastTick).coerceIn(0L, MAX_TICK_MS)
        lastTick = now

        // A failure the turn causes is not the face leaving: with the head on target, or
        // coming back from the previous step's turn (A03 run: still at yaw 45 after "left",
        // "up" paused on CENTRE for 1.5 s).
        val gateOk =
            input.gatePasses ||
                (
                    input.turnOnlyFailing && kind != ExtraCheckKind.SPEAK_DIGITS &&
                        (
                            (stepIndex > 0 && stepAge < RETURN_GRACE_MS) ||
                                currentStep?.let { target(it, input) } == true
                        )
                )

        // Face in the oval, with a little hysteresis so one bad frame doesn't flash amber.
        if (!gateOk) {
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
            stepAge += dt
        }

        val step = currentStep ?: return events
        if (kind == ExtraCheckKind.SPEAK_DIGITS) {
            if (stepElapsed >= step.durationMs) advance(now, events, reached = null)
            return events
        }
        if (paused) return events
        if (input.yaw != null && input.pitch != null) {
            tickYaw = input.yaw
            tickPitch = input.pitch
        }
        advanceTargeted(now, step, target(step, input), dt, events)
        return events
    }

    /** Whether the pose is on the current step's target (null: no pose to judge). */
    private fun target(
        step: ExtraCheckStep,
        input: ExtraCheckInput,
    ): Boolean? {
        if (kind == ExtraCheckKind.HEAD_TURN) return atTarget(step.direction, input.yaw, input.pitch)
        // The dot must have arrived before the head can be on it.
        val arrived = stepAge >= DOT_GLIDE_MS
        val reached =
            if (stepIndex == 0) {
                // The first waypoint: a straight face (the reference).
                atTarget(HeadTurnStep.CENTER, input.yaw, input.pitch)
            } else {
                val prev = steps[stepIndex - 1]
                atDotTarget(step.x, step.y, prev.x, prev.y, input.yaw, input.pitch, refYaw, refPitch)
            }
        return reached?.let { it && arrived }
    }

    private fun advanceTargeted(
        now: Long,
        step: ExtraCheckStep,
        target: Boolean?,
        dt: Long,
        events: MutableList<ExtraCheckEvent>,
    ) {
        val server = step.durationMs.coerceAtLeast(MIN_STEP_MS)
        lastAtTarget = target
        if (target == null && !stepPoseSeen) {
            // No pose from the tracker: the server's time, as before.
            if (stepElapsed >= step.durationMs) {
                if (needsRetry(step)) {
                    retryUsed = true
                    retrying = true
                    stepHinted = true
                    plannedMs += step.durationMs
                    stepElapsed = 0L
                    peakTurn = 0f
                    events += ExtraCheckEvent.Step(stepIndex, retry = true)
                } else {
                    advance(now, events, reached = null)
                }
            }
            return
        }
        // A pose seen earlier in the step, lost now: not on target (the hint and time-out still run).
        val onTarget = target ?: false
        if (onTarget) moveSettled = true
        stepPoseSeen = true
        posed = true
        if (onTarget && atTargetMs == 0L) reachedAt = now
        atTargetMs = if (onTarget) atTargetMs + dt else 0L
        if (!onTarget) reachedAt = -1L
        if (atTargetMs >= HOLD_MS) {
            advance(now, events, reached = true)
            return
        }
        if (stepElapsed >= max(HINT_AFTER_MS, server)) {
            if (!retrying && !retryUsed) {
                // One hint per check, with the same time again.
                retryUsed = true
                retrying = true
                stepHinted = true
                stepElapsed = 0L
                events += ExtraCheckEvent.Step(stepIndex, retry = true)
            } else {
                advance(now, events, reached = false) // the server judges this step
            }
        }
    }

    /** Log the step and start the next one (or complete). */
    private fun advance(
        now: Long,
        events: MutableList<ExtraCheckEvent>,
        reached: Boolean?,
    ) {
        if (kind != ExtraCheckKind.SPEAK_DIGITS) {
            if (reached != false) reachedSteps++
            val prompted = if (promptedAt >= 0) promptedAt else now
            log +=
                ExtraCheckStepLog(
                    index = stepIndex,
                    promptedAt = prompted,
                    endedAt = now,
                    reachedAt = if (reached == true && reachedAt >= 0) reachedAt else null,
                    reached = reached,
                    hinted = stepHinted,
                    firstMoveDeg = firstMoveDeg,
                    firstMoveMs = if (firstMoveAt >= 0) firstMoveAt - prompted else null,
                    maxAwayDeg = maxAwayDeg,
                )
        }
        promptedAt = now
        // The next step's first move is measured from the pose now.
        promptYaw = poseYaw
        promptPitch = posePitch
        firstMoveDeg = null
        firstMoveAt = -1L
        maxAwayDeg = null
        moveSettled = false
        reachedAt = -1L
        stepHinted = false
        lastAtTarget = null
        if (kind == ExtraCheckKind.FOLLOW_DOT) {
            // The next move is judged from where the head is now.
            tickYaw?.let { refYaw = it }
            tickPitch?.let { refPitch = it }
        }
        stepIndex++
        retrying = false
        stepElapsed = 0L
        stepAge = 0L
        atTargetMs = 0L
        stepPoseSeen = false
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

    /** The current step's movement toward its target from the pose `from` (null: nothing to move toward). */
    private fun toward(
        yaw: Double,
        pitch: Double,
        fromYaw: Double,
        fromPitch: Double,
    ): Double? {
        val step = currentStep ?: return null
        return when (kind) {
            ExtraCheckKind.HEAD_TURN -> towardTurn(step.direction, yaw, pitch, fromYaw, fromPitch)
            ExtraCheckKind.FOLLOW_DOT -> {
                if (stepIndex == 0) return null // waypoint 0: the reference
                val prev = steps[stepIndex - 1]
                towardDot(step.x, step.y, prev.x, prev.y, yaw, pitch, fromYaw, fromPitch)
            }
            ExtraCheckKind.SPEAK_DIGITS -> null
        }
    }

    /** No pose: only a left/right turn is checked on the device (nose offset), once per extra check. */
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

        /** No pose: about 15 degrees of yaw, the nose a fifth of a half cheek-width off the midline. */
        const val TURN_ENOUGH = 0.22f

        /** A stalled tick (app in the background) doesn't jump the timer. */
        const val MAX_TICK_MS = 250L

        /**
         * Head Turn targets (degrees from the face transform), as iOS: a little
         * more than the server's 6 (yaw) / 5 (pitch) so it sees a clear turn.
         */
        const val TURN_YAW = 15.0
        const val TURN_PITCH = 12.0
        const val CENTRE_TOLERANCE = 10.0

        /** On target this long ends the step. */
        const val HOLD_MS = 600L

        /** Not there after this long (or the server's step): the hint; the same again: move on. */
        const val HINT_AFTER_MS = 4_000L

        /** Follow Dot: the dot's glide to its next waypoint (ExtraCheckOverlayView's DOT_MOVE_MS). */
        const val DOT_GLIDE_MS = 600L

        /** ...then this long before settled frames count (a person's reaction to the move). */
        const val DOT_SETTLE_MS = 500L

        /** Follow Dot: a move under this share of the screen on an axis asks for no movement on it (the server's 0.1). */
        const val DOT_CENTRE_BAND = 0.1f
        const val DOT_YAW = 10.0
        const val DOT_PITCH = 8.0

        /** The dot move's other direction may go this far the wrong way. */
        const val DOT_WRONG_WAY = 10.0

        /** A new step: turn-caused gate failures don't pause for this long while the head comes back. */
        const val RETURN_GRACE_MS = ExtraCheckMotion.SIDE_GRACE_MS

        /** A move from the pose at the prompt bigger than this (yaw/pitch distance) is the step's first move. */
        const val FIRST_MOVE_DEG = 4.0

        /**
         * Which way "left" and "up" read in [com.usesense.sdk.capture.scan.HeadPoseMath]'s
         * angles, measured on a Galaxy A03: turning to the person's left reads
         * yaw > 0 (extra-check run); looking up reads pitch < 0 (Head Turn run,
         * 2026-11-11: up -20 to -38, down +31). Both are the opposite of iOS's
         * face transform.
         */
        const val LEFT_YAW_SIGN = 1.0
        const val UP_PITCH_SIGN = -1.0

        private const val MIN_STEP_MS = 200L

        /** Whether the pose is at a Head Turn target (null: no pose to judge). */
        fun atTarget(
            direction: String?,
            yaw: Double?,
            pitch: Double?,
        ): Boolean? =
            when (direction) {
                HeadTurnStep.LEFT, HeadTurnStep.RIGHT -> {
                    yaw?.let {
                        val towardLeft = it * LEFT_YAW_SIGN
                        if (direction == HeadTurnStep.LEFT) towardLeft >= TURN_YAW else -towardLeft >= TURN_YAW
                    }
                }
                HeadTurnStep.UP, HeadTurnStep.DOWN -> {
                    pitch?.let {
                        val towardUp = it * UP_PITCH_SIGN
                        if (direction == HeadTurnStep.UP) towardUp >= TURN_PITCH else -towardUp >= TURN_PITCH
                    }
                }
                else -> if (yaw == null || pitch == null) null else abs(yaw) < CENTRE_TOLERANCE && abs(pitch) < CENTRE_TOLERANCE
            }

        /**
         * Follow Dot: whether the head followed the dot's move from (px, py) to
         * (x, y), measured from the pose held at the previous waypoint. Screen
         * right is the person's right in the mirrored preview. Judged on the
         * move's main direction (the larger part): the head must move that way
         * by [DOT_YAW] / [DOT_PITCH]; the other part only fails it when the head
         * goes clearly the opposite way (more than [DOT_WRONG_WAY]). Same rule
         * as iOS and Watchtower's validator.
         */
        fun atDotTarget(
            x: Float,
            y: Float,
            px: Float,
            py: Float,
            yaw: Double?,
            pitch: Double?,
            refYaw: Double,
            refPitch: Double,
        ): Boolean? {
            if (yaw == null || pitch == null) return null
            val dx = x - px
            val dy = py - y // > 0: the dot moved up
            val towardRight = (yaw - refYaw) * -LEFT_YAW_SIGN
            val towardUp = (pitch - refPitch) * UP_PITCH_SIGN
            val followX = if (dx > 0) towardRight else -towardRight
            val followY = if (dy > 0) towardUp else -towardUp
            val movesX = abs(dx) > DOT_CENTRE_BAND
            val movesY = abs(dy) > DOT_CENTRE_BAND
            if (!movesX && !movesY) return true
            if (movesX && (!movesY || abs(dx) >= abs(dy))) {
                return followX >= DOT_YAW && (!movesY || followY > -DOT_WRONG_WAY)
            }
            return followY >= DOT_PITCH && (!movesX || followX > -DOT_WRONG_WAY)
        }

        /**
         * Head Turn: how far the head moved toward the step's target from the
         * pose `from` (negative: away). Left/right on yaw, up/down on pitch,
         * centre = the distance from straight ahead shrinking.
         */
        fun towardTurn(
            direction: String?,
            yaw: Double,
            pitch: Double,
            fromYaw: Double,
            fromPitch: Double,
        ): Double =
            when (direction) {
                HeadTurnStep.LEFT -> (yaw - fromYaw) * LEFT_YAW_SIGN
                HeadTurnStep.RIGHT -> -(yaw - fromYaw) * LEFT_YAW_SIGN
                HeadTurnStep.UP -> (pitch - fromPitch) * UP_PITCH_SIGN
                HeadTurnStep.DOWN -> -(pitch - fromPitch) * UP_PITCH_SIGN
                else -> hypot(fromYaw, fromPitch) - hypot(yaw, pitch)
            }

        /**
         * Follow Dot: how far the head moved with the dot's move from the pose
         * `from`, on the axis the dot moved most along (x wins ties, as the
         * server's `judgeDotMove`), with [atDotTarget]'s mirroring and signs.
         * Negative: against the dot.
         */
        fun towardDot(
            x: Float,
            y: Float,
            px: Float,
            py: Float,
            yaw: Double,
            pitch: Double,
            fromYaw: Double,
            fromPitch: Double,
        ): Double {
            val dx = x - px
            val dy = py - y
            val towardRight = (yaw - fromYaw) * -LEFT_YAW_SIGN
            val towardUp = (pitch - fromPitch) * UP_PITCH_SIGN
            if (abs(dx) >= abs(dy)) return if (dx >= 0) towardRight else -towardRight
            return if (dy > 0) towardUp else -towardUp
        }

        /** Degrees on the wire: one decimal. */
        fun roundDeg(v: Double): Double = (v * 10).roundToLong() / 10.0

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
