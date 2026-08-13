package com.usesense.sdk.finalization

import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.api.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

enum class FinalizationPhase { PREPARING, UPLOADING, COMPLETING }

enum class RecoveryAction { RETRY, RESTART, EXIT }

data class FinalizationPolicy(
    val preparationTimeoutMs: Long = 30_000,
    val uploadAllowanceMs: Long = 300_000,
    val noProgressTimeoutMs: Long = 30_000,
    val completionTimeoutMs: Long = 30_000,
    val progressCheckMs: Long = 250,
)

sealed interface FinalizationUpdate {
    data class Phase(val phase: FinalizationPhase) : FinalizationUpdate
    data class Progress(val bytesSent: Long, val bytesTotal: Long) : FinalizationUpdate
    data class Result(val result: UseSenseResult) : FinalizationUpdate
    data class Recovery(
        val phase: FinalizationPhase,
        val error: UseSenseError,
        val actions: Set<RecoveryAction>,
    ) : FinalizationUpdate
}

interface FinalizationOperations {
    suspend fun prepare(): Result<Unit>
    suspend fun upload(onProgress: (Long, Long) -> Unit): Result<Unit>
    suspend fun complete(): Result<UseSenseResult>
}

/** Owns the standard capture finalization pipeline. Payload ownership stays in the session. */
class FinalizationCoordinator(
    private val operations: FinalizationOperations,
    private val policy: FinalizationPolicy = FinalizationPolicy(),
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(onUpdate: (FinalizationUpdate) -> Unit) =
        run(FinalizationPhase.PREPARING, onUpdate)

    suspend fun run(
        startAt: FinalizationPhase,
        onUpdate: (FinalizationUpdate) -> Unit,
    ) {
        if (startAt == FinalizationPhase.PREPARING) {
            val prepared = bounded(FinalizationPhase.PREPARING, policy.preparationTimeoutMs, onUpdate) {
                operations.prepare()
            } ?: return
            if (prepared.isFailure) return recover(FinalizationPhase.PREPARING, prepared.exceptionOrNull(), onUpdate)
        }

        if (startAt != FinalizationPhase.COMPLETING) {
            onUpdate(FinalizationUpdate.Phase(FinalizationPhase.UPLOADING))
            val uploadFailure = monitorUpload(onUpdate)
            if (uploadFailure != null) return recover(FinalizationPhase.UPLOADING, uploadFailure, onUpdate)
        }

        val completed = bounded(FinalizationPhase.COMPLETING, policy.completionTimeoutMs, onUpdate) {
            operations.complete()
        } ?: return
        completed.fold(
            onSuccess = { onUpdate(FinalizationUpdate.Result(it)) },
            onFailure = { recover(FinalizationPhase.COMPLETING, it, onUpdate) },
        )
    }

    private suspend fun <T> bounded(
        phase: FinalizationPhase,
        timeoutMs: Long,
        onUpdate: (FinalizationUpdate) -> Unit,
        operation: suspend () -> Result<T>,
    ): Result<T>? {
        onUpdate(FinalizationUpdate.Phase(phase))
        val result = withTimeoutOrNull(timeoutMs) { runCatching { operation() }.getOrElse { Result.failure(it) } }
        if (result == null) recover(phase, FinalizationTimeout(phase, false), onUpdate)
        return result
    }

    private suspend fun monitorUpload(onUpdate: (FinalizationUpdate) -> Unit): Throwable? = coroutineScope {
        val startedAt = nowMs()
        val lastProgressAt = AtomicLong(startedAt)
        val furthestByte = AtomicLong(0L)
        val upload = async {
            runCatching {
                operations.upload { sent, total ->
                    if (sent > furthestByte.getAndAccumulate(sent, ::maxOf)) {
                        lastProgressAt.set(nowMs())
                    }
                    onUpdate(FinalizationUpdate.Progress(sent, total))
                }
            }.getOrElse { Result.failure(it) }
        }
        while (!upload.isCompleted) {
            pause(policy.progressCheckMs)
            val now = nowMs()
            val idleFor = now - lastProgressAt.get()
            if (now - startedAt >= policy.uploadAllowanceMs || idleFor >= policy.noProgressTimeoutMs) {
                upload.cancel()
                return@coroutineScope FinalizationTimeout(
                    FinalizationPhase.UPLOADING,
                    idleFor >= policy.noProgressTimeoutMs,
                )
            }
        }
        upload.await().exceptionOrNull()
    }

    private fun recover(
        phase: FinalizationPhase,
        cause: Throwable?,
        onUpdate: (FinalizationUpdate) -> Unit,
    ) {
        val error = when (cause) {
            is ApiException -> cause.useSenseError
            is FinalizationTimeout -> UseSenseError.finalizationTimeout(phase.name.lowercase(), cause.noProgress)
            else -> UseSenseError.networkError(cause?.message)
        }
        val actions = if (phase == FinalizationPhase.PREPARING || !error.isRetryable) {
            setOf(RecoveryAction.RESTART, RecoveryAction.EXIT)
        } else {
            setOf(RecoveryAction.RETRY, RecoveryAction.RESTART, RecoveryAction.EXIT)
        }
        onUpdate(FinalizationUpdate.Recovery(phase, error, actions))
    }
}

private class FinalizationTimeout(val phase: FinalizationPhase, val noProgress: Boolean) : Exception()
