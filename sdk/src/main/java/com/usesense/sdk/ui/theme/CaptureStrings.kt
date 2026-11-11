package com.usesense.sdk.ui.theme

import com.usesense.sdk.flows.CaptureCopy

/**
 * Capture copy (BRAND-v1 section 5): `copy.capture` overrides, else the
 * SDK's shipped English from strings.xml, byte for byte.
 *
 * A string resource maps to a `copy.capture` key through [CaptureCopyKeys]
 * by its resource name, so every call site keeps its R.string id. Pure: the
 * Android lookups come in as functions, so it is unit tested.
 *
 * @param app `{app}`: the resolved display name, else the host app's name.
 * @param resourceName R.string id -> resource entry name (`usesense_leave_title`).
 * @param shipped R.string id + app name -> the shipped string (`%s` filled with the app).
 */
internal class CaptureStrings(
    private val copy: CaptureCopy?,
    private val app: String,
    private val resourceName: (Int) -> String?,
    private val shipped: (Int, String) -> String,
) {
    /** The override for [id] (or for [key] when given), else the shipped string. */
    fun text(id: Int, key: String? = null): String {
        val entry = key?.let { CaptureCopyKeys.Entry(it) } ?: resourceName(id)?.let { CaptureCopyKeys.byResource[it] }
        val override = entry?.let { copy?.text(it.key) }
        return if (override != null) fill(override, app, entry.side) else shipped(id, app)
    }

    /** A tip list: the override list for [key], else the shipped strings. */
    fun list(key: String, ids: List<Int>): List<String> =
        copy?.list(key)?.map { fill(it, app, null) } ?: ids.map { shipped(it, app) }

    companion object {
        /** `{app}` and `{side}`; any other `{...}` stays as written. */
        fun fill(template: String, app: String, side: String?): String {
            var out = template.replace("{app}", app)
            if (side != null) out = out.replace("{side}", side)
            return out
        }
    }
}

/**
 * strings.xml resource name -> `copy.capture` key. Keys marked NEW are not in
 * BRAND-v1 section 5: Android strings with no key there, added under the
 * nearest group in the same style (reported for iOS and Web to match).
 */
internal object CaptureCopyKeys {
    /** [side] fills `{side}` for one-template-many-directions keys. */
    data class Entry(val key: String, val side: String? = null)

    private fun e(key: String, side: String? = null) = Entry(key, side)

    // Tip lists (string arrays).
    const val PRIMER_SELFIE_TIPS = "primer.selfieTips"
    const val PRIMER_TWO_STEPS_TIPS = "primer.twoStepsTips"
    /** NEW: the rejected-with-retry page's two tips. */
    const val RESULT_RETRY_TIPS = "result.retryTips"

    // Keys that share a resource with another key: passed explicitly at the call site.
    const val RESULT_RETRY_TITLE = "result.retryTitle"
    const val UPLOAD_RETRY = "upload.retry"

    val byResource: Map<String, Entry> = mapOf(
        // primer
        "usesense_primer_title" to e("primer.selfieTitle"),
        "usesense_primer_body" to e("primer.selfieBody"),
        "usesense_primer_two_title" to e("primer.twoStepsTitle"),
        "usesense_primer_two_body" to e("primer.twoStepsBody"),
        "usesense_start" to e("primer.start"),
        "usesense_getting_ready" to e("primer.gettingReady"), // NEW: "Getting ready…" loading screen

        // guidance (camera)
        "usesense_hint_show_face" to e("guidance.showFace"),
        "usesense_hint_one_person" to e("guidance.onePerson"),
        "usesense_hint_face_covered" to e("guidance.covered"),
        "usesense_hint_centre" to e("guidance.centre"),
        "usesense_hint_move_closer" to e("guidance.closer"),
        "usesense_hint_move_back" to e("guidance.back"),
        "usesense_hint_look_straight" to e("guidance.straight"),
        "usesense_hint_face_light" to e("guidance.dark"),
        "usesense_hint_face_light_detail" to e("guidance.darkHint"),
        "usesense_hint_too_bright" to e("guidance.bright"),
        "usesense_hint_too_bright_detail" to e("guidance.brightHint"),
        "usesense_hint_hold_steady" to e("guidance.blur"),
        "usesense_hint_eyes_open" to e("guidance.eyes"),
        "usesense_hint_hold_still" to e("guidance.holdStill"),
        "usesense_shutter_title" to e("guidance.shutterHint"),
        "usesense_scan_complete" to e("guidance.scanComplete"), // NEW
        "usesense_capture_help_title" to e("guidance.helpTitle"), // NEW: help dialog
        "usesense_capture_help_body" to e("guidance.helpBody"), // NEW
        "usesense_ok" to e("guidance.helpOk"), // NEW
        "usesense_inline_intro" to e("guidance.inlineIntro"), // NEW: on-device attack detection prompts
        "usesense_inline_intro_detail" to e("guidance.inlineIntroDetail"), // NEW
        "usesense_inline_flash_detail" to e("guidance.inlineFlashDetail"), // NEW
        "usesense_inline_blink" to e("guidance.blink"), // NEW
        "usesense_inline_smile" to e("guidance.smile"), // NEW
        "usesense_inline_eyebrows" to e("guidance.eyebrows"), // NEW
        "usesense_inline_mouth" to e("guidance.mouth"), // NEW

        // extra check
        "usesense_xc_sheet_title" to e("extraCheck.sheetTitle"),
        // sheetBody is the Head Turn sheet (its five sequence variants); Follow Dot and
        // Speak Digits need their own sentence, so they get NEW keys.
        "usesense_xc_sheet_head_left_right" to e("extraCheck.sheetBody"),
        "usesense_xc_sheet_head_right_left" to e("extraCheck.sheetBody"),
        "usesense_xc_sheet_head_left" to e("extraCheck.sheetBody"),
        "usesense_xc_sheet_head_right" to e("extraCheck.sheetBody"),
        "usesense_xc_sheet_head_other" to e("extraCheck.sheetBody"),
        "usesense_xc_sheet_dot" to e("extraCheck.sheetBodyFollowDot"), // NEW
        "usesense_xc_sheet_digits" to e("extraCheck.sheetBodySpeakDigits"), // NEW
        "usesense_xc_continue" to e("extraCheck.continue"),
        "usesense_xc_turn_left" to e("extraCheck.headTurn", "left"),
        "usesense_xc_turn_right" to e("extraCheck.headTurn", "right"),
        "usesense_xc_turn_up" to e("extraCheck.headTurn", "up"),
        "usesense_xc_turn_down" to e("extraCheck.headTurn", "down"),
        "usesense_xc_turn_centre" to e("extraCheck.lookCentre"),
        "usesense_xc_now_left" to e("extraCheck.tryNext", "left"),
        "usesense_xc_now_right" to e("extraCheck.tryNext", "right"),
        "usesense_xc_now_up" to e("extraCheck.tryNext", "up"),
        "usesense_xc_now_down" to e("extraCheck.tryNext", "down"),
        "usesense_xc_now_centre" to e("extraCheck.nowCentre"), // NEW
        "usesense_xc_follow_dot" to e("extraCheck.followDot"),
        "usesense_xc_say_numbers" to e("extraCheck.speakDigits"),
        "usesense_xc_say_words" to e("extraCheck.speakWords"), // NEW: a non-digit phrase
        "usesense_xc_done" to e("extraCheck.done"),
        "usesense_xc_slowly" to e("extraCheck.slowly"), // NEW
        "usesense_xc_again" to e("extraCheck.again"), // NEW
        "usesense_xc_further_left" to e("extraCheck.further", "left"), // NEW
        "usesense_xc_further_right" to e("extraCheck.further", "right"), // NEW
        "usesense_xc_paused_turn_detail" to e("extraCheck.pausedTurnDetail"), // NEW: no direction known
        "usesense_xc_paused_turn_left" to e("extraCheck.pausedTurnSide", "left"), // NEW
        "usesense_xc_paused_turn_right" to e("extraCheck.pausedTurnSide", "right"), // NEW
        "usesense_xc_paused_turn_up" to e("extraCheck.pausedTurnSide", "up"), // NEW
        "usesense_xc_paused_turn_down" to e("extraCheck.pausedTurnSide", "down"), // NEW
        "usesense_xc_paused_turn_centre" to e("extraCheck.pausedLookCentre"), // NEW
        "usesense_xc_dot_wait" to e("extraCheck.dotWait"), // NEW
        "usesense_xc_keep_following" to e("extraCheck.keepFollowing"), // NEW
        "usesense_xc_paused_digits_detail" to e("extraCheck.pausedDigitsDetail"), // NEW
        "usesense_xc_cant_hear" to e("extraCheck.cantHear"), // NEW
        "usesense_xc_louder" to e("extraCheck.louder"), // NEW

        // upload
        "usesense_upload_title" to e("upload.title"),
        "usesense_upload_detail" to e("upload.caption"),
        "usesense_upload_failed_title" to e("upload.failedTitle"),
        "usesense_upload_failed_body" to e("upload.failedBody"),

        // result
        "usesense_page_approved_title" to e("result.approvedTitle"),
        "usesense_page_approved_body" to e("result.approvedBody"),
        "usesense_page_review_title" to e("result.reviewTitle"),
        "usesense_page_review_body" to e("result.reviewBody"),
        "usesense_page_rejected_title" to e("result.rejectedTitle"),
        "usesense_page_rejected_body" to e("result.rejectedBody"),
        "usesense_page_rejected_retry_body" to e("result.retryBody"),
        "usesense_page_rejected_retry_body_many" to e("result.retryBody"),
        "usesense_page_done" to e("result.done"),
        "usesense_page_try_again" to e("result.tryAgain"),
        "usesense_not_now" to e("result.notNow"),

        // errors
        "usesense_page_camera_denied_title" to e("errors.cameraDeniedTitle"),
        "usesense_page_camera_denied_body" to e("errors.cameraDeniedBody"),
        "usesense_open_settings" to e("errors.openSettings"),
        "usesense_page_camera_error_title" to e("errors.cameraStoppedTitle"),
        "usesense_page_camera_error_body" to e("errors.cameraStoppedBody"),
        "usesense_page_mic_title" to e("errors.micTitle"),
        "usesense_page_mic_body" to e("errors.micBody"),
        "usesense_page_mic_instead" to e("errors.useHeadMovement"),
        "usesense_page_offline_title" to e("errors.offlineTitle"),
        "usesense_page_offline_body" to e("errors.offlineBody"),
        "usesense_page_expired_title" to e("errors.expiredTitle"),
        "usesense_page_expired_body" to e("errors.expiredBody"),
        "usesense_start_again" to e("errors.startAgain"),
        "usesense_page_error_title" to e("errors.errorTitle"),
        "usesense_page_error_body" to e("errors.errorBody"),

        // leave
        "usesense_leave_title" to e("leave.title"),
        "usesense_leave_body" to e("leave.body"),
        "usesense_leave" to e("leave.leave"),
        "usesense_keep_going" to e("leave.keepGoing"),
    )
}
