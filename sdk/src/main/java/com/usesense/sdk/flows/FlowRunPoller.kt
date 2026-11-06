package com.usesense.sdk.flows

import kotlinx.coroutines.delay

/**
 * Bounded re-reading of a run that is not yet actionable.
 *
 * The runner used to show a spinner and return when the server answered with
 * a non-terminal run and no pending action (a step still settling server-side):
 * nothing ever re-read the run, so the subject sat on the spinner forever and
 * the host never heard a thing. This polls with backoff and, past [budgetMs],
 * fails with a stable [FlowError.Code.RUN_TIMEOUT] instead.
 *
 * Network faults while polling are tolerated until the budget runs out (the
 * subject may be on a flaky link); any other FlowError ends polling at once.
 */
internal class FlowRunPoller(
    private val budgetMs: Long = BUDGET_MS,
    private val initialIntervalMs: Long = INITIAL_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <T> pollUntil(fetch: suspend () -> T, isSettled: (T) -> Boolean): T {
        val startedAt = nowMs()
        var interval = initialIntervalMs
        while (true) {
            val elapsed = nowMs() - startedAt
            if (elapsed >= budgetMs) {
                throw FlowError(
                    FlowError.Code.RUN_TIMEOUT,
                    "This step is taking longer than expected. Please try again.",
                    SERVER_CODE,
                )
            }
            pause(minOf(interval, budgetMs - elapsed))
            interval = minOf(interval * 2, maxIntervalMs)
            val next = try {
                fetch()
            } catch (e: FlowError) {
                if (e.code != FlowError.Code.NETWORK_UNAVAILABLE) throw e
                continue
            }
            if (isSettled(next)) return next
        }
    }

    companion object {
        const val INITIAL_INTERVAL_MS = 1_500L
        const val MAX_INTERVAL_MS = 5_000L

        /** How long a run may sit with no pending action before the runner gives up. */
        const val BUDGET_MS = 90_000L

        const val SERVER_CODE = "RUN_POLL_TIMEOUT"
    }
}
