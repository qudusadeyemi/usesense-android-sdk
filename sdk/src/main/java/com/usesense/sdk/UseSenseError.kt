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
