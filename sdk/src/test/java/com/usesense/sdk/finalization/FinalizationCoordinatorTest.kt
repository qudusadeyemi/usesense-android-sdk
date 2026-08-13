package com.usesense.sdk.finalization

import com.usesense.sdk.UseSenseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizationCoordinatorTest {
    private val result = UseSenseResult("session", "enrollment", null, "APPROVE", "now")

    @Test
    fun `public callback observes all phases and result`() = runTest {
        val updates = mutableListOf<FinalizationUpdate>()
        FinalizationCoordinator(successfulOperations()).run(updates::add)

        assertEquals(
            listOf(FinalizationPhase.PREPARING, FinalizationPhase.UPLOADING, FinalizationPhase.COMPLETING),
            updates.filterIsInstance<FinalizationUpdate.Phase>().map { it.phase },
        )
        assertEquals(result, updates.filterIsInstance<FinalizationUpdate.Result>().single().result)
    }

    @Test
    fun `preparation stall requires restart or exit`() = runTest {
        val recovery = runWith(object : FinalizationOperations by successfulOperations() {
            override suspend fun prepare(): Result<Unit> {
                CompletableDeferred<Unit>().await()
                return Result.success(Unit)
            }
        }, FinalizationPolicy(preparationTimeoutMs = 10))
        assertEquals(setOf(RecoveryAction.RESTART, RecoveryAction.EXIT), recovery.actions)
    }

    @Test
    fun `upload without progress exposes retry restart and exit`() = runTest {
        var now = 0L
        val recovery = runWith(
            object : FinalizationOperations by successfulOperations() {
                override suspend fun upload(onProgress: (Long, Long) -> Unit): Result<Unit> {
                    CompletableDeferred<Unit>().await()
                    return Result.success(Unit)
                }
            },
            FinalizationPolicy(noProgressTimeoutMs = 30, uploadAllowanceMs = 300, progressCheckMs = 10),
            { now },
            { now += it },
        )
        assertTrue(RecoveryAction.RETRY in recovery.actions)
        assertTrue(recovery.error.message.contains("no upload progress"))
    }

    @Test
    fun `advancing upload may use full allowance`() = runTest {
        var now = 0L
        val updates = mutableListOf<FinalizationUpdate>()
        val operations = object : FinalizationOperations by successfulOperations() {
            override suspend fun upload(onProgress: (Long, Long) -> Unit): Result<Unit> {
                repeat(4) { onProgress((it + 1L) * 10, 50); now += 20; kotlinx.coroutines.yield() }
                return Result.success(Unit)
            }
        }
        FinalizationCoordinator(
            operations,
            FinalizationPolicy(noProgressTimeoutMs = 30, uploadAllowanceMs = 100, progressCheckMs = 1),
            { now },
            { kotlinx.coroutines.yield() },
        ).run(updates::add)
        assertFalse(updates.any { it is FinalizationUpdate.Recovery })
    }

    @Test
    fun `completion timeout is technical recovery not rejection`() = runTest {
        val recovery = runWith(object : FinalizationOperations by successfulOperations() {
            override suspend fun complete(): Result<UseSenseResult> {
                CompletableDeferred<Unit>().await()
                return Result.success(result)
            }
        }, FinalizationPolicy(completionTimeoutMs = 10))
        assertEquals(FinalizationPhase.COMPLETING, recovery.phase)
        assertTrue(RecoveryAction.RETRY in recovery.actions)
    }

    @Test
    fun `completion retry resumes completion without re-uploading`() = runTest {
        var uploads = 0
        val updates = mutableListOf<FinalizationUpdate>()
        val operations = object : FinalizationOperations by successfulOperations() {
            override suspend fun upload(onProgress: (Long, Long) -> Unit) =
                Result.success(Unit).also { uploads++ }
        }

        FinalizationCoordinator(operations).run(FinalizationPhase.COMPLETING, updates::add)

        assertEquals(0, uploads)
        assertEquals(result, updates.filterIsInstance<FinalizationUpdate.Result>().single().result)
    }

    private suspend fun runWith(
        operations: FinalizationOperations,
        policy: FinalizationPolicy,
        now: () -> Long = { 0 },
        pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): FinalizationUpdate.Recovery {
        val updates = mutableListOf<FinalizationUpdate>()
        FinalizationCoordinator(operations, policy, now, pause).run(updates::add)
        return updates.filterIsInstance<FinalizationUpdate.Recovery>().single()
    }

    private fun successfulOperations() = object : FinalizationOperations {
        override suspend fun prepare() = Result.success(Unit)
        override suspend fun upload(onProgress: (Long, Long) -> Unit) = Result.success(Unit)
        override suspend fun complete() = Result.success(result)
    }
}
