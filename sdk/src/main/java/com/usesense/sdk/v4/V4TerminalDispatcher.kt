package com.usesense.sdk.v4

import com.usesense.sdk.api.V4VerificationCallback
import com.usesense.sdk.api.V4Verdict
import java.util.concurrent.atomic.AtomicBoolean

internal class V4TerminalDispatcher(
    private val callback: V4VerificationCallback,
    private val post: (() -> Unit) -> Unit,
) {
    private val finished = AtomicBoolean(false)

    fun success(verdict: V4Verdict) {
        if (finished.compareAndSet(false, true)) post { runCatching { callback.onComplete(verdict) } }
    }

    fun failure(error: Throwable) {
        if (finished.compareAndSet(false, true)) post { runCatching { callback.onFailure(error) } }
    }
}
