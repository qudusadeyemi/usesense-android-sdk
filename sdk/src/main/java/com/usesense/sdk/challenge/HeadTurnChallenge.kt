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
        if (sequence.isEmpty()) return
        isActive = true
        currentStepIndex = 0
        responseBuilder.markStarted()
        responseBuilder.setCurrentStep(0)
        showStep(0)
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
}
