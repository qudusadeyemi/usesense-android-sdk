package com.usesense.sdk.flows

/**
 * Uniform error taxonomy for the Flows runner, mirroring the web SDK's
 * FlowError and the iOS SDK's FlowError struct. See `guides/flows/errors`
 * in the API docs for the per-code recovery patterns host apps should
 * implement.
 */
class FlowError(
    val code: Code,
    message: String,
    /** Server-side error code passed through verbatim when present. */
    val serverCode: String? = null,
) : RuntimeException(message) {
    enum class Code(val wire: String) {
        TOKEN_EXPIRED("token_expired"),
        TOKEN_INVALID("token_invalid"),
        NETWORK_UNAVAILABLE("network_unavailable"),
        PERMISSION_DENIED("permission_denied"),
        PROVIDER_UNAVAILABLE("provider_unavailable"),
        CANCELLED("cancelled"),
        UNSUPPORTED_ACTION("unsupported_action"),
        UNKNOWN("unknown");
    }

    override fun toString(): String = "FlowError(${code.wire}): $message"
}
