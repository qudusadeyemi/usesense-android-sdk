package com.usesense.sdk.ui

import android.content.Context
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** A4: remote enrolment / verification report their outcome, as iOS onComplete. */
class RemoteFlowCallbackTest {
    private val context = mockk<Context>(relaxed = true)

    @Before fun setUp() {
        CaptureLaunches.clearForTest()
        every { context.applicationContext } returns context
        UseSense.initialize(context, UseSenseConfig(apiKey = "pk_sandbox_x"))
    }

    @After fun tearDown() {
        CaptureLaunches.clearForTest()
        UseSense.reset()
    }

    private class Recorder : UseSenseCallback {
        val events = mutableListOf<String>()
        override fun onSuccess(result: UseSenseResult) { events += "success:${result.decision}" }
        override fun onError(error: UseSenseError) { events += "error:${error.code}" }
        override fun onCancelled() { events += "cancelled" }
    }

    @Test
    fun `remote enrolment carries the callback to the screen`() {
        val cb = Recorder()
        UseSense.startRemoteEnrollment(context, "enr_1", cb)
        val launch = CaptureLaunches.allForTest().single()
        assertEquals(CaptureLaunch.Kind.REMOTE_ENROLLMENT, launch.kind)
        assertEquals("enr_1", launch.remoteId)
        assertSame(cb, launch.callback)
        // The screen's single delivery reaches it once.
        launch.gate.success(UseSenseResult("ses", "enrollment", "idn", UseSenseResult.DECISION_APPROVE, "t"))
        launch.gate.cancel()
        assertEquals(listOf("success:APPROVE"), cb.events)
    }

    @Test
    fun `remote verification carries the callback, and a dispute is a decision`() {
        val cb = Recorder()
        UseSense.startRemoteVerification(context, "rs_1", cb)
        val launch = CaptureLaunches.allForTest().single()
        assertEquals(CaptureLaunch.Kind.REMOTE_VERIFICATION, launch.kind)
        launch.gate.success(UseSenseResult("rs_1", "authentication", null, UseSenseResult.DECISION_DISPUTED, "t"))
        assertEquals(listOf("success:DISPUTED"), cb.events)
    }

    @Test
    fun `the old two argument forms still launch, with no callback`() {
        UseSense.startRemoteEnrollment(context, "enr_1")
        UseSense.startRemoteVerification(context, "rs_1")
        verify(exactly = 2) { context.startActivity(any()) }
        CaptureLaunches.allForTest().forEach { assertNull(it.callback) }
        // Nothing to deliver to: the gate is a no-op, not a crash.
        CaptureLaunches.allForTest().forEach { it.gate.cancel() }
    }

    @Test
    fun `a blank id fails through the callback without launching`() {
        val cb = Recorder()
        UseSense.startRemoteVerification(context, " ", cb)
        assertEquals(listOf("error:${UseSenseError.INVALID_CONFIG}"), cb.events)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `leaving before the end is a cancellation`() {
        val cb = Recorder()
        UseSense.startRemoteEnrollment(context, "enr_1", cb)
        CaptureLaunches.allForTest().single().gate.cancel()
        assertEquals(listOf("cancelled"), cb.events)
    }
}
