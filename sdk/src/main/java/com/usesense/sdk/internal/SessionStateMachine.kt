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
 * Sub-phases within the CAPTURING state (v1.17.5+).
 * FACE_GUIDE and COUNTDOWN are new phases added for challenge sessions.
 */
enum class CapturePhase {
    INSTRUCTIONS,
    FACE_GUIDE,
    BASELINE,
    COUNTDOWN,
    CHALLENGE,
    DONE;
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
