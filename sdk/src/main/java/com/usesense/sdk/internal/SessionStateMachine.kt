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

class SessionStateMachine {

    @Volatile
    var currentState: SessionState = SessionState.IDLE
        private set

    private val validTransitions = mapOf(
        SessionState.IDLE to setOf(SessionState.CREATED, SessionState.ERROR),
        SessionState.CREATED to setOf(SessionState.CAPTURING, SessionState.ERROR),
        SessionState.CAPTURING to setOf(SessionState.UPLOADING, SessionState.ERROR),
        SessionState.UPLOADING to setOf(SessionState.COMPLETING, SessionState.ERROR),
        SessionState.COMPLETING to setOf(SessionState.DONE, SessionState.ERROR),
    )

    private val listeners = mutableListOf<(SessionState, SessionState) -> Unit>()

    fun addListener(listener: (oldState: SessionState, newState: SessionState) -> Unit) {
        listeners.add(listener)
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
    fun reset() {
        currentState = SessionState.IDLE
    }
}
