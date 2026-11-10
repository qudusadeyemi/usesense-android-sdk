package com.usesense.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpiryTest {
    private val t = java.time.Instant.parse("2026-11-10T18:11:00Z").toEpochMilli()

    @Test
    fun `reads the server's formats`() {
        assertEquals(t, SessionExpiry.parse("2026-11-10T18:11:00.000Z"))
        assertEquals(t, SessionExpiry.parse("2026-11-10T18:11:00Z"))
        assertEquals(t + 123, SessionExpiry.parse("2026-11-10T18:11:00.123456+00:00"))
        assertEquals(t + 500, SessionExpiry.parse("2026-11-10T18:11:00.5Z"))
        assertEquals(t, SessionExpiry.parse("2026-11-10T19:11:00+01:00"))
        assertEquals(t, SessionExpiry.parse("2026-11-10T18:11:00"))
        assertNull(SessionExpiry.parse("tomorrow"))
        assertNull(SessionExpiry.parse(null))
    }

    @Test
    fun `ends soon within the start margin, never on an unreadable value`() {
        assertTrue(SessionExpiry.endsWithin("2026-11-10T18:11:00Z", nowMs = t + 1))
        assertTrue(SessionExpiry.endsWithin("2026-11-10T18:11:00Z", nowMs = t - 60_000))
        assertFalse(SessionExpiry.endsWithin("2026-11-10T18:11:00Z", nowMs = t - 10 * 60_000))
        assertFalse(SessionExpiry.endsWithin("2026-11-10T18:11:00Z", nowMs = t - 60_000, marginMs = 0))
        assertFalse(SessionExpiry.endsWithin("garbage", nowMs = t))
    }
}
