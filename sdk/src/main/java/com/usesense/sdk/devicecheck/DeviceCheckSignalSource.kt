package com.usesense.sdk.devicecheck

import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/** What a phase sends: channel_integrity (or the inventory) plus the diagnostics `client`. */
internal class CollectedSignals(val payload: JSONObject, val client: JSONObject)

/** Where a DeviceCheck gets its signals. Swappable in tests. */
internal interface DeviceCheckSignalSource {
    suspend fun start(
        scope: CoroutineScope,
        nonce: String,
    ): CollectedSignals

    suspend fun finish(): CollectedSignals
}
