package com.usesense.sdk.challenge

import android.os.Handler
import android.os.Looper
import com.usesense.sdk.api.models.ChallengeSpec

class SpeakPhraseChallenge(
    override val spec: ChallengeSpec,
) : ChallengePresenter {

    override val responseBuilder = ChallengeResponseBuilder(spec)
    override var isActive = false
        private set
    override var currentStepIndex = 0
        private set

    override var onStepChanged: ((Int) -> Unit)? = null
    override var onChallengeComplete: (() -> Unit)? = null

    var onPhraseDisplay: ((phrase: String, isRecording: Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun start() {
        isActive = true
        responseBuilder.markStarted()
        responseBuilder.setCurrentStep(0)
        onPhraseDisplay?.invoke(spec.phrase ?: "", true)

        handler.postDelayed({
            isActive = false
            responseBuilder.markCompleted()
            onPhraseDisplay?.invoke(spec.phrase ?: "", false)
            onChallengeComplete?.invoke()
        }, spec.totalDurationMs.toLong())
    }

    override fun stop() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onFrameCaptured(frameIndex: Int, timestampMs: Long) {
        if (!isActive) return
        responseBuilder.recordFrame(frameIndex, timestampMs)
    }
}
