package com.usesense.sdk.capture.scan

/**
 * COVER-v1 "Fill light and the change watch", rule 1: the fill light is
 * decided before a scan starts and frozen until it ends (finish, drop or
 * cancel). Turning it on mid-scan lit the face unevenly behind the change
 * watch's baseline and read as a covered face (A03: nose at 2.7-3x the limit
 * for 8 s, segmenter body_skin 0.00).
 */
internal class FillLightLatch {
    var scanning = false
        private set

    fun scanStarted() { scanning = true }

    /** The scan finished, dropped back to align, or was cancelled. */
    fun scanEnded() { scanning = false }

    /** Whether the fill light may be switched now. */
    fun mayToggle(): Boolean = !scanning
}
