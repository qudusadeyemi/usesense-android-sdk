package com.usesense.sdk

data class UseSenseError(
    val code: Int,
    val serverCode: String? = null,
    val message: String,
    val isRetryable: Boolean = false,
    val details: Map<String, Any>? = null,
) {
    companion object {
        // Client-side error codes
        const val CAMERA_UNAVAILABLE = 1001
        const val CAMERA_PERMISSION_DENIED = 1002
        const val MICROPHONE_PERMISSION_DENIED = 1003
        const val NETWORK_ERROR = 2001
        const val NETWORK_TIMEOUT = 2002
        const val SESSION_EXPIRED = 3001
        const val UPLOAD_FAILED = 3002
        const val CAPTURE_FAILED = 4001
        const val ENCODING_FAILED = 4002
        const val INVALID_CONFIG = 5001
        const val QUOTA_EXCEEDED = 6001
        const val FINALIZATION_TIMEOUT = 7001

        /** The capture (camera open through the last frame) did not finish in time. */
        const val CAPTURE_TIMEOUT = 7002

        /** Packaging, upload or result did not finish in time. */
        const val UPLOAD_TIMEOUT = 7003

        /** Stable string codes, carried in [serverCode] for the timeout errors. */
        const val CODE_CAPTURE_TIMEOUT = "CAPTURE_TIMEOUT"
        const val CODE_UPLOAD_TIMEOUT = "UPLOAD_TIMEOUT"
        const val CODE_NO_FRAMES = "NO_FRAMES_CAPTURED"

        fun cameraUnavailable() = UseSenseError(
            code = CAMERA_UNAVAILABLE,
            message = "Front camera is not available on this device",
        )

        fun cameraPermissionDenied() = UseSenseError(
            code = CAMERA_PERMISSION_DENIED,
            message = "We need camera access to verify your identity. Please allow camera access in Settings.",
        )

        fun microphonePermissionDenied() = UseSenseError(
            code = MICROPHONE_PERMISSION_DENIED,
            message = "We need microphone access to complete verification. Please allow microphone access in Settings.",
        )

        fun networkError(cause: String? = null) = UseSenseError(
            code = NETWORK_ERROR,
            message = cause ?: "Connection issue. Please check your internet and try again.",
            isRetryable = true,
        )

        fun networkTimeout() = UseSenseError(
            code = NETWORK_TIMEOUT,
            message = "Request timed out. Please try again.",
            isRetryable = true,
        )

        fun sessionExpired() = UseSenseError(
            code = SESSION_EXPIRED,
            message = "Your session has expired. Please start over.",
        )

        fun uploadFailed() = UseSenseError(
            code = UPLOAD_FAILED,
            message = "Signal upload failed after retries",
            isRetryable = true,
        )

        fun finalizationTimeout(phase: String, noProgress: Boolean) = UseSenseError(
            code = FINALIZATION_TIMEOUT,
            message = if (noProgress) {
                "Finalization paused because there was no upload progress. Please try again."
            } else {
                "Finalization timed out during $phase. Please try again."
            },
            isRetryable = phase != "preparing",
            details = mapOf("phase" to phase, "no_progress" to noProgress),
        )

        /**
         * The session watchdog fired while the camera was opening or frames were
         * being captured. Retryable: "Try again" starts a clean attempt.
         */
        fun captureTimeout(phase: String, elapsedMs: Long) = UseSenseError(
            code = CAPTURE_TIMEOUT,
            serverCode = CODE_CAPTURE_TIMEOUT,
            message = "The camera stopped responding. Please try again.",
            isRetryable = true,
            details = mapOf("phase" to phase, "elapsed_ms" to elapsedMs),
        )

        /** The session watchdog fired while the capture was being packaged, uploaded or scored. */
        fun uploadTimeout(phase: String, elapsedMs: Long) = UseSenseError(
            code = UPLOAD_TIMEOUT,
            serverCode = CODE_UPLOAD_TIMEOUT,
            message = "We could not finish sending your capture. Please try again.",
            isRetryable = true,
            details = mapOf("phase" to phase, "elapsed_ms" to elapsedMs),
        )

        /** The camera ran but produced no frames: a system failure, never a verdict. */
        fun noFramesCaptured() = UseSenseError(
            code = CAPTURE_FAILED,
            serverCode = CODE_NO_FRAMES,
            message = "The camera did not return any images. Please try again.",
            isRetryable = true,
        )

        fun captureFailed(cause: String? = null) = UseSenseError(
            code = CAPTURE_FAILED,
            message = cause ?: "Frame capture failed",
        )

        fun encodingFailed() = UseSenseError(
            code = ENCODING_FAILED,
            message = "JPEG frame encoding failed",
        )

        fun invalidConfig(detail: String) = UseSenseError(
            code = INVALID_CONFIG,
            message = "Invalid configuration: $detail",
        )

        fun quotaExceeded() = UseSenseError(
            code = QUOTA_EXCEEDED,
            serverCode = "QUOTA_EXCEEDED",
            message = "Rate limit reached. Please try again later.",
        )

        fun fromServerError(httpStatus: Int, serverCode: String?, message: String?): UseSenseError {
            val userMessage = message ?: when (httpStatus) {
                400 -> "Invalid request. Please check the parameters."
                401 -> when (serverCode) {
                    "session_expired" -> "Your session has expired. Please start over."
                    "invalid_token" -> "Session token is invalid."
                    else -> "Authentication failed. Check API key."
                }
                404 -> when (serverCode) {
                    "identity_not_found" -> "Identity not found."
                    else -> "Endpoint not found. Verify Backend URL."
                }
                429 -> "Rate limit reached. Try again later."
                500 -> "Server error. Please try again."
                503 -> "Service unavailable. Try again later."
                else -> "Server error"
            }

            val retryable = httpStatus in setOf(500, 503, 429)
            return UseSenseError(
                code = httpStatus,
                serverCode = serverCode,
                message = userMessage,
                isRetryable = retryable,
            )
        }
    }
}
