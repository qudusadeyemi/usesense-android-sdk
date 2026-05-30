package com.usesense.sdk.flows

import org.json.JSONException
import org.json.JSONObject

/** Server-driven Flow Run state. */
enum class FlowRunState(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    STALLED("stalled"),
    AWAITING_REVIEW("awaiting_review"),
    COMPLETED("completed"),
    ERRORED("errored"),
    ABANDONED("abandoned"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(s: String): FlowRunState = values().firstOrNull { it.wire == s } ?: PENDING
    }
}

enum class FlowOutcome(val wire: String) {
    APPROVE("APPROVE"),
    REJECT("REJECT"),
    MANUAL_REVIEW("MANUAL_REVIEW");

    companion object {
        fun fromWire(s: String?): FlowOutcome? = values().firstOrNull { it.wire == s }
    }
}

/**
 * The action the server parked at. The runner reads it and renders the
 * matching native surface; unknown kinds surface as FlowError.UNSUPPORTED_ACTION.
 * See `guides/flows/action-contract` in the API docs.
 */
sealed class PendingAction {
    data class CaptureFace(val toolId: String?) : PendingAction()
    data class CaptureDocument(
        val category: String,
        val documentTypes: List<String>,
        val issuingCountries: List<String>,
    ) : PendingAction()
    data class CaptureForm(val fields: List<String>) : PendingAction()
    data class RedirectToConsent(val consentUrl: String) : PendingAction()

    companion object {
        /**
         * Decodes the wire format; throws FlowError.UNSUPPORTED_ACTION for any
         * kind the SDK does not implement so a version-skewed runner fails
         * loud instead of guessing.
         */
        fun decode(raw: JSONObject): PendingAction {
            val kind = raw.optString("kind", "")
            return when (kind) {
                "capture" -> when (val capture = raw.optString("capture", "")) {
                    "face" -> CaptureFace(toolId = raw.opt("toolId") as? String)
                    "document" -> CaptureDocument(
                        category = raw.optString("documentCategory", "identity"),
                        documentTypes = raw.optJSONArray("documentTypes").toStringList(),
                        issuingCountries = raw.optJSONArray("issuingCountries").toStringList(),
                    )
                    "form" -> CaptureForm(fields = raw.optJSONArray("fields").toStringList())
                    else -> throw FlowError(FlowError.Code.UNSUPPORTED_ACTION, "Unknown capture variant: $capture")
                }
                "redirect_to_consent" -> RedirectToConsent(consentUrl = raw.optString("consentUrl"))
                else -> throw FlowError(FlowError.Code.UNSUPPORTED_ACTION, "Unknown pendingAction.kind: $kind")
            }
        }
    }
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

/** Server-shaped view returned by GET / POST advance. */
data class FlowRunView(
    val id: String,
    val state: FlowRunState,
    val outcome: FlowOutcome?,
    val cursorStepId: String?,
    val environment: String,
    val pendingAction: PendingAction?,
    val branding: Branding?,
) {
    data class Branding(
        val displayName: String,
        val logoUrl: String?,
        val primaryColor: String,
        val redirectUrl: String?,
    )

    companion object {
        fun decode(json: JSONObject): FlowRunView {
            val run = json.optJSONObject("flowRun")
                ?: throw FlowError(FlowError.Code.UNKNOWN, "Malformed flow run view")
            val pendingActionJson = run.optJSONObject("pendingAction")
            val brandingJson = json.optJSONObject("branding")
            return FlowRunView(
                id = run.getString("id"),
                state = FlowRunState.fromWire(run.optString("state")),
                outcome = FlowOutcome.fromWire(run.optString("outcome", null)),
                cursorStepId = run.optString("cursorStepId", null),
                environment = run.optString("environment", "production"),
                pendingAction = pendingActionJson?.let { PendingAction.decode(it) },
                branding = brandingJson?.let {
                    Branding(
                        displayName = it.optString("display_name", "UseSense"),
                        logoUrl = it.optString("logo_url", null),
                        primaryColor = it.optString("primary_color", "#4F7CFF"),
                        redirectUrl = it.optString("redirect_url", null),
                    )
                },
            )
        }
    }
}

/** Terminal result delivered to the host app's callback. */
data class FlowRunResult(
    val flowRunId: String,
    val state: FlowRunState,
    val outcome: FlowOutcome?,
)

/** Options for `UseSenseFlows.run(...)`. */
data class RunFlowOptions(
    val flowRunId: String,
    val sdkToken: String,
    val apiBaseUrl: String = "https://api.usesense.ai",
)

/** Host-app callback (no Kotlin sealed Result quirks — explicit success / failure / cancel). */
interface FlowsCallback {
    fun onResult(result: FlowRunResult)
    fun onError(error: FlowError)
}
