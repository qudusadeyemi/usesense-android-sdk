package com.usesense.sdk.capture.scan

/**
 * Turns the gate's failing hints into one steady on-screen hint.
 *
 *  - A hint appears only after its check has failed for [showAfterMs] without a break.
 *  - Among hints that have, the highest-priority one wins.
 *  - Once shown, a hint stays at least [minVisibleMs], even if it stops failing.
 *
 * So hints never flicker, and spoken announcements (made only when [update]
 * reports a new hint) are rate-limited the same way.
 */
internal class HintScheduler(
    private val showAfterMs: Long = SHOW_AFTER_MS,
    private val minVisibleMs: Long = MIN_VISIBLE_MS,
) {
    private val failingSince = HashMap<GateHint, Long>()

    var current: GateHint? = null
        private set
    private var shownAt = 0L

    fun reset() {
        failingSince.clear()
        current = null
        shownAt = 0L
    }

    /**
     * @param failing the gate's failing hints, highest priority first.
     * @return true when [current] changed to a new, non-null hint (announce it).
     */
    fun update(nowMs: Long, failing: List<GateHint>): Boolean {
        failingSince.keys.retainAll(failing.toSet())
        for (h in failing) failingSince.getOrPut(h) { nowMs }

        val shown = current
        if (shown != null && nowMs - shownAt < minVisibleMs) return false

        val ready = failing.firstOrNull { nowMs - (failingSince[it] ?: nowMs) >= showAfterMs }
        if (ready == shown) return false
        current = ready
        shownAt = nowMs
        return ready != null
    }

    companion object {
        const val SHOW_AFTER_MS = 400L
        const val MIN_VISIBLE_MS = 1200L
    }
}
