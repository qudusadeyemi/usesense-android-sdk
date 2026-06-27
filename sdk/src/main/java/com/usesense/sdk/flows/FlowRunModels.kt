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
 * Per-field type the SDK uses to choose the right native input control.
 * Mirrors modules/flows/types.ts in usesense-watchtower — keep both in sync.
 */
enum class FormFieldType(val wire: String) {
    TEXT("text"), EMAIL("email"), TEL("tel"), NUMBER("number"),
    DATE("date"), SELECT("select"), CHECKBOX("checkbox"), COUNTRY("country");
    companion object {
        fun fromWire(s: String?): FormFieldType = values().firstOrNull { it.wire == s } ?: TEXT
    }
}

/**
 * One typed input on a form-capture step. A plain string on the wire becomes
 * a default text field; an object payload carries the full per-field metadata
 * so the runner can render the right input primitive, run the validators, and
 * surface inline errors.
 */
data class FormField(
    val key: String,
    val type: FormFieldType = FormFieldType.TEXT,
    val label: String? = null,
    val hint: String? = null,
    val placeholder: String? = null,
    val required: Boolean = true,
    /** Raw initial value: String / Boolean / Number depending on type. */
    val initial: Any? = null,
    val validators: Validators? = null,
    val options: List<Option>? = null,
    val allowedCountries: List<String>? = null,
) {
    data class Validators(
        val pattern: String? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val minNumber: Double? = null,
        val maxNumber: Double? = null,
        val minString: String? = null,
        val maxString: String? = null,
        val errorMessage: String? = null,
    )

    data class Option(val value: String, val label: String)

    companion object {
        fun decode(any: Any?): FormField {
            if (any is String) return FormField(key = any)
            val raw = any as? JSONObject ?: return FormField(key = "")
            val type = FormFieldType.fromWire(raw.optString("type", "text"))
            val validators = raw.optJSONObject("validators")?.let { v ->
                Validators(
                    pattern = v.optStringOrNull("pattern"),
                    minLength = v.optIntOrNull("min_length"),
                    maxLength = v.optIntOrNull("max_length"),
                    minNumber = v.opt("min").asDoubleOrNull(),
                    maxNumber = v.opt("max").asDoubleOrNull(),
                    minString = (v.opt("min") as? String),
                    maxString = (v.opt("max") as? String),
                    errorMessage = v.optStringOrNull("error_message"),
                )
            }
            val options = raw.optJSONArray("options")?.let { arr ->
                val out = ArrayList<Option>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val v = o.optStringOrNull("value") ?: continue
                    val l = o.optStringOrNull("label") ?: continue
                    out.add(Option(v, l))
                }
                out
            }
            return FormField(
                key = raw.optString("key", ""),
                type = type,
                label = raw.optStringOrNull("label"),
                hint = raw.optStringOrNull("hint"),
                placeholder = raw.optStringOrNull("placeholder"),
                required = if (raw.has("required")) raw.optBoolean("required", true) else true,
                initial = if (raw.has("initial")) raw.opt("initial") else null,
                validators = validators,
                options = options,
                allowedCountries = raw.optJSONArray("allowed_countries").toStringList().ifEmpty { null },
            )
        }
    }
}

enum class InfoBulletIcon(val wire: String) {
    CHECK("check"), SHIELD("shield"), CAMERA("camera"), WARNING("warning"), INFO("info");
    companion object {
        fun fromWire(s: String?): InfoBulletIcon? = values().firstOrNull { it.wire == s }
    }
}

data class InfoBullet(val icon: InfoBulletIcon?, val text: String)

data class InfoCta(
    val label: String,
    /** When set, the SDK opens this URL in a Chrome Custom Tab (or ACTION_VIEW
     *  fallback) and advances the run after the subject returns. */
    val openUrl: String?,
)

data class InfoSecondaryCta(val label: String, val action: Action) {
    enum class Action(val wire: String) {
        CANCEL("cancel"), ADVANCE("advance");
        companion object {
            fun fromWire(s: String?): Action? = values().firstOrNull { it.wire == s }
        }
    }
}

/**
 * Server-driven info screen. Subsumes `redirect_to_consent` for downgrade-safe
 * shapes (see action-contract.mdx). Unknown bullet icons render as the
 * default info glyph; never block on them.
 */
data class InfoAction(
    val title: String,
    val body: String?,
    val imageUrl: String?,
    val bullets: List<InfoBullet>,
    val primary: InfoCta,
    val secondary: InfoSecondaryCta?,
) {
    companion object {
        fun decode(raw: JSONObject): InfoAction {
            val title = raw.optString("title", "")
            val primaryRaw = raw.optJSONObject("primary_cta")
                ?: throw FlowError(FlowError.Code.UNSUPPORTED_ACTION, "info.primary_cta missing")
            val primary = InfoCta(
                label = primaryRaw.optString("label", ""),
                openUrl = primaryRaw.optString("open_url", "").takeIf { it.isNotEmpty() },
            )
            val secondary = raw.optJSONObject("secondary_cta")?.let { s ->
                val action = InfoSecondaryCta.Action.fromWire(s.optString("action", "")) ?: return@let null
                InfoSecondaryCta(label = s.optString("label", ""), action = action)
            }
            val bulletsArr = raw.optJSONArray("bullets")
            val bullets = ArrayList<InfoBullet>(bulletsArr?.length() ?: 0)
            if (bulletsArr != null) for (i in 0 until bulletsArr.length()) {
                val b = bulletsArr.optJSONObject(i) ?: continue
                val text = b.optString("text", "")
                if (text.isEmpty()) continue
                bullets.add(InfoBullet(icon = InfoBulletIcon.fromWire(b.optString("icon", null)), text = text))
            }
            return InfoAction(
                title = title,
                body = raw.optString("body", "").takeIf { it.isNotEmpty() },
                imageUrl = raw.optString("image_url", "").takeIf { it.isNotEmpty() },
                bullets = bullets,
                primary = primary,
                secondary = secondary,
            )
        }
    }
}

/**
 * The action the server parked at. The runner reads it and renders the
 * matching native surface; unknown kinds surface as FlowError.UNSUPPORTED_ACTION.
 * See `guides/flows/action-contract` in the API docs.
 */
/** One ID-type option for the id_number capture step (e.g. NIN / BVN). */
data class IdTypeSpec(
    val value: String,
    val label: String,
    val hint: String?,
    val field: String,
    val maxLength: Int?,
    val numeric: Boolean,
) {
    companion object {
        fun decode(raw: JSONObject): IdTypeSpec? {
            val value = raw.opt("value") as? String ?: return null
            val label = raw.opt("label") as? String ?: return null
            val field = raw.opt("field") as? String ?: return null
            return IdTypeSpec(
                value = value,
                label = label,
                hint = raw.opt("hint") as? String,
                field = field,
                maxLength = if (raw.has("maxLength")) raw.optInt("maxLength") else null,
                numeric = raw.optBoolean("numeric", false),
            )
        }
    }
}

sealed class PendingAction {
    data class CaptureFace(val toolId: String?) : PendingAction()
    data class CaptureDocument(
        val category: String,
        val documentTypes: List<String>,
        val issuingCountries: List<String>,
        /** "rear" (default) or "front". */
        val camera: String?,
        /** Allowed methods, operator-configurable per step. Defaults to both. */
        val captureMethods: List<String>,
    ) : PendingAction()
    data class CaptureForm(val fields: List<FormField>) : PendingAction()
    data class CaptureIdNumber(val idTypes: List<IdTypeSpec>) : PendingAction()
    data class Info(val info: InfoAction) : PendingAction()
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
                        camera = raw.opt("camera") as? String,
                        captureMethods = raw.optJSONArray("captureMethods").toStringList()
                            .ifEmpty { listOf("camera", "upload") },
                    )
                    "form" -> {
                        val arr = raw.optJSONArray("fields")
                        val fields = ArrayList<FormField>(arr?.length() ?: 0)
                        if (arr != null) {
                            for (i in 0 until arr.length()) fields.add(FormField.decode(arr.opt(i)))
                        }
                        CaptureForm(fields = fields)
                    }
                    "id_number" -> {
                        val arr = raw.optJSONArray("idTypes")
                        val types = ArrayList<IdTypeSpec>(arr?.length() ?: 0)
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                (arr.opt(i) as? JSONObject)?.let { obj -> IdTypeSpec.decode(obj)?.let(types::add) }
                            }
                        }
                        CaptureIdNumber(idTypes = types)
                    }
                    else -> throw FlowError(FlowError.Code.UNSUPPORTED_ACTION, "Unknown capture variant: $capture")
                }
                "info" -> Info(InfoAction.decode(raw))
                "redirect_to_consent" -> RedirectToConsent(consentUrl = raw.optString("consentUrl"))
                else -> throw FlowError(FlowError.Code.UNSUPPORTED_ACTION, "Unknown pendingAction.kind: $kind")
            }
        }
    }
}

internal fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name, "").takeIf { it.isNotEmpty() }
}
private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name).takeIf { has(name) }
}
private fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
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
        /**
         * Operator-configured white-label appearance (Phase 1c). Optional; when
         * present it carries the full FlowAppearance contract delivered under the
         * branding payload's `appearance` object. Merged below the SDK-init
         * appearance and above the legacy primaryColor field.
         */
        val appearance: FlowAppearance? = null,
        /**
         * Operator-configured white-label copy (Phase 2). Optional; when present
         * it carries the full FlowCopy contract delivered under the branding
         * payload's `copy` object. Merged below the SDK-init copy and above the
         * built-in defaults.
         */
        val copy: FlowCopy? = null,
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
                        appearance = it.optJSONObject("appearance")?.let(FlowAppearance::decode),
                        copy = it.optJSONObject("copy")?.let(FlowCopy::decode),
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
    /**
     * Optional SDK-init white-label appearance (Phase 1c). Merged above the
     * server-delivered appearance and the legacy primaryColor when resolving the
     * runner theme. Null = inherit from server / built-in tokens.
     */
    val appearance: FlowAppearance? = null,
    /**
     * Optional SDK-init white-label copy (Phase 2). Merged above the
     * server-delivered copy when resolving subject-facing strings. Null = inherit
     * from server / built-in defaults.
     */
    val copy: FlowCopy? = null,
)

/** Host-app callback (no Kotlin sealed Result quirks — explicit success / failure / cancel). */
interface FlowsCallback {
    fun onResult(result: FlowRunResult)
    fun onError(error: FlowError)
}
