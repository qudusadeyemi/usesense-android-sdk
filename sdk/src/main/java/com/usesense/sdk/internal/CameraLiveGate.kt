package com.usesense.sdk.internal

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the capture clock until the camera actually delivers a frame.
 *
 * The capture used to start the moment the camera was bound. On a budget
 * phone (Galaxy A03, 2026-11-07) the camera took about 2s to configure and
 * longer to stream, so the 2s baseline plus ~0.5s challenge window ran out
 * first: captures came back with 0, 2 or 7 frames, and 0 ended in
 * NO_FRAMES_CAPTURED. Starting the clock on the first real frame gives every
 * phone the same capture window. The camera watchdog still bounds the wait.
 *
 * Threading: [markFrame] is called from the analyzer thread; [whenLive],
 * [onLive] and [reset] from the main thread. Because [onLive] is posted to
 * the main thread after [markFrame], it always runs after any [whenLive]
 * that saw the camera as not yet live.
 */
internal class CameraLiveGate {
    private val live = AtomicBoolean(false)
    private var pending: (() -> Unit)? = null

    val isLive: Boolean get() = live.get()

    /** A new camera binding: not live until its first frame. */
    fun reset() {
        live.set(false)
        pending = null
    }

    /** Analyzer thread. True exactly once per binding, on the first frame. */
    fun markFrame(): Boolean = live.compareAndSet(false, true)

    /** Main thread. Runs [action] now if frames are flowing, else on the first frame. */
    fun whenLive(action: () -> Unit) {
        if (live.get()) action() else pending = action
    }

    /** Main thread, posted after [markFrame] returned true. */
    fun onLive() {
        val action = pending
        pending = null
        action?.invoke()
    }
}
