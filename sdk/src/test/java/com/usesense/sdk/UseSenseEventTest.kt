package com.usesense.sdk

import org.junit.Assert.*
import org.junit.Test

class UseSenseEventTest {

    @Test
    fun `event emitter delivers events to listeners`() {
        val emitter = UseSenseEventEmitter()
        val received = mutableListOf<UseSenseEvent>()

        emitter.addListener { event -> received.add(event) }
        emitter.emit(EventType.SESSION_CREATED, mapOf("session_id" to "test-123"))

        assertEquals(1, received.size)
        assertEquals(EventType.SESSION_CREATED, received[0].type)
        assertEquals("test-123", received[0].data?.get("session_id"))
    }

    @Test
    fun `unsubscribe stops event delivery`() {
        val emitter = UseSenseEventEmitter()
        val received = mutableListOf<UseSenseEvent>()

        val unsubscribe = emitter.addListener { event -> received.add(event) }
        emitter.emit(EventType.CAPTURE_STARTED)
        assertEquals(1, received.size)

        unsubscribe()
        emitter.emit(EventType.CAPTURE_COMPLETED)
        assertEquals(1, received.size) // no new events
    }

    @Test
    fun `clear removes all listeners`() {
        val emitter = UseSenseEventEmitter()
        val received = mutableListOf<UseSenseEvent>()

        emitter.addListener { received.add(it) }
        emitter.addListener { received.add(it) }
        emitter.emit(EventType.SESSION_CREATED)
        assertEquals(2, received.size)

        emitter.clear()
        emitter.emit(EventType.CAPTURE_STARTED)
        assertEquals(2, received.size) // no new events
    }

    @Test
    fun `redacted result does not contain scores`() {
        val result = UseSenseResult(
            sessionId = "sess-1",
            sessionType = "enrollment",
            identityId = "id-1",
            decision = "APPROVE",
            timestamp = "2026-03-07T12:00:00.000Z",
        )

        assertEquals("APPROVE", result.decision)
        assertTrue(result.isApproved)
        assertFalse(result.isRejected)
        assertFalse(result.isPendingReview)
        // Verify no score fields exist (compilation check - they were removed)
    }
}
