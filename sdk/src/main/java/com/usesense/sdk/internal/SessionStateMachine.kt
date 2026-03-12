package com.usesense.sdk.internal

enum class SessionState {
    IDLE,
    CREATED,
    CAPTURING,
    UPLOADING,
    COMPLETING,
    DONE,
    ERROR;
}

/**
 * Capture engine phases matching the Web SDK state machine (Section 4).
 * The full phase flow is:
 *   INITIALIZING -> CAMERA_REQUEST -> [CAMERA_ERROR -> CAMERA_REQUEST] ->
 *   INSTRUCTIONS -> FACE_GUIDE -> BASELINE -> COUNTDOWN -> CHALLENGE ->
 *   UPLOADING -> COMPLETING -> DONE
 */
enum class CapturePhase {
    INITIALIZING,     // SDK setup
    CAMERA_REQUEST,   // Requesting camera permission
    CAMERA_ERROR,     // Camera denied/unavailable (user can retry)
    INSTRUCTIONS,     // Pre-challenge instructions (tap "Got it - Start")
    FACE_GUIDE,       // Oval face positioning guide (tap "I'm Ready")
    BASELINE,         // 2000ms passive capture, looking straight ahead
    COUNTDOWN,        // 3-2-1 countdown with continued capture
    CHALLENGE,        // Active challenge (head_turn / follow_dot / speak_phrase)
    UPLOADING,        // Uploading frames + metadata to server
    COMPLETING,       // Server-side evaluation in progress
    DONE;             // Terminal - onComplete/onError has been called
}

class SessionStateMachine {

    @Volatile
    var currentState: SessionState = SessionState.IDLE
        private set

    @Volatile
    var capturePhase: CapturePhase = CapturePhase.INSTRUCTIONS
        private set

    private val validTransitions = mapOf(
        SessionState.IDLE to setOf(SessionState.CREATED, SessionState.ERROR),
        SessionState.CREATED to setOf(SessionState.CAPTURING, SessionState.ERROR),
        SessionState.CAPTURING to setOf(SessionState.UPLOADING, SessionState.ERROR),
        SessionState.UPLOADING to setOf(SessionState.COMPLETING, SessionState.ERROR),
        SessionState.COMPLETING to setOf(SessionState.DONE, SessionState.ERROR),
    )

    private val listeners = mutableListOf<(SessionState, SessionState) -> Unit>()
    private val phaseListeners = mutableListOf<(CapturePhase) -> Unit>()

    fun addListener(listener: (oldState: SessionState, newState: SessionState) -> Unit) {
        listeners.add(listener)
    }

    fun addPhaseListener(listener: (CapturePhase) -> Unit) {
        phaseListeners.add(listener)
    }

    @Synchronized
    fun transition(newState: SessionState): Boolean {
        val allowed = validTransitions[currentState] ?: emptySet()
        if (newState !in allowed) {
            return false
        }
        val oldState = currentState
        currentState = newState
        listeners.forEach { it(oldState, newState) }
        return true
    }

    @Synchronized
    fun setCapturePhase(phase: CapturePhase) {
        capturePhase = phase
        phaseListeners.forEach { it(phase) }
    }

    @Synchronized
    fun reset() {
        currentState = SessionState.IDLE
        capturePhase = CapturePhase.INSTRUCTIONS
    }
}
