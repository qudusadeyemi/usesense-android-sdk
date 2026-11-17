package com.usesense.sdk.devicecheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A headless "silent device check" next to another KYC provider's SDK
 * (MetaMap, Smile ID, Sumsub, ...). No UI, no camera, no permission prompts.
 * Create one with [com.usesense.sdk.UseSense.deviceCheck].
 *
 *  1. [start] before the provider's SDK opens: collects the camera-free
 *     DeepSense signals (Play Integrity bound to the check's nonce, emulator,
 *     root, debug, Play services, security patch, hardware keystore, device
 *     and session context, install id) and sends them. Send it once.
 *  2. Run the provider, passing the `deviceCheckId` in its metadata.
 *  3. [finish] (optional) after it closes: sends the camera inventory.
 *
 * Both return a [DeviceCheckResult] and never throw into the host app (only
 * the caller's own coroutine cancellation propagates). Work runs off the main
 * thread and is bounded by [DeviceCheckConfig.timeoutMs]. The device is told
 * only that the signals arrived: the verdict goes to your server.
 *
 * Calls are serialised: a [finish] issued while [start] is still running waits
 * for it. A second [start] after one was accepted is refused locally
 * ([DeviceCheckError.Code.OUT_OF_ORDER]) and nothing is sent.
 */
class DeviceCheck internal constructor(
    private val config: DeviceCheckConfig,
    private val signals: DeviceCheckSignalSource,
    private val transport: DeviceCheckTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val log: (String) -> Unit = {},
) {
    private val configError: DeviceCheckError? = DeviceCheckProtocol.validate(config)
    private val nonce = config.nonce.trim()
    private val mutex = Mutex()

    @Volatile private var startAccepted = false

    /** Owns the Java callback variants and the Play Integrity warm-up. */
    private val scope =
        CoroutineScope(
            SupervisorJob() + ioDispatcher + CoroutineExceptionHandler { _, e -> log("unexpected: ${e.javaClass.simpleName}") },
        )

    /** The `dc_` id, to pass into your KYC provider's metadata. */
    val deviceCheckId: String get() = config.deviceCheckId

    /** Collect the camera-free device signals and send them (phase `start`). */
    suspend fun start(): DeviceCheckResult =
        guarded(DeviceCheckPhase.START) {
            if (startAccepted) {
                return@guarded DeviceCheckResult.Failed(
                    DeviceCheckPhase.START,
                    DeviceCheckError(DeviceCheckError.Code.OUT_OF_ORDER, "start was already accepted for this device check; send it once"),
                )
            }
            val collected = signals.start(scope, nonce)
            val result = transport.send(DeviceCheckPhase.START, DeviceCheckProtocol.startBody(nonce, collected.payload, collected.client))
            if (result is DeviceCheckResult.Sent) startAccepted = true
            result
        }

    /** Read the camera inventory (no permission, no camera opened) and send it (phase `finish`). */
    suspend fun finish(): DeviceCheckResult =
        guarded(DeviceCheckPhase.FINISH) {
            val collected = signals.finish()
            transport.send(DeviceCheckPhase.FINISH, DeviceCheckProtocol.finishBody(nonce, collected.payload, collected.client))
        }

    /** Java and callback-style [start]; [callback] runs on the main thread. */
    fun start(callback: DeviceCheckCallback): Job = launchWithCallback(callback) { start() }

    /** Java and callback-style [finish]; [callback] runs on the main thread. */
    fun finish(callback: DeviceCheckCallback): Job = launchWithCallback(callback) { finish() }

    /**
     * Cancel anything still running. Callbacks of cancelled calls are not
     * invoked, and later calls return [DeviceCheckError.Code.CANCELLED].
     */
    fun cancel() {
        scope.cancel()
    }

    private fun launchWithCallback(
        callback: DeviceCheckCallback,
        block: suspend () -> DeviceCheckResult,
    ): Job =
        scope.launch {
            val result = block()
            withContext(callbackDispatcher) {
                try {
                    callback.onResult(result)
                } catch (e: Exception) {
                    // The host's own callback threw; keep it from crashing the SDK scope.
                    log("callback threw ${e.javaClass.simpleName}")
                }
            }
        }

    private suspend fun guarded(
        phase: DeviceCheckPhase,
        block: suspend () -> DeviceCheckResult,
    ): DeviceCheckResult {
        configError?.let { return DeviceCheckResult.Failed(phase, it) }
        if (!scope.isActive) {
            return DeviceCheckResult.Failed(phase, DeviceCheckError(DeviceCheckError.Code.CANCELLED, "This device check was cancelled"))
        }
        return mutex.withLock {
            val outcome =
                try {
                    withTimeoutOrNull(config.timeoutMs) { withContext(ioDispatcher) { block() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("${phase.wire} failed: ${e.javaClass.simpleName}")
                    return@withLock DeviceCheckResult.Failed(
                        phase,
                        DeviceCheckError(DeviceCheckError.Code.UNKNOWN, e.message ?: "Device check failed"),
                    )
                }
            outcome ?: DeviceCheckResult.Failed(
                phase,
                DeviceCheckError(DeviceCheckError.Code.TIMEOUT, "Device check ${phase.wire} did not finish within ${config.timeoutMs} ms"),
            )
        }
    }
}
