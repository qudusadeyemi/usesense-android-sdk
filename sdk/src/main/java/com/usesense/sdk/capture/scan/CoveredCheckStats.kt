package com.usesense.sdk.capture.scan

import com.usesense.sdk.liveness.SegmenterModelInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The covered-face check's numbers for the signals upload, as
 * `metadata.covered_check` (capture-test-kit COVER-v1.md section 3). Numbers
 * only: no images, no landmarks. Sent so real-world score distributions can
 * set the limits later; it changes no decision.
 *
 * Counters cover the whole capture; the per-scan fields (at_scan_start,
 * scan_max, final, change_watch.max) describe the last scan attempt, the one
 * uploaded. Written from the segmenter thread, the gate thread and the main
 * thread, so every method is synchronized.
 */
internal class CoveredCheckStats {
    private var modelLoadMs: Long? = null
    private var modelError: String? = null
    private var modelLoaded = false
    private val runMs = ArrayList<Long>()

    private var atScanStart: FaceCover? = null
    private var atScanStartAgeMs: Long? = null
    private var scanMaxBodySkin = 0f
    private var scanMaxOther = 0f
    private var finalCover: FaceCover? = null
    private var finalWaitedMs: Long? = null
    private var finalOutcome: String? = null

    private var alignCoveredMs = 0L
    private var scanDrops = 0
    private var changeTrips = 0
    private var singleFrameFlags = 0
    private var rebaselines = 0
    private var baselineWaitMs: Long? = null
    private var changeMaxLuma = 0f
    private var changeMaxChroma = 0f
    private var changeMaxEdge = 1f
    private val tripRegions = LinkedHashSet<String>()

    /** The model load was attempted (loaded or failed). */
    val tried: Boolean @Synchronized get() = modelLoadMs != null

    @Synchronized fun modelLoaded(ms: Long) {
        modelLoaded = true
        modelLoadMs = ms
        modelError = null
    }

    @Synchronized fun modelFailed(ms: Long, error: String) {
        modelLoaded = false
        modelLoadMs = ms
        modelError = error.take(200)
    }

    @Synchronized fun run(ms: Long) {
        runMs += ms
    }

    /** A scan started: the latest segmentation result and its age. Resets the per-scan fields. */
    @Synchronized fun scanStarted(cover: FaceCover?, ageMs: Long?) {
        atScanStart = cover
        atScanStartAgeMs = if (cover != null) ageMs else null
        scanMaxBodySkin = 0f
        scanMaxOther = 0f
        finalCover = null
        finalWaitedMs = null
        finalOutcome = null
        changeMaxLuma = 0f
        changeMaxChroma = 0f
        changeMaxEdge = 1f
        baselineWaitMs = null
    }

    @Synchronized fun scanFrame(cover: FaceCover) {
        scanMaxBodySkin = max(scanMaxBodySkin, cover.bodySkin)
        scanMaxOther = max(scanMaxOther, cover.other)
    }

    /** The final check before finishing: clear | covered | timeout | skipped. */
    @Synchronized fun finalCheck(cover: FaceCover?, waitedMs: Long?, outcome: String) {
        finalCover = cover
        finalWaitedMs = waitedMs
        finalOutcome = outcome
    }

    @Synchronized fun alignCovered(ms: Long) {
        if (ms > 0) alignCoveredMs += ms
    }

    /** A scan dropped back to align for cover (segmenter or final check). */
    @Synchronized fun scanDropForCover() {
        scanDrops++
    }

    /** One scan frame compared against the change-watch baseline. */
    @Synchronized fun changeWatchFrame(lumaDelta: Float, chromaDelta: Float, edgeFactor: Float) {
        changeMaxLuma = max(changeMaxLuma, lumaDelta)
        changeMaxChroma = max(changeMaxChroma, chromaDelta)
        changeMaxEdge = max(changeMaxEdge, edgeFactor)
    }

    /** The scan's steady change-watch baseline was taken [ms] after the scan started (first per scan). */
    @Synchronized fun changeWatchBaselineTaken(ms: Long) {
        if (baselineWaitMs == null) baselineWaitMs = ms.coerceAtLeast(0L)
        debug("change watch baseline taken ${ms} ms after scan start")
    }

    /** A new change-watch baseline after a fill-light change. */
    @Synchronized fun changeWatchRebaseline() {
        rebaselines++
    }

    /** One-frame exceedances the 2-frame rule ignored. */
    @Synchronized fun changeWatchSingleFrameFlags(n: Int) {
        singleFrameFlags += n
    }

    /** The change watch started failing (it pauses the scan); [region] as in FaceRegions.NAMES. */
    @Synchronized fun changeWatchTrip(region: String?) {
        changeTrips++
        debug("change watch trip #$changeTrips on $region")
        region?.let { tripRegions += it }
    }

    @Synchronized fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("model_sha256_prefix", if (modelLoaded) SegmenterModelInfo.SHA256.take(8) else JSONObject.NULL)
        put("model_load_ms", modelLoadMs ?: JSONObject.NULL)
        put("model_error", modelError ?: JSONObject.NULL)
        put("limits", JSONObject().apply {
            // Rounded: Float -> Double would write 0.11999999731779099.
            put("body_skin", r3(FaceCover.BODY_SKIN_MAX))
            put("other", r3(FaceCover.OTHER_MAX))
            put("shrink", r3(FaceCover.INNER))
        })
        put("runs", runMs.size)
        val sorted = runMs.sorted()
        put("run_ms_p50", if (sorted.isEmpty()) JSONObject.NULL else sorted[sorted.size / 2])
        put("run_ms_max", sorted.lastOrNull() ?: JSONObject.NULL)
        put("at_scan_start", atScanStart?.let { split(it).put("age_ms", atScanStartAgeMs ?: JSONObject.NULL) } ?: JSONObject.NULL)
        put("scan_max", JSONObject().apply {
            put("body_skin", r3(scanMaxBodySkin))
            put("other", r3(scanMaxOther))
        })
        put("final", if (finalOutcome == null) {
            JSONObject.NULL
        } else {
            (finalCover?.let { split(it) } ?: JSONObject())
                .put("waited_ms", finalWaitedMs ?: JSONObject.NULL)
                .put("outcome", finalOutcome)
        })
        put("align_covered_ms", alignCoveredMs)
        put("scan_drops", scanDrops)
        put("change_watch", JSONObject().apply {
            put("trips", changeTrips)
            put("single_frame_flags", singleFrameFlags)
            put("rebaselines", rebaselines)
            put("baseline_wait_ms", baselineWaitMs ?: JSONObject.NULL)
            put("max", JSONObject().apply {
                put("luma_ratio_delta", r3(changeMaxLuma))
                put("chroma_delta", r3(changeMaxChroma))
                put("edge_factor", r3(changeMaxEdge))
            })
            put("trip_regions", JSONArray(tripRegions.toList()))
        })
    }

    private fun split(c: FaceCover) = JSONObject().apply {
        put("bg", r3(c.background))
        put("hair", r3(c.hair))
        put("body_skin", r3(c.bodySkin))
        put("face_skin", r3(c.faceSkin))
        put("clothes", r3(c.clothes))
        put("others", r3(c.others))
    }

    private fun r3(v: Float): Double = (v * 1000f).roundToInt() / 1000.0

    private fun debug(message: String) {
        runCatching { if (android.util.Log.isLoggable("UseSenseGate", android.util.Log.DEBUG)) android.util.Log.d("UseSenseGate", message) }
    }

    /** What a finished scan does about the final covered-face check. */
    enum class FinalPlan {
        /** Wait for a segmenter result from after the last banked frame (clear, covered or timeout). */
        WAIT,
        /** No segmenter (model missing or failed to load): outcome "skipped", as COVER-v1 defines it. */
        SKIP_NO_MODEL,
        /** Debug test rig forced the gate to pass: outcome "rig_forced", so the upload never passes for a real skip. */
        SKIP_RIG_FORCED,
    }

    companion object {
        const val VERSION = "cover-v1"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_RIG_FORCED = "rig_forced"

        fun finalPlan(coverCheckOn: Boolean, rigForcesPass: Boolean): FinalPlan =
            when {
                !coverCheckOn -> FinalPlan.SKIP_NO_MODEL
                rigForcesPass -> FinalPlan.SKIP_RIG_FORCED
                else -> FinalPlan.WAIT
            }

        fun skipOutcome(plan: FinalPlan): String =
            if (plan == FinalPlan.SKIP_RIG_FORCED) OUTCOME_RIG_FORCED else OUTCOME_SKIPPED
    }
}
