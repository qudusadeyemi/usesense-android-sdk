package com.usesense.sdk.capture.scan

/**
 * The passive scan's clock: align, scan, pause, resume, drop, done.
 *
 *  - The scan banks good time while every check passes; the ring shows it.
 *  - A failing check pauses the ring. Frames aren't banked while paused, but
 *    the ones already banked are kept.
 *  - A pause longer than [maxPauseMs] drops the window: back to align, start over.
 *    After a manual (shutter) start, pauses never drop the window.
 *  - The shutter is offered after [shutterAfterMs] in align, or after
 *    [shutterAfterDrops] dropped windows.
 *
 * Pure: the caller feeds it the clock and the gate result.
 */
internal class ScanController(
    private val totalMs: Long,
    private val maxPauseMs: Long = MAX_PAUSE_MS,
    private val shutterAfterMs: Long = SHUTTER_AFTER_MS,
    private val shutterAfterDrops: Int = SHUTTER_AFTER_DROPS,
) {
    enum class State { ALIGNING, SCANNING, PAUSED, DONE }

    enum class Event { PAUSED, RESUMED, DROPPED, COMPLETED }

    var state: State = State.ALIGNING
        private set
    var bankedMs: Long = 0L
        private set
    var droppedWindows: Int = 0
        private set
    var manual: Boolean = false
        private set

    /** When this attempt first started aligning: the 20 s shutter clock doesn't restart on a drop. */
    private var firstAlignAt = -1L
    private var lastTick = 0L
    private var pausedSince = 0L

    val progress: Float get() = (bankedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val isScanning: Boolean get() = state == State.SCANNING || state == State.PAUSED

    fun beginAlign(nowMs: Long) {
        state = State.ALIGNING
        if (firstAlignAt < 0) firstAlignAt = nowMs
        bankedMs = 0L
        manual = false
    }

    fun shutterAvailable(nowMs: Long): Boolean =
        state == State.ALIGNING &&
            (nowMs - firstAlignAt >= shutterAfterMs || droppedWindows >= shutterAfterDrops)

    fun start(nowMs: Long, manual: Boolean) {
        state = State.SCANNING
        this.manual = manual
        bankedMs = 0L
        lastTick = nowMs
    }

    /** Advance the clock. Returns what changed, if anything. */
    fun tick(nowMs: Long, allPass: Boolean): Event? {
        val dt = (nowMs - lastTick).coerceIn(0L, MAX_TICK_MS)
        lastTick = nowMs
        when (state) {
            State.SCANNING -> {
                if (!allPass) {
                    state = State.PAUSED
                    pausedSince = nowMs
                    return Event.PAUSED
                }
                bankedMs += dt
                if (bankedMs >= totalMs) {
                    bankedMs = totalMs
                    state = State.DONE
                    return Event.COMPLETED
                }
            }
            State.PAUSED -> {
                if (allPass) {
                    state = State.SCANNING
                    return Event.RESUMED
                }
                if (!manual && nowMs - pausedSince > maxPauseMs) {
                    droppedWindows++
                    beginAlign(nowMs)
                    return Event.DROPPED
                }
            }
            State.ALIGNING, State.DONE -> Unit
        }
        return null
    }

    companion object {
        const val MAX_PAUSE_MS = 1500L
        const val SHUTTER_AFTER_MS = 20_000L
        const val SHUTTER_AFTER_DROPS = 3
        /** A stalled main thread must not bank a big jump of time at once. */
        const val MAX_TICK_MS = 250L
    }
}
