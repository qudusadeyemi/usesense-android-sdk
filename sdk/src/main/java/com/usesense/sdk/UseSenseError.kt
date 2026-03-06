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

        fun cameraUnavailable() = UseSenseError(
            code = CAMERA_UNAVAILABLE,
            message = "Front camera is not available on this device",
        )

        fun cameraPermissionDenied() = UseSenseError(
            code = CAMERA_PERMISSION_DENIED,
            message = "Camera permission was not granted",
        )

        fun microphonePermissionDenied() = UseSenseError(
            code = MICROPHONE_PERMISSION_DENIED,
            message = "Microphone permission was not granted",
        )

        fun networkError(cause: String? = null) = UseSenseError(
            code = NETWORK_ERROR,
            message = cause ?: "Network request failed",
            isRetryable = true,
        )

        fun networkTimeout() = UseSenseError(
            code = NETWORK_TIMEOUT,
            message = "Network request timed out",
            isRetryable = true,
        )

        fun sessionExpired() = UseSenseError(
            code = SESSION_EXPIRED,
            message = "Session has expired",
        )

        fun uploadFailed() = UseSenseError(
            code = UPLOAD_FAILED,
            message = "Signal upload failed after retries",
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

        fun fromServerError(httpStatus: Int, serverCode: String?, message: String?): UseSenseError {
            val retryable = httpStatus == 500
            return UseSenseError(
                code = httpStatus,
                serverCode = serverCode,
                message = message ?: "Server error",
                isRetryable = retryable,
            )
        }
    }
}
