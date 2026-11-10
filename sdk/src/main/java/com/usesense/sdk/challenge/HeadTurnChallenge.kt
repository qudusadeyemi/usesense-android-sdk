package com.usesense.sdk.challenge

import android.os.Handler
import android.os.Looper
import com.usesense.sdk.api.models.ChallengeSpec

class HeadTurnChallenge(
    override val spec: ChallengeSpec,
) : ChallengePresenter {

    override val responseBuilder = ChallengeResponseBuilder(spec)
    override var isActive = false
        private set
    override var currentStepIndex = 0
        private set

    override var onStepChanged: ((Int) -> Unit)? = null
    override var onChallengeComplete: (() -> Unit)? = null

    var onDirectionChanged: ((direction: String, stepIndex: Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val sequence = spec.sequence ?: emptyList()

    override fun start() {
        if (sequence.isEmpty()) {
            // A spec with no steps used to return here without ever calling
            // onChallengeComplete, leaving the capture screen running forever
            // and the signals never sent. Run for the spec's duration instead,
            // then complete, so the capture always reaches finalization.
            isActive = true
            responseBuilder.markStarted()
            handler.postDelayed({
                isActive = false
                responseBuilder.markCompleted()
                onChallengeComplete?.invoke()
            }, spec.totalDurationMs.toLong().coerceAtLeast(MIN_EMPTY_SPEC_MS))
            return
        }
        isActive = true
        currentStepIndex = 0
        responseBuilder.markStarted()
        responseBuilder.setCurrentStep(0)
        showStep(0)
    }

    override fun beginDriven() {
        handler.removeCallbacksAndMessages(null)
        isActive = true
        currentStepIndex = 0
        responseBuilder.markStarted()
        responseBuilder.setCurrentStep(0)
    }

    override fun driveStep(index: Int) {
        currentStepIndex = index
        responseBuilder.setCurrentStep(index)
        onStepChanged?.invoke(index)
    }

    override fun endDriven() {
        if (!isActive) return
        isActive = false
        responseBuilder.markCompleted()
    }

    override fun stop() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onFrameCaptured(frameIndex: Int, timestampMs: Long) {
        if (!isActive) return
        responseBuilder.recordFrame(frameIndex, timestampMs)
    }

    private fun showStep(index: Int) {
        if (index >= sequence.size) {
            isActive = false
            responseBuilder.markCompleted()
            onChallengeComplete?.invoke()
            return
        }

        val step = sequence[index]
        currentStepIndex = index
        responseBuilder.setCurrentStep(index)
        onStepChanged?.invoke(index)
        onDirectionChanged?.invoke(step.direction, index)

        handler.postDelayed({
            showStep(index + 1)
        }, step.durationMs.toLong())
    }

    private companion object {
        const val MIN_EMPTY_SPEC_MS = 1_000L
    }
}
