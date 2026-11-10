package com.usesense.sdk.challenge

import com.usesense.sdk.api.models.ChallengeSpec

interface ChallengePresenter {
    val spec: ChallengeSpec
    val responseBuilder: ChallengeResponseBuilder
    val isActive: Boolean
    val currentStepIndex: Int

    fun start()
    fun stop()
    fun onFrameCaptured(frameIndex: Int, timestampMs: Long)

    /**
     * Driven mode (the extra check on the scan screen): the screen's
     * ExtraCheckRunner owns the timing, pauses and the retry, and tells the
     * presenter which step the frames belong to. The presenter's own timers
     * never run; [start] is not called.
     */
    fun beginDriven() {}
    fun driveStep(index: Int) {}
    fun endDriven() {}

    var onStepChanged: ((stepIndex: Int) -> Unit)?
    var onChallengeComplete: (() -> Unit)?
}
