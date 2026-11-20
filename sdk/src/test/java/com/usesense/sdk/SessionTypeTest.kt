package com.usesense.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTypeTest {

    @Test
    fun `liveness wire value is liveness`() {
        assertEquals("liveness", SessionType.LIVENESS.value)
        assertEquals("enrollment", SessionType.ENROLLMENT.value)
        assertEquals("authentication", SessionType.AUTHENTICATION.value)
    }

    @Test
    fun `liveness without identity id is valid`() {
        assertNull(UseSense.validateRequest(VerificationRequest(sessionType = SessionType.LIVENESS)))
        assertNull(
            UseSense.validateRequest(
                VerificationRequest(sessionType = SessionType.LIVENESS, externalUserId = "user-1")
            )
        )
    }

    @Test
    fun `liveness with identity id is rejected like the server`() {
        val error = UseSense.validateRequest(
            VerificationRequest(sessionType = SessionType.LIVENESS, identityId = "idn_123")
        )
        assertNotNull(error)
        assertEquals(UseSenseError.INVALID_CONFIG, error!!.code)
        assertTrue(error.message.contains("liveness"))
    }

    @Test
    fun `blank identity id with liveness is treated as absent`() {
        assertNull(
            UseSense.validateRequest(VerificationRequest(sessionType = SessionType.LIVENESS, identityId = " "))
        )
    }

    @Test
    fun `authentication still requires identity id`() {
        val error = UseSense.validateRequest(VerificationRequest(sessionType = SessionType.AUTHENTICATION))
        assertNotNull(error)
        assertEquals(UseSenseError.INVALID_CONFIG, error!!.code)
        assertNull(
            UseSense.validateRequest(
                VerificationRequest(sessionType = SessionType.AUTHENTICATION, identityId = "idn_123")
            )
        )
    }

    @Test
    fun `enrollment needs no identity id`() {
        assertNull(UseSense.validateRequest(VerificationRequest(sessionType = SessionType.ENROLLMENT)))
    }
}
