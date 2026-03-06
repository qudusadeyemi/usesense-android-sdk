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

    var onStepChanged: ((stepIndex: Int) -> Unit)?
    var onChallengeComplete: (() -> Unit)?
}
