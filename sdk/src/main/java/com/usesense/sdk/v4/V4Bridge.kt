package com.usesense.sdk.v4

import android.os.Handler
import android.os.Looper
import com.usesense.sdk.api.V4Phase
import com.usesense.sdk.api.V4VerificationCallback
import com.usesense.sdk.api.V4Verdict
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-singleton that carries the current v4 session's callback from
 * UseSense.startV4Verification into LiveSenseV4Activity.
 *
 * Phase 1 ticket A-2.
 *
 * Standard Android practice is to pass the callback through an
 * ActivityResult contract. We use a process-global singleton instead
 * because the callback surface is rich (three methods including
 * phase streaming) and the ActivityResult contract serialisation path
 * would lose the phase stream. A single in-flight v4 session is
 * guaranteed by Android's single-task activity stack behaviour
 * (launchMode=singleTop on the activity manifest entry).
 *
 * TODO: device-verify that concurrent launches on split-screen +
 * picture-in-picture do not collide. If they can, switch to an
 * IBinder-based passthrough.
 */
internal object V4Bridge {
    private val callback: AtomicReference<V4VerificationCallback?> = AtomicReference(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminal = AtomicReference<V4TerminalDispatcher?>(null)

    fun install(cb: V4VerificationCallback) {
        callback.set(cb)
        terminal.set(V4TerminalDispatcher(cb) { mainHandler.post(it) })
    }

    fun clear() {
        callback.set(null)
        terminal.set(null)
    }

    fun dispatchSuccess(verdict: V4Verdict) {
        terminal.getAndSet(null)?.success(verdict)
        callback.set(null)
    }

    fun dispatchFailure(error: Throwable) {
        terminal.getAndSet(null)?.failure(error)
        callback.set(null)
    }

    fun dispatchPhase(phase: V4Phase) {
        val cb = callback.get() ?: return
        if (terminal.get() == null) return
        mainHandler.post { runCatching { cb.onPhaseChange(phase) } }
    }
}
