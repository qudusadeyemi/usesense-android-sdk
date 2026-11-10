package com.usesense.sdk.internal

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A session's `expires_at` (ISO 8601, any fraction length, `Z` or an offset)
 * against the device clock. Pure, so it is unit tested.
 */
internal object SessionExpiry {
    /** Left on the session when the capture starts: enough to scan, check and upload. */
    const val START_MARGIN_MS = 2 * 60_000L

    /** Epoch ms, or null when the value can't be read (then nothing is assumed). */
    fun parse(expiresAt: String?): Long? {
        val s = expiresAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        // No offset: the server's times are UTC.
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    /** True when the session has ended or ends within [marginMs] of [nowMs]. */
    fun endsWithin(
        expiresAt: String?,
        nowMs: Long,
        marginMs: Long = START_MARGIN_MS,
    ): Boolean {
        val end = parse(expiresAt) ?: return false
        return end - nowMs <= marginMs
    }
}
