package com.usesense.sdk.evidence

/** What the colour player needs from the screen and the camera (main thread). */
internal interface ColourTintHost {
    /** [colour] null hides the tint. */
    fun showTint(colour: String?, opacity: Double)

    /** Raise the screen to full brightness; the brightness actually set (0 to 1), or null if unknown. */
    fun holdFullBrightness(): Double?

    fun releaseFullBrightness()

    /** Lock (true) or release (false) auto white balance. True when the lock was applied. */
    fun lockWhiteBalance(locked: Boolean): Boolean
}

/**
 * Plays the server's colour sequence (EVIDENCE-v1 section 6) and records what
 * actually happened. A segment starts at the Choreographer frame time of the
 * frame that first draws it, on the capture clock. Main thread only; the
 * driver feeds [advance] from a Choreographer frame callback.
 *
 * Order rule (Watchtower `scoreReflection`): uploaded segments must read
 * `issued[i % n]` in order, or the scorer reports `sequence_mismatch`. So a
 * gate pause drops the partly shown segment and the replay resumes at that
 * colour (the spec's "replay the full sequence" would break the order); a
 * dropped scan window drops its frames, so the record clears and the next
 * window starts again at the first colour. Same as iOS.
 */
internal class ColourSequencePlayer(
    val sequence: EvidenceConfig.ColourSequence,
    /** Choreographer frame time (System.nanoTime base) to capture-clock ms; null before the clock starts. */
    private val clock: (Long) -> Long?,
) {
    enum class Phase { IDLE, LEAD_IN, SEGMENT, DONE }

    var host: ColourTintHost? = null

    var phase: Phase = Phase.IDLE
        private set
    private var segmentIndex = -1
    var record: ColourEvidence = ColourEvidence(sequenceId = sequence.id)
        private set
    /** Plays that showed at least one colour. A pause during the lead-in costs nothing. */
    var runs = 0
        private set
    private var shownThisPlay = false
    private var phaseStartNanos = 0L
    private var awbAllRuns = true
    /** The colour the next play starts at. */
    var nextIndex = 0
        private set

    val isPlaying: Boolean get() = phase == Phase.LEAD_IN || phase == Phase.SEGMENT
    val finished: Boolean get() = phase == Phase.DONE

    /** Start, or resume after a pause, from a lead-in. */
    fun play(nowNanos: Long) {
        if (isPlaying || finished) return
        shownThisPlay = false
        phase = Phase.LEAD_IN
        phaseStartNanos = nowNanos
        val locked = host?.lockWhiteBalance(true) ?: false
        awbAllRuns = awbAllRuns && locked
        val brightness = host?.holdFullBrightness()
        record = record.copy(awbLocked = awbAllRuns, screenBrightness = brightness ?: record.screenBrightness)
    }

    /** A gate pause: stop the tint. The partly shown segment is dropped; the next play resumes at its colour. */
    fun interrupt(reason: String = GATE_PAUSE) {
        if (!isPlaying) return
        if (phase == Phase.SEGMENT) {
            record = record.copy(segments = record.segments.dropLast(1))
            nextIndex = segmentIndex
        }
        record = record.copy(completed = false, interruptedReason = reason)
        stop(Phase.IDLE)
    }

    /** The scan window was dropped with its frames: the segments go too. */
    fun resetForNewWindow() {
        interrupt(WINDOW_DROPPED)
        record = ColourEvidence(sequenceId = sequence.id)
        nextIndex = 0
        runs = 0
        awbAllRuns = true
        phase = Phase.IDLE
    }

    /** Drive the state machine to the frame drawn at [frameNanos]. */
    fun advance(frameNanos: Long) {
        val leadIn = sequence.leadInMs * 1_000_000L
        val segment = sequence.segmentMs * 1_000_000L
        when (phase) {
            Phase.LEAD_IN -> if (frameNanos - phaseStartNanos >= leadIn) begin(nextIndex, frameNanos)
            Phase.SEGMENT -> if (frameNanos - phaseStartNanos >= segment) {
                closeSegment(frameNanos)
                val i = segmentIndex
                nextIndex = i + 1
                if (i + 1 < sequence.colours.size) {
                    begin(i + 1, frameNanos)
                } else {
                    record = record.copy(completed = true, interruptedReason = null)
                    stop(Phase.DONE)
                }
            }
            else -> Unit
        }
    }

    private fun begin(i: Int, frameNanos: Long) {
        val ms = clock(frameNanos) ?: return
        phase = Phase.SEGMENT
        segmentIndex = i
        phaseStartNanos = frameNanos
        if (!shownThisPlay) { shownThisPlay = true; runs++ }
        val colour = sequence.colours[i]
        record = record.copy(segments = record.segments + ColourEvidence.Segment(colour, ms, ms))
        host?.showTint(colour, sequence.opacity)
    }

    private fun closeSegment(frameNanos: Long) {
        val last = record.segments.lastOrNull() ?: return
        val end = maxOf(last.startMs + 1, clock(frameNanos) ?: (last.startMs + 1))
        record = record.copy(segments = record.segments.dropLast(1) + last.copy(endMs = end))
    }

    private fun stop(next: Phase) {
        val wasPlaying = isPlaying
        phase = next
        if (!wasPlaying) return
        host?.showTint(null, 0.0)
        host?.releaseFullBrightness()
        host?.lockWhiteBalance(false)
    }

    companion object {
        const val GATE_PAUSE = "gate_pause"
        const val WINDOW_DROPPED = "window_dropped"
        const val SCAN_ENDED = "scan_ended"
        const val CANCELLED = "cancelled"
        /** A gate pause restarts the sequence; this many plays at most (as iOS). */
        const val MAX_RUNS = 3
    }
}
