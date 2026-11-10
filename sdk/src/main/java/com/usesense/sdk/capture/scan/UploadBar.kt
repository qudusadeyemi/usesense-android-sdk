package com.usesense.sdk.capture.scan

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/**
 * The upload sheet's progress bar, as iOS (UploadBar, PR #99). Display only:
 * it follows time, not bytes, so it keeps moving while the server checks
 * (6.7 s on an iPhone) instead of sitting at 95% and reading as stuck.
 *
 *  - sending: from [START] toward [SEND_CAP] with time constant [SEND_TAU_MS];
 *  - checking (upload accepted, or the result in flight): the same curve from
 *    wherever the bar is, toward [CHECK_CAP] with [CHECK_TAU_MS]. It never
 *    reaches the cap;
 *  - done: 1.
 *
 * Stages only move forward and the value never goes back. [reset] on a return
 * to align.
 */
internal class UploadBar {
    enum class Stage { IDLE, SENDING, CHECKING, DONE }

    var stage = Stage.IDLE
        private set
    private var stageStartMs = 0L
    private var from = 0f
    private var shown = 0f

    fun reset() {
        stage = Stage.IDLE
        from = 0f
        shown = 0f
    }

    /** The upload began. */
    fun sending(nowMs: Long) {
        if (stage >= Stage.SENDING) return
        stage = Stage.SENDING
        stageStartMs = nowMs
        from = START
    }

    /** The upload was accepted, or the result is in flight. */
    fun checking(nowMs: Long) {
        if (stage >= Stage.CHECKING) return
        if (stage == Stage.IDLE) sending(nowMs)
        from = value(nowMs)
        stage = Stage.CHECKING
        stageStartMs = nowMs
    }

    fun done() {
        stage = Stage.DONE
    }

    /** The bar at [nowMs], 0..1. Never lower than the last value returned. */
    fun value(nowMs: Long): Float {
        val t = (nowMs - stageStartMs).coerceAtLeast(0L).toFloat()
        val v = when (stage) {
            Stage.IDLE -> 0f
            Stage.SENDING -> approach(from, SEND_CAP, t, SEND_TAU_MS)
            Stage.CHECKING -> approach(from, CHECK_CAP, t, CHECK_TAU_MS)
            Stage.DONE -> 1f
        }
        shown = max(shown, v)
        return shown
    }

    companion object {
        const val START = 0.04f
        const val SEND_CAP = 0.60f
        const val SEND_TAU_MS = 1_500f
        const val CHECK_CAP = 0.95f
        const val CHECK_TAU_MS = 5_000f
        private const val CAP_MARGIN = 0.001f

        private fun approach(from: Float, cap: Float, tMs: Float, tauMs: Float): Float {
            if (from >= cap) return from
            // Float rounding would reach the cap after a long wait: stay under it.
            return minOf(cap - (cap - from) * exp(-tMs / tauMs), cap - CAP_MARGIN)
        }

        /** The value spoken by TalkBack: floored to 10% steps. */
        fun accessiblePercent(value: Float): Int = (floor(value.coerceIn(0f, 1f) * 10f) * 10f).toInt()
    }
}
