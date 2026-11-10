package com.usesense.sdk.ui

import com.usesense.sdk.R
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult

/** The full-screen dark pages of the storyboard (primer, results, errors). */
internal enum class PageKind {
    PRIMER,
    PRIMER_TWO,
    CAMERA_DENIED,
    CAMERA_ERROR,
    OFFLINE,
    MIC_NEEDED,
    APPROVED,
    REVIEW,
    REJECTED_RETRY,
    REJECTED,
    EXPIRED,
    ERROR,
}

/** What a page shows. Strings with a %s take the host app's name. */
internal data class PageSpec(
    val kind: PageKind,
    val icon: String,
    val iconBackground: Int,
    val iconForeground: Int,
    val title: Int,
    val body: Int,
    val tips: List<Int> = emptyList(),
    val primary: Int,
    val secondary: Int? = null,
)

/** Which page shows for an outcome or an error, and with what. Pure, so it is unit tested. */
internal object OutcomePages {
    private val I = StrokeIconView.Icons
    private val S = StrokeIconView

    private val primerTips =
        listOf(
            R.string.usesense_primer_tip_light,
            R.string.usesense_primer_tip_uncovered,
            R.string.usesense_primer_tip_alone,
        )

    fun spec(
        kind: PageKind,
        retriesLeft: Int = 0,
    ): PageSpec =
        when (kind) {
            PageKind.PRIMER ->
                PageSpec(
                    kind,
                    I.FACE,
                    S.TINT_BLUE_BG,
                    S.TINT_BLUE_FG,
                    R.string.usesense_primer_title,
                    R.string.usesense_primer_body,
                    primerTips,
                    R.string.usesense_start,
                )
            PageKind.PRIMER_TWO ->
                PageSpec(
                    kind,
                    I.FACE,
                    S.TINT_BLUE_BG,
                    S.TINT_BLUE_FG,
                    R.string.usesense_primer_two_title,
                    R.string.usesense_primer_two_body,
                    primerTips,
                    R.string.usesense_start,
                )
            PageKind.CAMERA_DENIED ->
                PageSpec(
                    kind,
                    I.CAMERA,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_camera_denied_title,
                    R.string.usesense_page_camera_denied_body,
                    primary = R.string.usesense_open_settings,
                    secondary = R.string.usesense_page_try_again,
                )
            PageKind.CAMERA_ERROR ->
                PageSpec(
                    kind,
                    I.CAMERA,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_camera_error_title,
                    R.string.usesense_page_camera_error_body,
                    primary = R.string.usesense_page_try_again,
                )
            PageKind.OFFLINE ->
                PageSpec(
                    kind,
                    I.WIFI,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_offline_title,
                    R.string.usesense_page_offline_body,
                    primary = R.string.usesense_page_try_again,
                )
            PageKind.MIC_NEEDED ->
                PageSpec(
                    kind,
                    I.MIC,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_mic_title,
                    R.string.usesense_page_mic_body,
                    primary = R.string.usesense_open_settings,
                    secondary = R.string.usesense_page_mic_instead,
                )
            PageKind.APPROVED ->
                PageSpec(
                    kind,
                    I.CHECK,
                    S.TINT_GREEN_BG,
                    S.TINT_GREEN_FG,
                    R.string.usesense_page_approved_title,
                    R.string.usesense_page_approved_body,
                    primary = R.string.usesense_page_done,
                )
            PageKind.REVIEW ->
                PageSpec(
                    kind,
                    I.CLOCK,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_review_title,
                    R.string.usesense_page_review_body,
                    primary = R.string.usesense_page_done,
                )
            PageKind.REJECTED_RETRY ->
                PageSpec(
                    kind, I.X, S.TINT_RED_BG, S.TINT_RED_FG,
                    R.string.usesense_page_rejected_title,
                    if (retriesLeft > 1) R.string.usesense_page_rejected_retry_body_many else R.string.usesense_page_rejected_retry_body,
                    listOf(R.string.usesense_page_rejected_tip_light, R.string.usesense_page_rejected_tip_level),
                    R.string.usesense_page_try_again, R.string.usesense_not_now,
                )
            PageKind.REJECTED ->
                PageSpec(
                    kind,
                    I.X,
                    S.TINT_RED_BG,
                    S.TINT_RED_FG,
                    R.string.usesense_page_rejected_title,
                    R.string.usesense_page_rejected_body,
                    primary = R.string.usesense_page_done,
                )
            PageKind.EXPIRED ->
                PageSpec(
                    kind,
                    I.HOURGLASS,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_expired_title,
                    R.string.usesense_page_expired_body,
                    primary = R.string.usesense_start_again,
                )
            PageKind.ERROR ->
                PageSpec(
                    kind,
                    I.ALERT,
                    S.TINT_AMBER_BG,
                    S.TINT_AMBER_FG,
                    R.string.usesense_page_error_title,
                    R.string.usesense_page_error_body,
                    primary = R.string.usesense_page_try_again,
                )
        }

    /** The result page for a decision. "Try again" only while the integrator allows retries. */
    fun forDecision(
        decision: String,
        retriesLeft: Int,
    ): PageKind =
        when (decision) {
            UseSenseResult.DECISION_APPROVE -> PageKind.APPROVED
            UseSenseResult.DECISION_MANUAL_REVIEW -> PageKind.REVIEW
            else -> if (retriesLeft > 0) PageKind.REJECTED_RETRY else PageKind.REJECTED
        }

    /** The error page for a failure. [beforeUpload]: nothing was sent yet (offline means the connection). */
    fun forError(
        error: UseSenseError?,
        beforeUpload: Boolean,
    ): PageKind =
        when {
            error == null -> PageKind.ERROR
            error.code == UseSenseError.SESSION_EXPIRED ||
                error.serverCode?.contains("expired", ignoreCase = true) == true -> PageKind.EXPIRED
            error.code == UseSenseError.CAMERA_PERMISSION_DENIED -> PageKind.CAMERA_DENIED
            error.code == UseSenseError.CAMERA_UNAVAILABLE -> PageKind.CAMERA_ERROR
            beforeUpload && (error.code == UseSenseError.NETWORK_ERROR || error.code == UseSenseError.NETWORK_TIMEOUT) -> PageKind.OFFLINE
            else -> PageKind.ERROR
        }

    /** The storyboard's name for a page (the test rig's forceScreen), or null. */
    fun byStoryboardName(name: String): PageKind? =
        when (name) {
            "primer" -> PageKind.PRIMER
            "primer_two" -> PageKind.PRIMER_TWO
            "perm_denied" -> PageKind.CAMERA_DENIED
            "cam_error" -> PageKind.CAMERA_ERROR
            "offline" -> PageKind.OFFLINE
            "mic_denied" -> PageKind.MIC_NEEDED
            "approved" -> PageKind.APPROVED
            "review" -> PageKind.REVIEW
            "rejected_retry" -> PageKind.REJECTED_RETRY
            "rejected" -> PageKind.REJECTED
            "expired" -> PageKind.EXPIRED
            "error" -> PageKind.ERROR
            else -> null
        }
}
