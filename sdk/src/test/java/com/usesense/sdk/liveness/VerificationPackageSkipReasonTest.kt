package com.usesense.sdk.liveness

import org.junit.Assert.*
import org.junit.Test

class VerificationPackageSkipReasonTest {

    private fun resolve(
        hasGeometricCoherenceConfig: Boolean = true,
        meshRequested: Boolean = true,
        initResult: FaceMeshInitResult = FaceMeshInitResult.SUCCESS,
        framesProcessed: Int = 10,
        meshFrames: Int = 10,
        fittedFrames: Int = 10,
    ) = VerificationPackageSkipReason.resolve(
        hasGeometricCoherenceConfig = hasGeometricCoherenceConfig,
        meshRequested = meshRequested,
        initResult = initResult,
        framesProcessed = framesProcessed,
        meshFrames = meshFrames,
        fittedFrames = fittedFrames,
    )

    @Test
    fun `mesh is requested by either dual path or on-device 3DMM`() {
        assertTrue(VerificationPackageSkipReason.meshRequested(true, false))
        assertTrue(VerificationPackageSkipReason.meshRequested(false, true))
        assertFalse(VerificationPackageSkipReason.meshRequested(false, false))
        assertFalse(VerificationPackageSkipReason.meshRequested(null, null))
    }

    @Test
    fun `a healthy mesh pipeline builds the package`() {
        assertNull(resolve())
        assertNull(resolve(fittedFrames = VerificationPackageSkipReason.MIN_MESH_FRAMES))
    }

    @Test
    fun `missing geometric coherence config`() {
        assertEquals(VerificationPackageSkipReason.NO_GC_CONFIG, resolve(hasGeometricCoherenceConfig = false))
    }

    @Test
    fun `server did not ask for mesh`() {
        assertEquals(VerificationPackageSkipReason.MESH_NOT_REQUESTED, resolve(meshRequested = false))
    }

    @Test
    fun `landmarker was never started, is still loading, or failed`() {
        assertEquals(
            VerificationPackageSkipReason.LANDMARKER_NOT_STARTED,
            resolve(initResult = FaceMeshInitResult.NOT_ATTEMPTED),
        )
        assertEquals(
            VerificationPackageSkipReason.LANDMARKER_INIT_PENDING,
            resolve(initResult = FaceMeshInitResult.PENDING),
        )
        assertEquals(
            VerificationPackageSkipReason.LANDMARKER_INIT_FAILED,
            resolve(initResult = FaceMeshInitResult.FAILED),
        )
    }

    @Test
    fun `no frames reached the landmarker`() {
        assertEquals(
            VerificationPackageSkipReason.NO_FRAMES_PROCESSED,
            resolve(framesProcessed = 0, meshFrames = 0, fittedFrames = 0),
        )
    }

    @Test
    fun `frames were processed but no face was found`() {
        assertEquals(
            VerificationPackageSkipReason.NO_FACE_DETECTED,
            resolve(meshFrames = 0, fittedFrames = 0),
        )
    }

    @Test
    fun `fewer fitted frames than the server minimum are withheld`() {
        assertEquals(
            VerificationPackageSkipReason.INSUFFICIENT_MESH_FRAMES,
            resolve(meshFrames = 2, fittedFrames = VerificationPackageSkipReason.MIN_MESH_FRAMES - 1),
        )
    }

    @Test
    fun `init result values match the server contract`() {
        assertEquals("not_attempted", FaceMeshInitResult.NOT_ATTEMPTED.value)
        assertEquals("pending", FaceMeshInitResult.PENDING.value)
        assertEquals("success", FaceMeshInitResult.SUCCESS.value)
        assertEquals("failed", FaceMeshInitResult.FAILED.value)
    }
}
