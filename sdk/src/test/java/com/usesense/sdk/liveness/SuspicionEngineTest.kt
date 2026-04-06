package com.usesense.sdk.liveness

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SuspicionEngineTest {

    private lateinit var engine: SuspicionEngine

    @Before
    fun setup() {
        engine = SuspicionEngine(suspicionThreshold = 55)
    }

    @Test
    fun `starts with zero score`() {
        assertEquals(0, engine.currentScore)
        assertFalse(engine.triggered)
    }

    @Test
    fun `does not trigger with insufficient frames`() {
        // Only analyze 2 frames — not enough for reliable scoring
        engine.analyzeFrame(HeadPose(0.0, 0.0, 0.0), 100.0, 50.0, 1000)
        engine.analyzeFrame(HeadPose(0.0, 0.0, 0.0), 100.0, 50.0, 1100)

        assertFalse(engine.triggered)
    }

    @Test
    fun `stable pose produces high suspicion`() {
        // Simulate screen: perfectly stable pose, stable brightness
        for (i in 0 until 30) {
            engine.analyzeFrame(
                HeadPose(yaw = 0.0, pitch = 0.0, roll = 0.0),
                luminance = 80.0,
                sharpness = 40.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        // Stable pose + stable brightness + low sharpness = screen-like
        assertTrue(engine.currentScore > 40)
    }

    @Test
    fun `natural jitter produces low suspicion`() {
        val random = java.util.Random(42)

        for (i in 0 until 30) {
            engine.analyzeFrame(
                HeadPose(
                    yaw = random.nextGaussian() * 0.5,
                    pitch = random.nextGaussian() * 0.3,
                    roll = random.nextGaussian() * 0.2,
                ),
                luminance = 100.0 + random.nextGaussian() * 10.0,
                sharpness = 60.0 + random.nextGaussian() * 5.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        // Natural jitter + varying brightness + good sharpness = live
        assertTrue(engine.currentScore < 55)
    }

    @Test
    fun `only analyzes every 2nd frame`() {
        // Feed 8 frames: frameCounter goes 1-8, analyzed on even (2,4,6,8) = 4 analyzed
        // Snapshot produced after framesAnalyzed >= 3 (i.e., from the 3rd analyzed frame onward)
        for (i in 0 until 8) {
            engine.analyzeFrame(
                HeadPose(0.0, 0.0, 0.0), 100.0, 50.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        val snapshot = engine.getSnapshot()
        assertNotNull(snapshot)
        // 8 frames fed, every 2nd analyzed = 4
        assertEquals(4, snapshot!!.framesAnalyzed)
    }

    @Test
    fun `snapshot includes all four signals`() {
        for (i in 0 until 20) {
            engine.analyzeFrame(
                HeadPose(i * 0.1, 0.0, 0.0), 100.0, 50.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        val snapshot = engine.getSnapshot()
        assertNotNull(snapshot)
        assertEquals(4, snapshot!!.signals.size)

        val signalNames = snapshot.signals.map { it.name }.toSet()
        assertTrue(signalNames.contains("micro_tremor"))
        assertTrue(signalNames.contains("temporal_smoothness"))
        assertTrue(signalNames.contains("brightness_stability"))
        assertTrue(signalNames.contains("sharpness_pattern"))
    }

    @Test
    fun `signal weights sum to 1`() {
        for (i in 0 until 10) {
            engine.analyzeFrame(
                HeadPose(0.0, 0.0, 0.0), 100.0, 50.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        val snapshot = engine.getSnapshot()!!
        val totalWeight = snapshot.signals.sumOf { it.weight }
        assertEquals(1.0, totalWeight, 0.001)
    }

    @Test
    fun `reset clears all state`() {
        for (i in 0 until 20) {
            engine.analyzeFrame(
                HeadPose(0.0, 0.0, 0.0), 100.0, 50.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        engine.reset()
        assertEquals(0, engine.currentScore)
        assertFalse(engine.triggered)
        assertNull(engine.getSnapshot())
    }

    @Test
    fun `reliable flag is false below 6 frames analyzed`() {
        for (i in 0 until 8) {
            engine.analyzeFrame(
                HeadPose(0.0, 0.0, 0.0), 100.0, 50.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        val snapshot = engine.getSnapshot()
        assertNotNull(snapshot)
        // 8 frames fed, every 2nd analyzed = 4 < 6
        assertFalse(snapshot!!.reliable)
    }

    @Test
    fun `reliable flag is true at 6 or more frames analyzed`() {
        for (i in 0 until 20) {
            engine.analyzeFrame(
                HeadPose(0.0, 0.0, 0.0), 100.0, 50.0,
                timestampMs = (1000 + i * 100).toLong(),
            )
        }

        val snapshot = engine.getSnapshot()
        assertNotNull(snapshot)
        // 20 frames fed, every 2nd analyzed = 10 >= 6
        assertTrue(snapshot!!.reliable)
    }
}
