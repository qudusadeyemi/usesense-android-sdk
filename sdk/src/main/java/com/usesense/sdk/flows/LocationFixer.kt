package com.usesense.sdk.flows

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Acquires a single position fix for address capture, then stops.
 *
 * Uses the platform LocationManager rather than the fused provider from Google
 * Play services, deliberately. play-services-location is not currently a
 * dependency of this SDK, adding it would grow every integrator's APK, and it
 * would make address capture silently useless on devices without Play
 * services. Those are not an edge case in the markets this feature is built
 * for. The platform API is enough: we need a single dwelling-level fix, not
 * continuous navigation-grade tracking.
 *
 * Every failure path resolves rather than throwing, because the caller must
 * submit either way: a denied permission still advances the step with the
 * descriptors alone. There is no error to propagate, only a state to report.
 *
 * Foreground only. ACCESS_FINE_LOCATION is the only permission this needs and
 * nothing here asks for ACCESS_BACKGROUND_LOCATION. Passive home inference is
 * a separate, separately consented instrument.
 */
internal class LocationFixer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var timeout: Runnable? = null
    private var finished = false
    private var callback: ((LocationFix?, LocationCaptureState) -> Unit)? = null

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts acquisition. [timeoutMs] bounds the whole attempt so a subject on
     * a device that never resolves a fix is not held indefinitely.
     */
    fun start(timeoutMs: Int, onResult: (LocationFix?, LocationCaptureState) -> Unit) {
        callback = onResult

        if (!hasPermission()) {
            finish(null, LocationCaptureState.DENIED)
            return
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            finish(null, LocationCaptureState.UNAVAILABLE)
            return
        }

        val provider = bestProvider(manager)
        if (provider == null) {
            // Location switched off at the system level, or no provider
            // enabled. Recoverable: the subject continues with descriptors.
            finish(null, LocationCaptureState.UNAVAILABLE)
            return
        }

        val work = Runnable { finish(null, LocationCaptureState.UNAVAILABLE) }
        timeout = work
        handler.postDelayed(work, timeoutMs.toLong())

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(fixFrom(location), LocationCaptureState.READY)
            }

            // Required on API 28 and 29; removed in later platform versions.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderDisabled(provider: String) {
                finish(null, LocationCaptureState.UNAVAILABLE)
            }

            override fun onProviderEnabled(provider: String) = Unit
        }
        listener = l

        try {
            manager.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked between the check above and here.
            finish(null, LocationCaptureState.DENIED)
        } catch (_: IllegalArgumentException) {
            finish(null, LocationCaptureState.UNAVAILABLE)
        }
    }

    fun cancel() {
        finish(null, LocationCaptureState.UNAVAILABLE)
    }

    private fun bestProvider(manager: LocationManager): String? {
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return candidates.firstOrNull { p ->
            runCatching { manager.isProviderEnabled(p) }.getOrDefault(false)
        }
    }

    /**
     * Turns a platform reading into a fix.
     *
     * A mocked location is submitted with `attested = false` rather than
     * rejected. It records a weaker evidence class, and rejecting it outright
     * would push honest subjects on rooted or unusual handsets off the ladder
     * entirely, which the capture strategy explicitly forbids.
     */
    private fun fixFrom(location: Location): LocationFix? {
        if (!LocationCapture.isUsableFix(location.latitude, location.longitude)) return null
        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        return LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = accuracy,
            attested = !mocked,
        )
    }

    private fun finish(fix: LocationFix?, state: LocationCaptureState) {
        if (finished) return
        finished = true
        timeout?.let(handler::removeCallbacks)
        timeout = null
        listener?.let { l ->
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            runCatching { manager?.removeUpdates(l) }
        }
        listener = null
        val done = callback
        callback = null
        handler.post { done?.invoke(fix, state) }
    }
}
