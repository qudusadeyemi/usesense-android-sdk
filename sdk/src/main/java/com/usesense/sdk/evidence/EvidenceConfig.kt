package com.usesense.sdk.evidence

/**
 * The optional `evidence` block of a session-create response (EVIDENCE-v1
 * section 1): what Phase 2 evidence the server wants. The SDK collects it,
 * the server scores it.
 *
 * Decoded by hand from Moshi's untyped value on purpose (a strict decoder
 * broke Shuttlers on 2026-06-22): every key is optional, unknown keys are
 * ignored, a malformed key reads as absent, and a `contract` other than
 * "evidence-v1" makes the whole block absent. Old servers never send it.
 */
data class EvidenceConfig(
    val colourSequence: ColourSequence? = null,
    val closerStep: CloserStep? = null,
    val bindingV2Enabled: Boolean = false,
    val landmarksRequired: Boolean = false,
) {
    data class ColourSequence(
        val id: String,
        /** `#RRGGBB`, uppercased. Played in this order; never reordered or cached. */
        val colours: List<String>,
        val segmentMs: Int = 600,
        val leadInMs: Int = 300,
        val opacity: Double = 0.35,
    ) {
        /** Lead-in plus every segment. */
        val totalMs: Int get() = leadInMs + segmentMs * colours.size
    }

    data class CloserStep(
        val enabled: Boolean,
        val targetScale: Double = 1.4,
        val minScale: Double = 1.25,
    )

    val closerEnabled: Boolean get() = closerStep?.enabled == true

    companion object {
        const val CONTRACT_V1 = "evidence-v1"

        /** The block from Moshi's untyped value (a Map), or null when absent, malformed or another contract. */
        fun parse(raw: Any?): EvidenceConfig? = runCatching {
            val m = raw as? Map<*, *> ?: return null
            if (m["contract"] != CONTRACT_V1) return null
            EvidenceConfig(
                colourSequence = parseColours(m["colour_sequence"]),
                closerStep = parseCloser(m["closer_step"]),
                bindingV2Enabled = (m["binding_v2"] as? Map<*, *>)?.get("enabled") == true,
                landmarksRequired = (m["landmarks"] as? Map<*, *>)?.get("required") == true,
            )
        }.getOrNull()

        /** A sequence with no id or an unreadable colour is not playable: no sequence. */
        private fun parseColours(raw: Any?): ColourSequence? {
            val m = raw as? Map<*, *> ?: return null
            val id = (m["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val list = m["colours"] as? List<*> ?: return null
            val colours = list.mapNotNull { (it as? String)?.let(::normalisedColour) }
            if (colours.isEmpty() || colours.size != list.size) return null
            return ColourSequence(
                id = id,
                colours = colours,
                segmentMs = number(m["segment_ms"])?.toInt()?.coerceIn(100, 2000) ?: 600,
                leadInMs = number(m["lead_in_ms"])?.toInt()?.coerceIn(0, 2000) ?: 300,
                opacity = number(m["opacity"])?.coerceIn(0.05, 1.0) ?: 0.35,
            )
        }

        private fun parseCloser(raw: Any?): CloserStep? {
            val m = raw as? Map<*, *> ?: return null
            val target = (number(m["target_scale"]) ?: 1.4).coerceIn(1.05, 2.0)
            val min = (number(m["min_scale"]) ?: 1.25).coerceIn(1.05, target)
            return CloserStep(enabled = m["enabled"] == true, targetScale = target, minScale = min)
        }

        private fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

        /** `#rrggbb` (or without `#`) to `#RRGGBB`; anything else null. */
        internal fun normalisedColour(s: String): String? {
            val hex = s.removePrefix("#")
            if (hex.length != 6 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return "#" + hex.uppercase()
        }
    }
}
