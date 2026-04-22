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
    private val finished: AtomicReference<Boolean> = AtomicReference(false)

    fun install(cb: V4VerificationCallback) {
        finished.set(false)
        callback.set(cb)
    }

    fun clear() {
        callback.set(null)
        finished.set(true)
    }

    fun dispatchSuccess(verdict: V4Verdict) {
        if (finished.getAndSet(true)) return
        val cb = callback.getAndSet(null) ?: return
        mainHandler.post { runCatching { cb.onComplete(verdict) } }
    }

    fun dispatchFailure(error: Throwable) {
        if (finished.getAndSet(true)) return
        val cb = callback.getAndSet(null) ?: return
        mainHandler.post { runCatching { cb.onFailure(error) } }
    }

    fun dispatchPhase(phase: V4Phase) {
        val cb = callback.get() ?: return
        if (finished.get()) return
        mainHandler.post { runCatching { cb.onPhaseChange(phase) } }
    }
}
