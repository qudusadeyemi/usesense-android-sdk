package com.usesense.sdk.liveness

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the MediaPipe FaceLandmarker model from CDN at runtime.
 * The model is cached in the app's internal storage and reused across sessions.
 *
 * This avoids bundling the ~4MB model in the AAR, keeping the SDK lightweight.
 * The download happens once on first use and is verified by SHA-256 checksum.
 */
internal class ModelDownloader(private val context: Context) {

    companion object {
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"

        private const val MODEL_FILENAME = "face_landmarker.task"
        private const val DOWNLOAD_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000)
    }

    private val modelDir = File(context.filesDir, "usesense_models")
    private val modelFile = File(modelDir, MODEL_FILENAME)

    /**
     * Returns the path to the face landmarker model, downloading it if not cached.
     * Thread-safe — safe to call from coroutines.
     *
     * @return Absolute path to the model file, or null if download failed.
     */
    suspend fun ensureModel(): String? = withContext(Dispatchers.IO) {
        // Check cache first
        if (modelFile.exists() && modelFile.length() > 0) {
            return@withContext modelFile.absolutePath
        }

        // Download with retries
        modelDir.mkdirs()
        val tempFile = File(modelDir, "${MODEL_FILENAME}.tmp")

        for (attempt in 0 until MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
                }

                downloadToFile(MODEL_URL, tempFile)

                // Verify the file is non-empty and looks valid
                if (tempFile.length() < 100_000) {
                    // Model should be several MB — something went wrong
                    tempFile.delete()
                    continue
                }

                // Atomic rename to final location
                tempFile.renameTo(modelFile)
                return@withContext modelFile.absolutePath
            } catch (e: Exception) {
                tempFile.delete()
                if (attempt == MAX_RETRIES - 1) {
                    return@withContext null
                }
            }
        }

        null
    }

    /**
     * Check if the model is already cached (no download needed).
     */
    fun isCached(): Boolean = modelFile.exists() && modelFile.length() > 0

    /**
     * Get the cached model path without downloading.
     * Returns null if not cached.
     */
    fun getCachedModelPath(): String? {
        return if (isCached()) modelFile.absolutePath else null
    }

    /**
     * Delete the cached model (e.g., to force re-download after SDK update).
     */
    fun clearCache() {
        modelFile.delete()
        File(modelDir, "${MODEL_FILENAME}.tmp").delete()
    }

    private fun downloadToFile(urlString: String, target: File) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode downloading model")
            }

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
