package com.usesense.sdk.signals

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class DeviceSignalCollector(private val context: Context) {

    private var sensorManager: SensorManager? = null
    private val accelerometerData = mutableListOf<JSONObject>()
    private val gyroscopeData = mutableListOf<JSONObject>()
    private var sensorStartMs = 0L
    private var accelListener: SensorEventListener? = null
    private var gyroListener: SensorEventListener? = null

    private val playIntegrityManager = PlayIntegrityManager(context)
    private var playIntegrityToken: String? = null

    fun startSensorCollection() {
        sensorStartMs = SystemClock.elapsedRealtime()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelListener = object : SensorEventListener {
            private var lastSampleMs = 0L
            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSampleMs < 500) return // ~2Hz sampling
                lastSampleMs = now
                if (accelerometerData.size < 20) {
                    accelerometerData.add(JSONObject().apply {
                        put("t", now - sensorStartMs)
                        put("x", event.values[0].toDouble())
                        put("y", event.values[1].toDouble())
                        put("z", event.values[2].toDouble())
                    })
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        gyroListener = object : SensorEventListener {
            private var lastSampleMs = 0L
            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSampleMs < 500) return
                lastSampleMs = now
                if (gyroscopeData.size < 20) {
                    gyroscopeData.add(JSONObject().apply {
                        put("t", now - sensorStartMs)
                        put("x", event.values[0].toDouble())
                        put("y", event.values[1].toDouble())
                        put("z", event.values[2].toDouble())
                    })
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accel?.let { sensorManager?.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyro?.let { sensorManager?.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stopSensorCollection() {
        accelListener?.let { sensorManager?.unregisterListener(it) }
        gyroListener?.let { sensorManager?.unregisterListener(it) }
    }

    /**
     * Request a Play Integrity token bound to the session nonce.
     * Must be called before collectAndroidIntegrity().
     */
    suspend fun requestPlayIntegrityToken(nonce: String) {
        playIntegrityToken = playIntegrityManager.requestIntegrityToken(nonce)
    }

    /**
     * Collect Android-specific integrity signals per the spec's AndroidIntegritySignals structure.
     * This replaces web_integrity for Android platform.
     */
    fun collectAndroidIntegrity(): JSONObject {
        val integrity = JSONObject()

        integrity.put("is_emulator", isEmulator())
        integrity.put("is_rooted", isRooted())
        integrity.put("is_debuggable", isDebuggable())
        integrity.put("play_integrity_token", playIntegrityToken)
        integrity.put("package_name", context.packageName)
        integrity.put("signing_certificate_hash", getSigningCertificateHash())
        integrity.put("device_model", Build.MODEL)
        integrity.put("os_version", Build.VERSION.SDK_INT.toString())

        // Screen resolution
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        integrity.put("screen_resolution", "${metrics.widthPixels}x${metrics.heightPixels}")

        integrity.put("hardware_concurrency", Runtime.getRuntime().availableProcessors())

        val runtime = Runtime.getRuntime()
        integrity.put("total_memory_mb", runtime.maxMemory() / (1024 * 1024))

        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val battery = JSONObject()
            if (level >= 0 && scale > 0) {
                battery.put("level", level.toFloat() / scale)
            }
            battery.put("charging",
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
            integrity.put("battery", battery)
        }

        // Connection
        val connection = JSONObject()
        connection.put("type", getNetworkType())
        integrity.put("connection", connection)

        integrity.put("timezone", java.util.TimeZone.getDefault().id)
        val locale = java.util.Locale.getDefault()
        integrity.put("locale", "${locale.language}_${locale.country}")

        return integrity
    }

    /**
     * Collect legacy web_integrity signals (kept for backward compat with backend).
     * For new integrations, android_integrity is preferred.
     */
    fun collectSignals(): JSONObject {
        val signals = JSONObject()

        signals.put("platform", "android")
        signals.put("sdk_version", SDK_VERSION)
        signals.put("device_model", Build.MODEL)
        signals.put("device_manufacturer", Build.MANUFACTURER)
        signals.put("os_version", "Android ${Build.VERSION.RELEASE}")
        signals.put("api_level", Build.VERSION.SDK_INT)

        // Screen
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        signals.put("screen_width", metrics.widthPixels)
        signals.put("screen_height", metrics.heightPixels)
        signals.put("screen_density", metrics.densityDpi)

        // Camera
        signals.put("camera_facing", "front")
        signals.put("camera_resolution", "640x480")

        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                signals.put("battery_level", level.toFloat() / scale)
            }
            signals.put("battery_charging",
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
        }

        // Network
        signals.put("network_type", getNetworkType())

        // Locale/timezone
        val locale = java.util.Locale.getDefault()
        signals.put("locale", "${locale.language}-${locale.country}")
        val tz = java.util.TimeZone.getDefault()
        signals.put("timezone", tz.id)
        signals.put("timezone_offset", tz.rawOffset / 60000)

        // App info
        signals.put("app_package", context.packageName)

        // Device integrity
        signals.put("is_emulator", isEmulator())
        signals.put("is_rooted", isRooted())
        signals.put("is_debuggable", isDebuggable())
        signals.put("has_play_services", hasPlayServices())

        // Permissions
        signals.put("camera_permission_granted",
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        signals.put("microphone_permission_granted",
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)

        // Uptime
        signals.put("uptime_ms", SystemClock.elapsedRealtime())

        // Sensor data
        signals.put("accelerometer_data", JSONArray(accelerometerData))
        signals.put("gyroscope_data", JSONArray(gyroscopeData))

        return signals
    }

    fun collectDeviceTelemetry(): JSONObject {
        val telemetry = JSONObject()
        telemetry.put("cpu_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")

        val runtime = Runtime.getRuntime()
        telemetry.put("total_ram_mb", runtime.maxMemory() / (1024 * 1024))
        telemetry.put("available_ram_mb", runtime.freeMemory() / (1024 * 1024))

        val stat = android.os.StatFs(context.filesDir.absolutePath)
        telemetry.put("storage_available_mb", stat.availableBytes / (1024 * 1024))
        telemetry.put("security_patch", Build.VERSION.SECURITY_PATCH)

        return telemetry
    }

    private fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || "google_sdk" == Build.PRODUCT
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu"))
    }

    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/data/local/xbin/su", "/data/local/bin/su",
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        if (Build.TAGS?.contains("test-keys") == true) return true
        val pathEnv = System.getenv("PATH") ?: return false
        for (dir in pathEnv.split(":")) {
            if (File(dir, "su").exists()) return true
        }
        return false
    }

    private fun isDebuggable(): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun hasPlayServices(): Boolean {
        return try {
            Class.forName("com.google.android.gms.common.GoogleApiAvailability")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun getSigningCertificateHash(): String {
        return try {
            val signingInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            }
            if (signingInfo != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(signingInfo)
                digest.joinToString("") { "%02x".format(it) }
            } else {
                "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun release() {
        stopSensorCollection()
        accelerometerData.clear()
        gyroscopeData.clear()
    }

    companion object {
        const val SDK_VERSION = "1.17.7"
    }
}
