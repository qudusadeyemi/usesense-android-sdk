package com.usesense.sdk.flows

import org.json.JSONObject

/**
 * The white-label copy/messaging override contract (Phase 2) shared across
 * surfaces and SDKs. Mirrors `FlowCopy` in usesense-web-sdk
 * (packages/sdk/src/flows/copy.ts) — keep both in sync.
 *
 * Like [FlowAppearance] a [FlowCopy] can be supplied two ways and is merged
 *   SDK-init > server(branding) > built-in default:
 *   - by the developer at SDK init (BrandingConfig.copy / RunFlowOptions.copy), and/or
 *   - by the operator in the dashboard, delivered on the flow-run branding payload
 *     under `branding.copy`.
 * Every field is optional; an omitted (or blank) key keeps the built-in
 * hosted-page copy. Only existing subject-facing strings are overridable — the
 * welcome/privacy groups are part of the cross-SDK contract but the Android
 * runner has no such surfaces yet, so they decode but are otherwise inert.
 */
data class FlowCopy(
    /** Optional welcome/intro shown before the first step (when set). */
    val welcome: WelcomeCopy? = null,
    /** Shared button labels. */
    val buttons: ButtonsCopy? = null,
    /** Titles shown under the loader for each transient state. */
    val loading: LoadingCopy? = null,
    /** Face capture primer. */
    val face: FaceCopy? = null,
    /** Document capture surfaces. */
    val document: DocumentCopy? = null,
    /** Form surface. */
    val form: FormCopy? = null,
    /** ID-number surface. */
    val idNumber: IdNumberCopy? = null,
    /** Terminal result screens. */
    val result: ResultCopy? = null,
    /** Error copy (provider failure vs unreadable capture vs generic). */
    val errors: ErrorsCopy? = null,
    /** Privacy / consent disclosures shown to the subject. */
    val privacy: PrivacyCopy? = null,
    /** Free-form help text / tooltips keyed by an SDK-defined slot id. */
    val help: Map<String, String>? = null,
) {
    companion object {
        /**
         * Decode the wire shape. The server delivers the copy under the flow-run
         * branding payload's `copy` object; every key is optional. Keys are read
         * both snake_case (wire convention) and camelCase (parity with the web
         * SDK / hand-authored payloads).
         */
        fun decode(raw: JSONObject): FlowCopy = FlowCopy(
            welcome = raw.optJSONObject("welcome")?.let(WelcomeCopy::decode),
            buttons = raw.optJSONObject("buttons")?.let(ButtonsCopy::decode),
            loading = raw.optJSONObject("loading")?.let(LoadingCopy::decode),
            face = raw.optJSONObject("face")?.let(FaceCopy::decode),
            document = raw.optJSONObject("document")?.let(DocumentCopy::decode),
            form = raw.optJSONObject("form")?.let(FormCopy::decode),
            idNumber = (raw.optJSONObject("id_number") ?: raw.optJSONObject("idNumber"))
                ?.let(IdNumberCopy::decode),
            result = raw.optJSONObject("result")?.let(ResultCopy::decode),
            errors = raw.optJSONObject("errors")?.let(ErrorsCopy::decode),
            privacy = raw.optJSONObject("privacy")?.let(PrivacyCopy::decode),
            help = raw.optJSONObject("help")?.toStringMap(),
        )
    }
}

data class WelcomeCopy(val title: String? = null, val body: String? = null) {
    companion object {
        fun decode(raw: JSONObject) = WelcomeCopy(
            title = raw.optStringOrNull("title"),
            body = raw.optStringOrNull("body"),
        )
    }
}

data class ButtonsCopy(
    val continueLabel: String? = null,
    val cancel: String? = null,
    val tryAgain: String? = null,
    val retake: String? = null,
    val useThisPhoto: String? = null,
    val uploadInstead: String? = null,
    val scan: String? = null,
    val upload: String? = null,
    val submitting: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject) = ButtonsCopy(
            continueLabel = raw.optStringOrNull("continue"),
            cancel = raw.optStringOrNull("cancel"),
            tryAgain = raw.optStringOrNull("try_again") ?: raw.optStringOrNull("tryAgain"),
            retake = raw.optStringOrNull("retake"),
            useThisPhoto = raw.optStringOrNull("use_this_photo") ?: raw.optStringOrNull("useThisPhoto"),
            uploadInstead = raw.optStringOrNull("upload_instead") ?: raw.optStringOrNull("uploadInstead"),
            scan = raw.optStringOrNull("scan"),
            upload = raw.optStringOrNull("upload"),
            submitting = raw.optStringOrNull("submitting"),
        )
    }
}

data class LoadingCopy(
    val default: String? = null,
    val verifying: String? = null,
    val submittingDocument: String? = null,
    val checkingQuality: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject) = LoadingCopy(
            default = raw.optStringOrNull("default"),
            verifying = raw.optStringOrNull("verifying"),
            submittingDocument = raw.optStringOrNull("submitting_document") ?: raw.optStringOrNull("submittingDocument"),
            checkingQuality = raw.optStringOrNull("checking_quality") ?: raw.optStringOrNull("checkingQuality"),
        )
    }
}

data class FaceCopy(val title: String? = null, val body: String? = null, val start: String? = null) {
    companion object {
        fun decode(raw: JSONObject) = FaceCopy(
            title = raw.optStringOrNull("title"),
            body = raw.optStringOrNull("body"),
            start = raw.optStringOrNull("start"),
        )
    }
}

data class DocumentCopy(
    val selectTitle: String? = null,
    val selectBody: String? = null,
    val primerTitle: String? = null,
    val primerBody: String? = null,
    val uploadTitle: String? = null,
    val uploadBody: String? = null,
    val scanTitle: String? = null,
    val scanBody: String? = null,
    val confirmTitle: String? = null,
    val confirmBody: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject) = DocumentCopy(
            selectTitle = raw.optStringOrNull("select_title") ?: raw.optStringOrNull("selectTitle"),
            selectBody = raw.optStringOrNull("select_body") ?: raw.optStringOrNull("selectBody"),
            primerTitle = raw.optStringOrNull("primer_title") ?: raw.optStringOrNull("primerTitle"),
            primerBody = raw.optStringOrNull("primer_body") ?: raw.optStringOrNull("primerBody"),
            uploadTitle = raw.optStringOrNull("upload_title") ?: raw.optStringOrNull("uploadTitle"),
            uploadBody = raw.optStringOrNull("upload_body") ?: raw.optStringOrNull("uploadBody"),
            scanTitle = raw.optStringOrNull("scan_title") ?: raw.optStringOrNull("scanTitle"),
            scanBody = raw.optStringOrNull("scan_body") ?: raw.optStringOrNull("scanBody"),
            confirmTitle = raw.optStringOrNull("confirm_title") ?: raw.optStringOrNull("confirmTitle"),
            confirmBody = raw.optStringOrNull("confirm_body") ?: raw.optStringOrNull("confirmBody"),
        )
    }
}

data class FormCopy(val title: String? = null) {
    companion object {
        fun decode(raw: JSONObject) = FormCopy(title = raw.optStringOrNull("title"))
    }
}

data class IdNumberCopy(val title: String? = null, val body: String? = null) {
    companion object {
        fun decode(raw: JSONObject) = IdNumberCopy(
            title = raw.optStringOrNull("title"),
            body = raw.optStringOrNull("body"),
        )
    }
}

data class ResultCopy(
    val successTitle: String? = null,
    val successBody: String? = null,
    val reviewTitle: String? = null,
    val reviewBody: String? = null,
    val notVerifiedTitle: String? = null,
    val notVerifiedBody: String? = null,
    val cancelledTitle: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject) = ResultCopy(
            successTitle = raw.optStringOrNull("success_title") ?: raw.optStringOrNull("successTitle"),
            successBody = raw.optStringOrNull("success_body") ?: raw.optStringOrNull("successBody"),
            reviewTitle = raw.optStringOrNull("review_title") ?: raw.optStringOrNull("reviewTitle"),
            reviewBody = raw.optStringOrNull("review_body") ?: raw.optStringOrNull("reviewBody"),
            notVerifiedTitle = raw.optStringOrNull("not_verified_title") ?: raw.optStringOrNull("notVerifiedTitle"),
            notVerifiedBody = raw.optStringOrNull("not_verified_body") ?: raw.optStringOrNull("notVerifiedBody"),
            cancelledTitle = raw.optStringOrNull("cancelled_title") ?: raw.optStringOrNull("cancelledTitle"),
        )
    }
}

data class ErrorsCopy(
    val generic: String? = null,
    val providerUnavailable: String? = null,
    val documentUnreadable: String? = null,
    /** Distinct from [documentUnreadable]: the document was fine, the transfer
     *  was not, so this copy must ask for a resend and never for a retake. */
    val documentIncomplete: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject) = ErrorsCopy(
            generic = raw.optStringOrNull("generic"),
            providerUnavailable = raw.optStringOrNull("provider_unavailable") ?: raw.optStringOrNull("providerUnavailable"),
            documentUnreadable = raw.optStringOrNull("document_unreadable") ?: raw.optStringOrNull("documentUnreadable"),
            documentIncomplete = raw.optStringOrNull("document_incomplete") ?: raw.optStringOrNull("documentIncomplete"),
        )
    }
}

data class PrivacyCopy(
    val disclosure: String? = null,
    val consentTitle: String? = null,
    val consentBody: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject) = PrivacyCopy(
            disclosure = raw.optStringOrNull("disclosure"),
            consentTitle = raw.optStringOrNull("consent_title") ?: raw.optStringOrNull("consentTitle"),
            consentBody = raw.optStringOrNull("consent_body") ?: raw.optStringOrNull("consentBody"),
        )
    }
}

/**
 * Deep-merge a higher-priority copy map over a lower one (for SDK > server).
 * Returns null only when both inputs are null. Mirrors `mergeCopy` in the web
 * SDK: per group, every overridable string falls through high -> low.
 */
fun mergeCopy(high: FlowCopy?, low: FlowCopy?): FlowCopy? {
    if (high == null) return low
    if (low == null) return high
    return FlowCopy(
        welcome = WelcomeCopy(
            title = pick(high.welcome?.title, low.welcome?.title),
            body = pick(high.welcome?.body, low.welcome?.body),
        ).takeIf { high.welcome != null || low.welcome != null },
        buttons = ButtonsCopy(
            continueLabel = pick(high.buttons?.continueLabel, low.buttons?.continueLabel),
            cancel = pick(high.buttons?.cancel, low.buttons?.cancel),
            tryAgain = pick(high.buttons?.tryAgain, low.buttons?.tryAgain),
            retake = pick(high.buttons?.retake, low.buttons?.retake),
            useThisPhoto = pick(high.buttons?.useThisPhoto, low.buttons?.useThisPhoto),
            uploadInstead = pick(high.buttons?.uploadInstead, low.buttons?.uploadInstead),
            scan = pick(high.buttons?.scan, low.buttons?.scan),
            upload = pick(high.buttons?.upload, low.buttons?.upload),
            submitting = pick(high.buttons?.submitting, low.buttons?.submitting),
        ).takeIf { high.buttons != null || low.buttons != null },
        loading = LoadingCopy(
            default = pick(high.loading?.default, low.loading?.default),
            verifying = pick(high.loading?.verifying, low.loading?.verifying),
            submittingDocument = pick(high.loading?.submittingDocument, low.loading?.submittingDocument),
            checkingQuality = pick(high.loading?.checkingQuality, low.loading?.checkingQuality),
        ).takeIf { high.loading != null || low.loading != null },
        face = FaceCopy(
            title = pick(high.face?.title, low.face?.title),
            body = pick(high.face?.body, low.face?.body),
            start = pick(high.face?.start, low.face?.start),
        ).takeIf { high.face != null || low.face != null },
        document = DocumentCopy(
            selectTitle = pick(high.document?.selectTitle, low.document?.selectTitle),
            selectBody = pick(high.document?.selectBody, low.document?.selectBody),
            primerTitle = pick(high.document?.primerTitle, low.document?.primerTitle),
            primerBody = pick(high.document?.primerBody, low.document?.primerBody),
            uploadTitle = pick(high.document?.uploadTitle, low.document?.uploadTitle),
            uploadBody = pick(high.document?.uploadBody, low.document?.uploadBody),
            scanTitle = pick(high.document?.scanTitle, low.document?.scanTitle),
            scanBody = pick(high.document?.scanBody, low.document?.scanBody),
            confirmTitle = pick(high.document?.confirmTitle, low.document?.confirmTitle),
            confirmBody = pick(high.document?.confirmBody, low.document?.confirmBody),
        ).takeIf { high.document != null || low.document != null },
        form = FormCopy(
            title = pick(high.form?.title, low.form?.title),
        ).takeIf { high.form != null || low.form != null },
        idNumber = IdNumberCopy(
            title = pick(high.idNumber?.title, low.idNumber?.title),
            body = pick(high.idNumber?.body, low.idNumber?.body),
        ).takeIf { high.idNumber != null || low.idNumber != null },
        result = ResultCopy(
            successTitle = pick(high.result?.successTitle, low.result?.successTitle),
            successBody = pick(high.result?.successBody, low.result?.successBody),
            reviewTitle = pick(high.result?.reviewTitle, low.result?.reviewTitle),
            reviewBody = pick(high.result?.reviewBody, low.result?.reviewBody),
            notVerifiedTitle = pick(high.result?.notVerifiedTitle, low.result?.notVerifiedTitle),
            notVerifiedBody = pick(high.result?.notVerifiedBody, low.result?.notVerifiedBody),
            cancelledTitle = pick(high.result?.cancelledTitle, low.result?.cancelledTitle),
        ).takeIf { high.result != null || low.result != null },
        errors = ErrorsCopy(
            generic = pick(high.errors?.generic, low.errors?.generic),
            providerUnavailable = pick(high.errors?.providerUnavailable, low.errors?.providerUnavailable),
            documentUnreadable = pick(high.errors?.documentUnreadable, low.errors?.documentUnreadable),
            documentIncomplete = pick(high.errors?.documentIncomplete, low.errors?.documentIncomplete),
        ).takeIf { high.errors != null || low.errors != null },
        privacy = PrivacyCopy(
            disclosure = pick(high.privacy?.disclosure, low.privacy?.disclosure),
            consentTitle = pick(high.privacy?.consentTitle, low.privacy?.consentTitle),
            consentBody = pick(high.privacy?.consentBody, low.privacy?.consentBody),
        ).takeIf { high.privacy != null || low.privacy != null },
        help = when {
            high.help != null && low.help != null -> low.help + high.help
            else -> high.help ?: low.help
        },
    )
}

/**
 * Pick the higher-priority string, treating a blank (empty/whitespace) high
 * value as unset so the lower-priority (server) value shows through. Mirrors the
 * blank-high-as-unset guard in the web SDK's `mergeGroup` (copy.ts).
 */
private fun pick(hi: String?, lo: String?): String? = if (!hi.isNullOrBlank()) hi else lo

/**
 * Read an override or fall back to the built-in default. Treats empty/blank
 * overrides as unset so a cleared dashboard field never blanks the UI. Mirrors
 * `txt(...)` in the web SDK.
 */
internal fun text(value: String?, default: String): String =
    if (value != null && value.isNotBlank()) value else default

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name, "").takeIf { it.isNotEmpty() }
}

private fun JSONObject.toStringMap(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (isNull(k)) continue
        val v = optString(k, "")
        if (v.isNotEmpty()) out[k] = v
    }
    return out
}
