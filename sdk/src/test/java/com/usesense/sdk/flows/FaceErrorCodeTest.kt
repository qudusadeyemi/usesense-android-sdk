package com.usesense.sdk.flows

import com.usesense.sdk.UseSenseError
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wrappers (Flutter, RN) see only FlowError.code.wire: timeouts must keep their code. */
class FaceErrorCodeTest {
    @Test
    fun `watchdog timeouts reach the runner callback with stable codes`() {
        assertEquals("capture_timeout", faceErrorCode(UseSenseError.captureTimeout("capture", 1)).wire)
        assertEquals("upload_timeout", faceErrorCode(UseSenseError.uploadTimeout("uploading", 1)).wire)
        assertEquals("upload_timeout", faceErrorCode(UseSenseError.finalizationTimeout("uploading", true)).wire)
    }

    @Test
    fun `other capture failures keep provider_unavailable`() {
        assertEquals(FlowError.Code.PROVIDER_UNAVAILABLE, faceErrorCode(UseSenseError.networkError()))
        assertEquals(FlowError.Code.PROVIDER_UNAVAILABLE, faceErrorCode(UseSenseError.noFramesCaptured()))
    }
}
