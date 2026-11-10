package com.usesense.sdk.ui

import com.usesense.sdk.R
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Storyboard flow 6: which page shows, and "Try again" only while retries are allowed. */
class OutcomePagesTest {
    @Test
    fun `results by decision, retry only with retries left`() {
        assertEquals(PageKind.APPROVED, OutcomePages.forDecision(UseSenseResult.DECISION_APPROVE, 3))
        assertEquals(PageKind.REVIEW, OutcomePages.forDecision(UseSenseResult.DECISION_MANUAL_REVIEW, 3))
        assertEquals(PageKind.REJECTED, OutcomePages.forDecision(UseSenseResult.DECISION_REJECT, 0))
        assertEquals(PageKind.REJECTED_RETRY, OutcomePages.forDecision(UseSenseResult.DECISION_REJECT, 1))
    }

    @Test
    fun `the retry page has tips and Not now, the plain reject points back to the app`() {
        val retry = OutcomePages.spec(PageKind.REJECTED_RETRY, retriesLeft = 1)
        assertEquals(R.string.usesense_page_try_again, retry.primary)
        assertEquals(R.string.usesense_not_now, retry.secondary)
        assertEquals(2, retry.tips.size)
        assertEquals(R.string.usesense_page_rejected_retry_body, retry.body)
        assertEquals(R.string.usesense_page_rejected_retry_body_many, OutcomePages.spec(PageKind.REJECTED_RETRY, 2).body)
        val plain = OutcomePages.spec(PageKind.REJECTED)
        assertEquals(R.string.usesense_page_rejected_body, plain.body)
        assertNull(plain.secondary)
    }

    @Test
    fun `errors map to the storyboard pages`() {
        assertEquals(PageKind.EXPIRED, OutcomePages.forError(UseSenseError.sessionExpired(), beforeUpload = false))
        assertEquals(PageKind.EXPIRED, OutcomePages.forError(UseSenseError.fromServerError(401, "session_expired", "x"), beforeUpload = true))
        assertEquals(PageKind.OFFLINE, OutcomePages.forError(UseSenseError.networkError("no route"), beforeUpload = true))
        assertEquals(PageKind.ERROR, OutcomePages.forError(UseSenseError.networkError("no route"), beforeUpload = false))
        assertEquals(PageKind.CAMERA_DENIED, OutcomePages.forError(UseSenseError.cameraPermissionDenied(), beforeUpload = true))
        assertEquals(PageKind.CAMERA_ERROR, OutcomePages.forError(UseSenseError.cameraUnavailable(), beforeUpload = true))
        assertEquals(PageKind.ERROR, OutcomePages.forError(UseSenseError.fromServerError(409, "fallback_not_allowed", "x"), beforeUpload = true))
        assertEquals(PageKind.ERROR, OutcomePages.forError(null, beforeUpload = true))
    }

    @Test
    fun `primer has two versions and the mic page offers a head movement`() {
        assertEquals(R.string.usesense_primer_title, OutcomePages.spec(PageKind.PRIMER).title)
        assertEquals(R.string.usesense_primer_two_title, OutcomePages.spec(PageKind.PRIMER_TWO).title)
        assertEquals(3, OutcomePages.spec(PageKind.PRIMER_TWO).tips.size)
        assertEquals(R.string.usesense_page_mic_instead, OutcomePages.spec(PageKind.MIC_NEEDED).secondary)
        assertEquals(R.string.usesense_open_settings, OutcomePages.spec(PageKind.CAMERA_DENIED).primary)
    }
}
