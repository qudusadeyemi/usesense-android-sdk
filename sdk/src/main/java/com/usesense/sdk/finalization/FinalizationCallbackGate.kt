package com.usesense.sdk.finalization

import java.util.concurrent.atomic.AtomicBoolean

/** Ensures recoverable UI never becomes a host terminal event. */
internal class FinalizationCallbackGate<S, E>(
    private val onSuccess: (S) -> Unit,
    private val onError: (E) -> Unit,
    private val onCancelled: () -> Unit,
) {
    private val terminal = AtomicBoolean(false)

    fun recovery() = Unit
    fun success(result: S) = once { onSuccess(result) }
    fun exit(error: E) = once { onError(error) }
    fun cancel() = once(onCancelled)

    private inline fun once(callback: () -> Unit) {
        if (terminal.compareAndSet(false, true)) callback()
    }
}
