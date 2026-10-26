package com.usesense.sdk.flows

/**
 * Location capture, address ladder rung 0.
 *
 * The contract-critical half is kept pure and free of the Android framework on
 * purpose. What goes on the wire is a frozen contract that five clients
 * implement independently (see location-capture-contract.md in the watchtower
 * repo), so payload construction is a plain function with JVM unit tests
 * rather than something buried in a location callback.
 *
 * The governing rule of this whole surface: it never terminates the step in
 * failure. A denied permission, location switched off at the system level, a
 * fix that never arrives; every one of those still submits the descriptors and
 * advances, at a lower rung. Blocking would abandon exactly the subjects the
 * capture ladder exists to include, and a low-confidence record that can be
 * upgraded is worth more than a failed onboarding.
 */

/** How a subject's position was established, strongest first. */
enum class CaptureRung(val wire: String) {
    VALIDATED_AUTOCOMPLETE("validated_autocomplete"),
    AT_THE_DOOR("at_the_door"),
    PASSIVE_INFERENCE("passive_inference"),
    WALK_TRACE("walk_trace"),
    SPOKEN_DESCRIPTION("spoken_description"),
    NEIGHBOUR_MATCH("neighbour_match"),
    AGENT_VISIT("agent_visit");

    companion object {
        /**
         * Unknown values return null rather than throwing. The server takes
         * the weaker of its own ceiling and whatever we report, so falling
         * back costs nothing and cannot overstate the evidence.
         */
        fun fromWire(value: String?): CaptureRung? = entries.firstOrNull { it.wire == value }
    }
}

/** A position fix as the device reports it. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in metres, when the device reported one. */
    val accuracyM: Float? = null,
    /** Passed hardware attestation and mock-provider checks. */
    val attested: Boolean = false,
)

/** What the surface knows about the position, for the status line. */
enum class LocationCaptureState { IDLE, ACQUIRING, READY, DENIED, UNAVAILABLE }

object LocationCapture {

    /**
     * A fix is usable only if both coordinates are in range and it is not null
     * island.
     *
     * `0, 0` means the device failed and reported zeroes. Submitting it would
     * mint a Place in the Gulf of Guinea inside a registry shared across every
     * customer, and a poisoned Place propagates to every future subject who
     * resolves onto it. The server rejects it too; catching it here saves a
     * pointless round trip and lets the subject retry.
     */
    @JvmStatic
    fun isUsableFix(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude.isInfinite() || longitude.isInfinite()) return false
        if (latitude < -90.0 || latitude > 90.0) return false
        if (longitude < -180.0 || longitude > 180.0) return false
        if (latitude == 0.0 && longitude == 0.0) return false
        return true
    }

    /**
     * Builds the exact `inputs` payload for an advance call.
     *
     * Two rules from the contract are enforced here rather than trusted to the
     * caller. No fix means no coordinates at all and the rung drops to
     * `spoken_description`, because the descriptors are still worth having and
     * the rung says precisely what this evidence is. And we never report a
     * rung higher than we performed.
     */
    @JvmStatic
    @JvmOverloads
    fun buildInputs(
        fix: LocationFix?,
        descriptors: Map<String, Any>,
        requestedRung: CaptureRung?,
        frontageDocumentId: String? = null,
    ): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(descriptors)
        if (!frontageDocumentId.isNullOrEmpty()) {
            out["frontage_document_id"] = frontageDocumentId
        }

        if (fix == null || !isUsableFix(fix.latitude, fix.longitude)) {
            out["rung"] = CaptureRung.SPOKEN_DESCRIPTION.wire
            return out
        }

        out["latitude"] = fix.latitude
        out["longitude"] = fix.longitude
        fix.accuracyM
            ?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0f }
            ?.let { out["accuracy_m"] = Math.round(it) }
        out["attested"] = fix.attested
        out["rung"] = (requestedRung ?: CaptureRung.AT_THE_DOOR).wire
        return out
    }

    /**
     * Human status text for each state.
     *
     * The denied and unavailable strings both say the subject can continue,
     * because they can. Telling them otherwise is how a recoverable moment
     * becomes an abandoned onboarding.
     */
    @JvmStatic
    @JvmOverloads
    fun statusText(state: LocationCaptureState, accuracyM: Float? = null): String? = when (state) {
        LocationCaptureState.IDLE -> null
        LocationCaptureState.ACQUIRING -> "Finding your location…"
        LocationCaptureState.READY ->
            if (accuracyM != null && !accuracyM.isNaN()) {
                "Location found, accurate to about ${Math.round(accuracyM)} m"
            } else {
                "Location found"
            }
        LocationCaptureState.DENIED ->
            "Location is off. You can still continue by describing where you live."
        LocationCaptureState.UNAVAILABLE ->
            "We could not get your location. You can still continue below."
    }
}
