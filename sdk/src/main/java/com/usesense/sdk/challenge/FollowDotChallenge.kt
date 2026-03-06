package com.usesense.sdk.challenge

import android.os.Handler
import android.os.Looper
import com.usesense.sdk.api.models.ChallengeSpec

class FollowDotChallenge(
    override val spec: ChallengeSpec,
) : ChallengePresenter {

    override val responseBuilder = ChallengeResponseBuilder(spec)
    override var isActive = false
        private set
    override var currentStepIndex = 0
        private set

    override var onStepChanged: ((Int) -> Unit)? = null
    override var onChallengeComplete: (() -> Unit)? = null

    var onDotPositionChanged: ((x: Float, y: Float, animate: Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val waypoints = spec.waypoints ?: emptyList()

    override fun start() {
        if (waypoints.isEmpty()) return
        isActive = true
        currentStepIndex = 0
        responseBuilder.markStarted()
        responseBuilder.setCurrentStep(0)
        showWaypoint(0)
    }

    override fun stop() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onFrameCaptured(frameIndex: Int, timestampMs: Long) {
        if (!isActive) return
        responseBuilder.recordFrame(frameIndex, timestampMs)
    }

    private fun showWaypoint(index: Int) {
        if (index >= waypoints.size) {
            isActive = false
            responseBuilder.markCompleted()
            onChallengeComplete?.invoke()
            return
        }

        val wp = waypoints[index]
        currentStepIndex = index
        responseBuilder.setCurrentStep(index)
        onStepChanged?.invoke(index)

        // Animate dot to new position (first waypoint: no animation)
        onDotPositionChanged?.invoke(wp.x, wp.y, index > 0)

        // Dwell for waypoint duration, then advance
        handler.postDelayed({
            showWaypoint(index + 1)
        }, wp.durationMs.toLong())
    }
}
