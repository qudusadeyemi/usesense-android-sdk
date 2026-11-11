package com.usesense.demo

/**
 * LiveSense bench labels (livesense-phase3/tagging.md), carried in the
 * session's `external_user_id`:
 *
 *     lsb3_<run>_<subject>_<device>_<class>_<light>_<seq>
 *     lsb3_r1_s03_a03_photo-gloss_L2_0412
 *
 * A typo makes a session invisible to the report or files it under the wrong
 * species, so a label that starts with `lsb3` is checked before the session
 * starts. Anything else (a plain user id) passes through untouched.
 */
object BenchLabel {
    private const val PREFIX = "lsb3"
    private val RUN = Regex("r[a-z0-9]+")
    private val SUBJECT = Regex("s(0[1-9]|10)")

    /** The bench's device codes are a list that grows: lowercase letters and digits. */
    private val DEVICE = Regex("[a-z0-9]+")
    private val CLASS = Regex("genuine|(photo|screen|inject|mask)(-[a-z0-9]+)+|cutout(-[a-z0-9]+)*")
    private val LIGHT = Regex("L[123]")
    private val SEQ = Regex("[0-9]{4}")

    /** What's wrong with [label] as a bench label, or null when it's fine (or isn't one). */
    fun problem(label: String): String? {
        if (!label.lowercase().startsWith(PREFIX)) return null
        val parts = label.split('_')
        if (parts[0] != PREFIX) return "Bench labels start with \"lsb3_\""
        if (parts.size != 7) return "A bench label has 7 parts: lsb3_run_subject_device_class_light_seq"
        val (_, run, subject, device, cls, light) = parts
        val seq = parts[6]
        return when {
            !RUN.matches(run) -> "Run should look like r1"
            !SUBJECT.matches(subject) -> "Subject should be s01 to s10"
            !DEVICE.matches(device) -> "Device should be a code like a03"
            !CLASS.matches(cls) -> "Unknown class \"$cls\" (genuine, photo-…, screen-…, cutout, inject-…, mask-…)"
            !LIGHT.matches(light) -> "Light should be L1, L2 or L3"
            !SEQ.matches(seq) -> "Seq should be 4 digits"
            else -> null
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
