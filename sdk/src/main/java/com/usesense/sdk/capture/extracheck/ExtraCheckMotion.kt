package com.usesense.sdk.capture.extracheck

import com.usesense.sdk.api.models.HeadTurnStep
import com.usesense.sdk.capture.scan.GateMotion
import kotlin.math.abs

/**
 * Which way the quality gate lets the head go during a movement. Pose is
 * relaxed only toward the side the current step asks for (and, for a moment
 * after a change of side, the previous one, while the head comes back).
 * Speak Digits asks for no movement: the scan's rules apply unchanged.
 */
internal object ExtraCheckMotion {
    /** How long the previous step's side stays allowed after a step starts. */
    const val SIDE_GRACE_MS = 1_200L

    /** The dot within this distance of the middle needs no turn either way. */
    private const val DOT_CENTRE = 0.1f

    fun forStep(
        runner: ExtraCheckRunner,
        turn: Float?,
        previous: ExtraCheckStep?,
        msIntoStep: Long,
    ): GateMotion? {
        val step = runner.currentStep ?: return null
        val grace = previous?.takeIf { msIntoStep < SIDE_GRACE_MS }
        return when (runner.kind) {
            ExtraCheckKind.SPEAK_DIGITS -> null
            ExtraCheckKind.HEAD_TURN -> {
                fun yawOk(s: ExtraCheckStep?) =
                    when (s?.direction) {
                        HeadTurnStep.LEFT -> turn == null || turn <= 0f
                        HeadTurnStep.RIGHT -> turn == null || turn >= 0f
                        else -> false
                    }
                fun pitchOk(s: ExtraCheckStep?) = s?.direction == HeadTurnStep.UP || s?.direction == HeadTurnStep.DOWN
                GateMotion(
                    yawAllowed = yawOk(step) || yawOk(grace),
                    pitchAllowed = pitchOk(step) || pitchOk(grace),
                    moving = true,
                )
            }
            ExtraCheckKind.FOLLOW_DOT -> {
                // The mirrored preview: a dot on the screen's right asks the head to the right (turn > 0).
                fun yawOk(s: ExtraCheckStep?): Boolean {
                    if (s == null) return false
                    val side = s.x - 0.5f
                    if (abs(side) < DOT_CENTRE || turn == null) return true
                    return (side > 0f) == (turn >= 0f)
                }
                GateMotion(yawAllowed = yawOk(step) || yawOk(grace), pitchAllowed = true, moving = true)
            }
        }
    }
}
