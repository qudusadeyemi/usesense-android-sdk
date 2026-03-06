package com.usesense.sdk.internal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionStateMachineTest {

    private lateinit var sm: SessionStateMachine

    @Before
    fun setup() {
        sm = SessionStateMachine()
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(SessionState.IDLE, sm.currentState)
    }

    @Test
    fun `valid transition IDLE to CREATED succeeds`() {
        assertTrue(sm.transition(SessionState.CREATED))
        assertEquals(SessionState.CREATED, sm.currentState)
    }

    @Test
    fun `full happy path transitions succeed`() {
        assertTrue(sm.transition(SessionState.CREATED))
        assertTrue(sm.transition(SessionState.CAPTURING))
        assertTrue(sm.transition(SessionState.UPLOADING))
        assertTrue(sm.transition(SessionState.COMPLETING))
        assertTrue(sm.transition(SessionState.DONE))
        assertEquals(SessionState.DONE, sm.currentState)
    }

    @Test
    fun `invalid transition IDLE to UPLOADING fails`() {
        assertFalse(sm.transition(SessionState.UPLOADING))
        assertEquals(SessionState.IDLE, sm.currentState)
    }

    @Test
    fun `error transition from any state succeeds`() {
        assertTrue(sm.transition(SessionState.CREATED))
        assertTrue(sm.transition(SessionState.ERROR))
        assertEquals(SessionState.ERROR, sm.currentState)
    }

    @Test
    fun `reset returns to IDLE`() {
        sm.transition(SessionState.CREATED)
        sm.transition(SessionState.CAPTURING)
        sm.reset()
        assertEquals(SessionState.IDLE, sm.currentState)
    }

    @Test
    fun `listener is notified on transition`() {
        var capturedOld: SessionState? = null
        var capturedNew: SessionState? = null
        sm.addListener { old, new ->
            capturedOld = old
            capturedNew = new
        }
        sm.transition(SessionState.CREATED)
        assertEquals(SessionState.IDLE, capturedOld)
        assertEquals(SessionState.CREATED, capturedNew)
    }

    @Test
    fun `cannot transition from DONE`() {
        sm.transition(SessionState.CREATED)
        sm.transition(SessionState.CAPTURING)
        sm.transition(SessionState.UPLOADING)
        sm.transition(SessionState.COMPLETING)
        sm.transition(SessionState.DONE)
        assertFalse(sm.transition(SessionState.CREATED))
        assertEquals(SessionState.DONE, sm.currentState)
    }
}
