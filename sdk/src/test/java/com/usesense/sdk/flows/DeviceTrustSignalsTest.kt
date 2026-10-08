package com.usesense.sdk.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTrustSignalsTest {
    @Test
    fun `a device step without a nonce is re-read once, then fails`() {
        assertEquals(DeviceTrustSignals.MissingNonce.RELOAD, DeviceTrustSignals.onMissingNonce(alreadyReloaded = false))
        assertEquals(DeviceTrustSignals.MissingNonce.FAIL, DeviceTrustSignals.onMissingNonce(alreadyReloaded = true))
    }

    @Test
    fun `stale nonce and settled step re-read the run`() {
        assertTrue(DeviceTrustSignals.needsReload("nonce_mismatch"))
        assertTrue(DeviceTrustSignals.needsReload("device_step_not_pending"))
        assertFalse(DeviceTrustSignals.needsReload("invalid_input"))
        assertFalse(DeviceTrustSignals.needsReload(null))
    }
}
