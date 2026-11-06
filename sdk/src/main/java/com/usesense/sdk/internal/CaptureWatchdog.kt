package com.usesense.sdk.internal

import com.usesense.sdk.UseSenseError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The system-paced stretches of a capture the watchdog supervises. */
internal enum class WatchdogPhase(val wire: String) {
    /** Camera requested, not yet bound. */
    CAMERA("camera"),

    /** Frames being captured / challenge running. */
    CAPTURE("capture"),

    /** Capture finished; integrity, face mesh and the signals package being built. */
    PREPARING("preparing"),

    /** Signals request in flight. */
    UPLOADING("uploading"),

    /** Waiting for the verdict. */
    COMPLETING("completing"),
    ;

    val isCapture: Boolean get() = this == CAMERA || this == CAPTURE
}

/**
 * Last line of defence against a verification that hangs silently.
 *
 * Every individual await in the pipeline is bounded on its own; this is the
 * backstop for anything that is not (a native call that never returns, a
 * callback that never fires, a challenge that never completes). It is armed
 * when the system, not the subject, is driving the capture -- the camera
 * opening and everything after the capture starts -- and it is not armed on
 * screens that wait for the subject to tap something.
 *
 * Rules:
 *  - [budgetMs] (default 120s) is the total from [arm] to [disarm].
 *  - [CAMERA] additionally has its own shorter cap, [cameraBudgetMs].
 *  - Upload progress extends the deadline to at least `now + progressGraceMs`
 *    so a slow but moving upload is never cut off; a stalled one is.
 *
 * When it fires, [onTimeout] runs exactly once on [scope] with a stable
 * CAPTURE_TIMEOUT or UPLOAD_TIMEOUT error. The owner must not wait on the
 * stuck work: cancel it and move the UI to a terminal state immediately.
 */
internal class CaptureWatchdog(
    private val scope: CoroutineScope,
    private val budgetMs: Long = TOTAL_BUDGET_MS,
    private val cameraBudgetMs: Long = CAMERA_BUDGET_MS,
    private val progressGraceMs: Long = PROGRESS_GRACE_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val onTimeout: (WatchdogPhase, UseSenseError) -> Unit,
) {
    private var job: Job? = null
    private var armedAt = 0L
    private var deadline = 0L
    private var phaseDeadline = Long.MAX_VALUE

    @Volatile var phase: WatchdogPhase? = null
        private set

    @Volatile var fired: Boolean = false
        private set

    val isArmed: Boolean get() = job?.isActive == true

    /** Start (or restart) supervision from [phase]. Resets the total budget. */
    @Synchronized
    fun arm(phase: WatchdogPhase) {
        job?.cancel()
        fired = false
        armedAt = nowMs()
        deadline = armedAt + budgetMs
        enterLocked(phase)
        job = scope.launch {
            while (isActive) {
                val now = nowMs()
                val due = synchronized(this@CaptureWatchdog) { minOf(deadline, phaseDeadline) }
                if (now >= due) {
                    fire()
                    return@launch
                }
                // Re-check at least every TICK_MS so a deadline moved by
                // enter()/onProgress() is honoured without restarting the loop.
                delay(minOf(due - now, TICK_MS))
            }
        }
    }

    /** Move to [phase] without resetting the total budget. Arms if not armed. */
    @Synchronized
    fun enter(phase: WatchdogPhase) {
        if (!isArmed) {
            arm(phase)
            return
        }
        enterLocked(phase)
    }

    /** Bytes are moving: never fire while progress keeps arriving. */
    @Synchronized
    fun onProgress() {
        if (!isArmed) return
        deadline = maxOf(deadline, nowMs() + progressGraceMs)
    }

    /** Stop supervising: a terminal state, or a screen waiting on the subject. */
    @Synchronized
    fun disarm() {
        job?.cancel()
        job = null
        phase = null
    }

    private fun enterLocked(phase: WatchdogPhase) {
        this.phase = phase
        phaseDeadline = if (phase == WatchdogPhase.CAMERA) nowMs() + cameraBudgetMs else Long.MAX_VALUE
    }

    private fun fire() {
        val current: WatchdogPhase
        val elapsed: Long
        synchronized(this) {
            if (fired) return
            fired = true
            current = phase ?: WatchdogPhase.CAPTURE
            elapsed = nowMs() - armedAt
            job = null
            phase = null
        }
        onTimeout(current, errorFor(current, elapsed))
    }

    companion object {
        /** Total ceiling on a capture from camera-open to a terminal state. */
        const val TOTAL_BUDGET_MS = 120_000L

        /** A camera that has not bound by now is not going to. */
        const val CAMERA_BUDGET_MS = 20_000L

        /** How long upload progress keeps the watchdog quiet. */
        const val PROGRESS_GRACE_MS = 30_000L

        private const val TICK_MS = 500L

        fun errorFor(phase: WatchdogPhase, elapsedMs: Long): UseSenseError =
            if (phase.isCapture) {
                UseSenseError.captureTimeout(phase.wire, elapsedMs)
            } else {
                UseSenseError.uploadTimeout(phase.wire, elapsedMs)
            }
    }
}
