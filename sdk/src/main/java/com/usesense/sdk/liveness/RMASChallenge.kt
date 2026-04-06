package com.usesense.sdk.liveness

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Randomized Micro-Action Sequence (RMAS) step-up challenge per spec Chapter 7.5.
 *
 * Prompts 3 random facial actions. Pre-recorded video can't respond to random prompts.
 *
 * Action pool: blink, smile, raise_eyebrows, open_mouth, turn_left, turn_right
 * - 1.5s window per action, 300ms pause between
 * - Pass if >= 2 of 3 actions completed
 */
internal class RMASChallenge {

    data class ActionResult(
        val actionType: String,
        val label: String,
        val windowMs: Long,
        val completed: Boolean,
        val reactionTimeMs: Long,
    )

    private val actionResults = mutableListOf<ActionResult>()
    var passed = false
        private set
    var hardReject = false
        private set
    var confidence = 0
        private set

    /**
     * Execute the RMAS challenge.
     *
     * @param showAction Callback to display action label to user. Returns when UI is updated.
     * @param detectAction Function that polls face detection for the specified action.
     *                     Returns true if the action was detected within the time window.
     * @param getReactionTimeMs Returns how long it took for the action to be detected.
     */
    suspend fun execute(
        showAction: suspend (actionType: String, label: String, stepNumber: Int) -> Unit,
        detectAction: suspend (actionType: String, windowMs: Long) -> Boolean,
        getReactionTimeMs: () -> Long,
    ): Boolean = withContext(Dispatchers.Main) {
        actionResults.clear()

        // Pick 3 random actions (no duplicates)
        val actions = ACTION_POOL.shuffled().take(3)

        for ((index, action) in actions.withIndex()) {
            // Show action prompt
            showAction(action.type, action.label, index + 1)

            // Wait for detection within window
            val startMs = System.currentTimeMillis()
            val completed = detectAction(action.type, ACTION_WINDOW_MS)
            val reactionTime = if (completed) System.currentTimeMillis() - startMs else ACTION_WINDOW_MS

            actionResults.add(ActionResult(
                actionType = action.type,
                label = action.label,
                windowMs = ACTION_WINDOW_MS,
                completed = completed,
                reactionTimeMs = reactionTime,
            ))

            // Pause between actions
            if (index < actions.size - 1) {
                delay(PAUSE_BETWEEN_ACTIONS_MS)
            }
        }

        val completedCount = actionResults.count { it.completed }
        passed = completedCount >= 2
        hardReject = completedCount == 0

        confidence = when (completedCount) {
            3 -> 90
            2 -> 67
            1 -> 30
            else -> 5
        }

        passed
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("type", "rmas")

        val actionsArray = JSONArray()
        for (result in actionResults) {
            actionsArray.put(JSONObject().apply {
                put("actionType", result.actionType)
                put("label", result.label)
                put("windowMs", result.windowMs)
                put("completed", result.completed)
                put("reactionTimeMs", result.reactionTimeMs)
            })
        }
        json.put("actions", actionsArray)
        json.put("passed", passed)
        json.put("confidence", confidence)
        json.put("actionsCompleted", actionResults.count { it.completed })
        json.put("actionsTotal", actionResults.size)

        return json
    }

    data class Action(val type: String, val label: String)

    companion object {
        const val ACTION_WINDOW_MS = 1500L
        const val PAUSE_BETWEEN_ACTIONS_MS = 300L

        val ACTION_POOL = listOf(
            Action("blink", "Blink twice"),
            Action("smile", "Smile"),
            Action("raise_eyebrows", "Raise your eyebrows"),
            Action("open_mouth", "Open your mouth"),
            Action("turn_left", "Turn head left"),
            Action("turn_right", "Turn head right"),
        )

        /**
         * Validation thresholds per action type.
         * Used by the face detection polling loop to determine if an action was performed.
         */
        object Thresholds {
            const val BLINK_EAR_THRESHOLD = 0.2           // Eye aspect ratio < 0.2 = closed
            const val SMILE_MOUTH_INCREASE = 0.15          // Mouth width increase > 15%
            const val EYEBROW_RAISE_THRESHOLD = 0.008      // Brow landmarks move up > 0.008
            const val MOUTH_OPEN_THRESHOLD = 0.025          // Lip distance > 0.025
            const val HEAD_TURN_YAW_THRESHOLD = 12.0       // Yaw exceeds 12 degrees
        }
    }
}
