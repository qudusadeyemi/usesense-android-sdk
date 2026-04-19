package com.usesense.sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ZoomMotionController. Phase 1 ticket A-1.
 */
class ZoomMotionControllerTest {

    private fun bbox(scale: Float): FaceBoundingBox {
        val baseW = 240f
        val baseH = 320f
        return FaceBoundingBox(640f, 360f, baseW * scale, baseH * scale)
    }

    private fun pose(yaw: Float = 0f, pitch: Float = 0f) =
        HeadPoseDegrees(yaw, pitch, 0f)

    @Test
    fun validZoomCompletes() {
        val ctrl = ZoomMotionController()
        ctrl.start()

        for (i in 0 until 45) {
            val t = 100L + i * 33L
            val scale = 1.0f + 0.4f * i.toFloat() / 44f
            ctrl.observe(ZoomObservation(t, bbox(scale), pose()))
            if (ctrl.state() == ZoomState.COMPLETE || ctrl.state() == ZoomState.FAILED) break
        }
        for (i in 0 until 10) {
            val t = 100L + 45L * 33L + i * 33L
            ctrl.observe(ZoomObservation(t, bbox(1.4f), pose()))
            if (ctrl.state() == ZoomState.COMPLETE || ctrl.state() == ZoomState.FAILED) break
        }
        assertEquals(ZoomState.COMPLETE, ctrl.state())
    }

    @Test
    fun noMotionFails() {
        val ctrl = ZoomMotionController()
        ctrl.start()
        for (i in 0 until 60) {
            ctrl.observe(ZoomObservation(100L + i * 33L, bbox(1.0f), pose()))
        }
        assertEquals(ZoomState.FAILED, ctrl.state())
    }

    @Test
    fun headTurnFails() {
        val failures = mutableListOf<ZoomFailureReason?>()
        val ctrl = ZoomMotionController()
        ctrl.addListener { _, _, _, failure -> failures.add(failure) }
        ctrl.start()
        val stream = listOf(
            Triple(100L, 1.0f, 0f),
            Triple(133L, 1.05f, 5f),
            Triple(166L, 1.1f, 12f),
            Triple(200L, 1.15f, 20f)
        )
        for ((t, s, yaw) in stream) {
            ctrl.observe(ZoomObservation(t, bbox(s), pose(yaw = yaw)))
            if (ctrl.state() == ZoomState.FAILED) break
        }
        assertEquals(ZoomState.FAILED, ctrl.state())
        assertTrue("expected HEAD_TURN in $failures",
            failures.any { it == ZoomFailureReason.HEAD_TURN })
    }

    @Test
    fun timeoutShortBudget() {
        val ctrl = ZoomMotionController(ZoomMotionConfig(timeoutMs = 500, noMotionGraceMs = 2000))
        ctrl.start()
        for (i in 0 until 40) {
            val t = 100L + i * 25L
            ctrl.observe(ZoomObservation(t, bbox(1.0f + 0.01f * i), pose()))
        }
        assertEquals(ZoomState.FAILED, ctrl.state())
    }

    @Test
    fun statsPeakHeadPose() {
        val ctrl = ZoomMotionController()
        ctrl.start()
        ctrl.observe(ZoomObservation(100, bbox(1.0f), pose(yaw = 3f, pitch = -2f)))
        ctrl.observe(ZoomObservation(200, bbox(1.1f), pose(yaw = 7f, pitch = -5f)))
        ctrl.observe(ZoomObservation(300, bbox(1.2f), pose(yaw = -4f, pitch = 6f)))
        val s = ctrl.stats()
        assertEquals(7f, s.maxHeadYawAbsDeg, 0.001f)
        assertEquals(6f, s.maxHeadPitchAbsDeg, 0.001f)
    }
}
