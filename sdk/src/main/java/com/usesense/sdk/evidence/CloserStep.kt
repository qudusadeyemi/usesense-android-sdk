package com.usesense.sdk.evidence

/**
 * The closer step's live pacing (EVIDENCE-v1 section 7, step 5 of the scan):
 * the oval grows by `target_scale` and the person follows it. Pure: the caller
 * feeds the clock, the gate's eye distance and whether the near-phase gate
 * passes, and banks frames while [Tick.bank] is true.
 *
 * Never blocks: [TIMEOUT_MS] without reaching `min_scale` ends the step (the
 * caller records `skipped_reason: "timeout"`); once reached, [HOLD_BUDGET_MS]
 * more to hold still and bank. Same pacing as iOS.
 */
internal class CloserStepController(
    /** Median eye distance of the last scan gate frames (any unit; the near test uses the same one). */
    private val farEye: Double,
    private val minScale: Double,
    private val startMs: Long,
) {
    data class Tick(
        /** Bank frames now: close enough, gate passing, held still for [SETTLE_MS]. */
        val bank: Boolean,
        /** Still needs to come closer ("Move a little closer"); false shows "Hold still". */
        val needsCloser: Boolean,
        /** The step is over (enough near frames, or out of time). */
        val done: Boolean,
    )

    val threshold: Double = farEye * minScale
    private var deadline = startMs + TIMEOUT_MS
    private var reached = false
    private var shownNear = false
    private var holdingSince = -1L

    /**
     * [nearFrames]: near frames confirmed so far by the uploaded frames' own mesh.
     * [bankedNear]: frames banked by this step (each banked only while close
     * enough). The mesh lags banking (about 1 s on a Galaxy A03), so waiting on
     * it alone held people for the whole hold budget; [BANKED_ENOUGH] banked
     * frames end the step and the upload confirms them.
     */
    fun tick(nowMs: Long, eye: Double?, gatePasses: Boolean, nearFrames: Int, budgetLeft: Boolean, bankedNear: Int = 0): Tick {
        val e = eye ?: 0.0
        // The copy switches at the threshold and only goes back a little below it, so it doesn't flicker.
        shownNear = if (shownNear) e >= threshold * RELEASE_SHARE else e >= threshold
        if (shownNear && !reached) {
            reached = true
            deadline = maxOf(deadline, nowMs + HOLD_BUDGET_MS)
        }
        holdingSince = if (e >= threshold && gatePasses) (if (holdingSince < 0) nowMs else holdingSince) else -1L
        val bank = holdingSince >= 0 && nowMs - holdingSince >= SETTLE_MS
        val done = nearFrames >= PHASE_FRAMES || bankedNear >= BANKED_ENOUGH || !budgetLeft || nowMs >= deadline
        return Tick(bank = bank && !done, needsCloser = !shownNear, done = done)
    }

    companion object {
        /** Frames each phase needs (section 7: 3 or more). */
        const val PHASE_FRAMES = 3
        /** Banked near frames that end the step: one spare over [PHASE_FRAMES] for a frame the mesh rejects. */
        const val BANKED_ENOUGH = 4
        /** Frames the closer step may bank at most, out of `upload.max_frames`. */
        const val MAX_FRAMES = 6
        /** Section 7: the user didn't reach `min_scale` within 4 s. */
        const val TIMEOUT_MS = 4_000L
        /** Once close enough, this long to settle and bank. */
        const val HOLD_BUDGET_MS = 3_000L
        /** Close enough and still this long before frames bank (iOS device run: frames banked while still moving). */
        const val SETTLE_MS = 400L
        /** "Move a little closer" comes back only below this share of the threshold. */
        const val RELEASE_SHARE = 0.95
    }
}

/** The closer record from the uploaded frames' own mesh, at upload time. Pure. */
internal object CloserResolver {
    /** One uploaded frame with a mesh entry: its upload position and its eye distance in the JPEG. */
    data class MeshedFrame(val index: Int, val eye: Double?)

    /**
     * Far: the last [CloserStepController.PHASE_FRAMES] meshed frames before the
     * step ([startIndex]). Near: meshed frames banked during the step
     * ([startIndex] until [endIndex]) whose eye distance is at least `min_scale`
     * times the far median. Both only from [meshed] (frames with a package
     * entry), so every index the server reads points at landmarks. Fewer than
     * three of either is a timeout with no frames (`closer:none`).
     */
    fun resolve(
        meshed: List<MeshedFrame>,
        startIndex: Int,
        endIndex: Int,
        minScale: Double,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): CloserEvidence {
        val n = CloserStepController.PHASE_FRAMES
        val sorted = meshed.sortedBy { it.index }
        val far = sorted.filter { it.index < startIndex && it.eye != null }.takeLast(n)
        val farMedian = Evidence.median(far.mapNotNull { it.eye })
        if (far.size < n || farMedian == null || farMedian <= 0) {
            return CloserEvidence.skipped(CloserEvidence.TIMEOUT, startedAtMs, endedAtMs)
        }
        val need = farMedian * minScale
        val near = sorted.filter { it.index in startIndex until endIndex && (it.eye ?: 0.0) >= need }
        if (near.size < n) return CloserEvidence.skipped(CloserEvidence.TIMEOUT, startedAtMs, endedAtMs)
        return CloserEvidence(
            farFrames = far.map { it.index },
            nearFrames = near.map { it.index },
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            achievedScale = Evidence.median(near.mapNotNull { it.eye })?.let { it / farMedian },
            completed = true,
            skippedReason = null,
        )
    }
}
