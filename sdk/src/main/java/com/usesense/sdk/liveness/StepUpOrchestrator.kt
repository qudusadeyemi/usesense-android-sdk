package com.usesense.sdk.liveness

import android.graphics.Bitmap
import android.view.View
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Step-Up Orchestrator per spec Chapter 7.6-7.7.
 *
 * Coordinates inline step-up challenges when the Suspicion Engine triggers.
 * Challenge selection (auto mode):
 *   - Below threshold: none
 *   - threshold to threshold+20: Flash Reflection only (~3s)
 *   - threshold+20 and above: Flash Reflection + RMAS (~9s)
 *
 * 15-second hard timeout: if not done, continue with upload.
 * Camera stays active throughout.
 */
internal class StepUpOrchestrator(
    private val suspicionThreshold: Int = 55,
    private val preferredChallenge: String = "auto",
) {
    private var flashReflectionChallenge: FlashReflectionChallenge? = null
    private var rmasChallenge: RMASChallenge? = null

    var passed = false
        private set
    var hardReject = false
        private set
    var timedOut = false
        private set

    private val challengesRun = mutableListOf<String>()
    private var triggerSuspicionScore = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Execute the step-up flow with a 15-second hard timeout.
     *
     * @param suspicionScore Current suspicion engine score
     * @param overlayView View for flash reflection color overlay
     * @param sampleFrame Captures current camera frame
     * @param showAction RMAS: shows action prompt to user
     * @param detectAction RMAS: polls face detection for action completion
     * @param getReactionTimeMs RMAS: returns reaction time
     * @param onShowIntro Callback to show "Additional Verification" intro
     * @param onShowComplete Callback to show "Verification complete" indicator
     */
    suspend fun execute(
        suspicionScore: Int,
        overlayView: View,
        sampleFrame: suspend () -> Bitmap?,
        showAction: suspend (String, String, Int) -> Unit,
        detectAction: suspend (String, Long) -> Boolean,
        getReactionTimeMs: () -> Long,
        onShowIntro: suspend () -> Unit,
        onShowComplete: suspend () -> Unit,
    ) {
        triggerSuspicionScore = suspicionScore

        try {
            withTimeout(TIMEOUT_MS) {
                // Show intro (1.5s)
                onShowIntro()
                delay(1500)

                // Determine which challenges to run
                val runFlash = shouldRunFlash(suspicionScore)
                val runRmas = shouldRunRmas(suspicionScore)

                // Flash Reflection
                if (runFlash) {
                    challengesRun.add("flash_reflection")
                    flashReflectionChallenge = FlashReflectionChallenge()
                    flashReflectionChallenge!!.execute(overlayView, sampleFrame)
                }

                // RMAS
                if (runRmas) {
                    challengesRun.add("rmas")
                    rmasChallenge = RMASChallenge()
                    rmasChallenge!!.execute(showAction, detectAction, getReactionTimeMs)
                }

                // Show completion (0.5s)
                onShowComplete()
                delay(500)

                // Evaluate overall result
                evaluateResults()
            }
        } catch (e: TimeoutCancellationException) {
            timedOut = true
            passed = false
            hardReject = false
        }
    }

    private fun shouldRunFlash(score: Int): Boolean {
        return when (preferredChallenge) {
            "flash_reflection" -> true
            "rmas" -> false
            else -> score >= suspicionThreshold // auto: always run flash when triggered
        }
    }

    private fun shouldRunRmas(score: Int): Boolean {
        return when (preferredChallenge) {
            "rmas" -> true
            "flash_reflection" -> false
            else -> score >= suspicionThreshold + 20 // auto: run RMAS for higher suspicion
        }
    }

    private fun evaluateResults() {
        val flashPassed = flashReflectionChallenge?.passed
        val rmasPassed = rmasChallenge?.passed
        val flashHardReject = flashReflectionChallenge?.hardReject ?: false
        val rmasHardReject = rmasChallenge?.hardReject ?: false

        // Hard reject: both challenges run AND both decisively failed
        hardReject = when {
            flashHardReject && rmasHardReject -> true
            flashReflectionChallenge?.overallColorDelta?.let { it < 3.0 } == true &&
                rmasChallenge?.let { it.toJson().optInt("actionsCompleted") == 0 } == true -> true
            else -> false
        }

        // Pass if at least one challenge passed
        passed = when {
            hardReject -> false
            flashPassed == true -> true
            rmasPassed == true -> true
            else -> false
        }
    }

    /**
     * Build the evidence JSON for metadata upload.
     * Always included in metadata regardless of whether step-up was triggered.
     */
    fun toJson(): JSONObject? {
        if (challengesRun.isEmpty() && !timedOut) return null

        return JSONObject().apply {
            put("challengesRun", JSONArray(challengesRun))
            put("triggerSuspicionScore", triggerSuspicionScore)

            if (flashReflectionChallenge != null) {
                put("flashReflection", flashReflectionChallenge!!.toJson())
            } else {
                put("flashReflection", JSONObject.NULL)
            }

            if (rmasChallenge != null) {
                put("rmas", rmasChallenge!!.toJson())
            } else {
                put("rmas", JSONObject.NULL)
            }

            put("passed", passed)
            put("hardReject", hardReject)
            put("timestamp", isoFormat.format(Date()))
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
