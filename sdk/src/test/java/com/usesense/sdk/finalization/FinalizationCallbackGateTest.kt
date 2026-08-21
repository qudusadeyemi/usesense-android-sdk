package com.usesense.sdk.finalization

import org.junit.Assert.assertEquals
import org.junit.Test

class FinalizationCallbackGateTest {
    @Test
    fun `error retry success emits only success callback`() {
        val callbacks = mutableListOf<String>()
        val gate = FinalizationCallbackGate<String, String>(callbacks::add, { callbacks += "error:$it" }, { callbacks += "cancel" })

        gate.recovery()
        gate.success("success")

        assertEquals(listOf("success"), callbacks)
    }

    @Test
    fun `error exit emits one error callback`() {
        val callbacks = mutableListOf<String>()
        val gate = FinalizationCallbackGate<String, String>(callbacks::add, { callbacks += "error:$it" }, { callbacks += "cancel" })

        gate.recovery()
        gate.exit("network")
        gate.exit("network")

        assertEquals(listOf("error:network"), callbacks)
    }

    @Test
    fun `abandoning recovery emits exactly one cancellation callback`() {
        val callbacks = mutableListOf<String>()
        val gate = FinalizationCallbackGate<String, String>(callbacks::add, { callbacks += "error:$it" }, { callbacks += "cancel" })

        gate.recovery()
        gate.cancel()
        gate.cancel()
        gate.success("late-success")
        gate.exit("late-error")

        assertEquals(listOf("cancel"), callbacks)
    }
}
