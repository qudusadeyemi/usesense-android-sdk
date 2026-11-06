package com.usesense.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of [boundedCall]: the value, or a timeout with the still-running work. */
internal sealed interface Bounded<out T> {
    data class Done<T>(val value: T) : Bounded<T>

    /** [work] is still running (a native call cannot be interrupted); it is not awaited. */
    data class TimedOut<T>(val work: Deferred<T>) : Bounded<T>
}

/**
 * Run [block] on [scope] and wait at most [timeoutMs] for it.
 *
 * `withTimeout { withContext(io) { nativeCall() } }` is NOT bounded: cancellation
 * is cooperative, and withContext returns only once its block does, so a native
 * call that never returns (MediaPipe init/detect, a TFLite invoke) still holds the
 * caller forever. Here the caller waits on a Deferred, which is cancellable, so it
 * gets control back on time even if the work itself never finishes. The work is
 * returned in [Bounded.TimedOut] so the owner can clean up whatever it holds
 * (recycle a bitmap, close a model) once it eventually settles.
 */
internal suspend fun <T> boundedCall(
    scope: CoroutineScope,
    timeoutMs: Long,
    block: suspend () -> T,
): Bounded<T> {
    val work = scope.async { block() }
    // Boxed so a legitimately-null result is not mistaken for a timeout.
    val settled = withTimeoutOrNull(timeoutMs) { Bounded.Done(work.await()) }
    return settled ?: Bounded.TimedOut(work)
}
