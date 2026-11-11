package com.usesense.sdk.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * BRAND-v1 section 9: every capture element reads a theme token or is on the
 * fixed list. Fails on a new literal colour in the capture UI code or its
 * layout. Fixed colours live in CaptureFixed / SenseDefaults
 * (ui/theme/CaptureTheme.kt), the only capture file allowed literals.
 */
class CaptureColourGuardTest {

    private val main: File = listOf("src/main", "sdk/src/main").map(::File).first { it.exists() }
    private val java = File(main, "java/com/usesense/sdk")
    private val layout = File(main, "res/layout/activity_usesense_hosted.xml")

    /** Capture UI code: the hosted capture activity and every view and helper it draws with. */
    private val captureCode = listOf(
        "ui/HostedPageActivity.kt",
        "ui/OutcomePages.kt",
        "ui/StrokeIconView.kt",
        "ui/ExtraCheckOverlayView.kt",
        "ui/ContourScanView.kt",
        "ui/theme/CaptureStyler.kt",
        "ui/theme/CaptureLoaderView.kt",
        "ui/theme/CaptureImages.kt",
        "ui/theme/CaptureStrings.kt",
        "capture/extracheck/ExtraCheckCopy.kt",
        "capture/extracheck/MicroActionDetector.kt",
    )

    /**
     * Drawables the hosted layout still references. Each is camera chrome on
     * the fixed list (BRAND-v1 4.2: the camera is dark, brand only as accents),
     * or a glyph the theme tints at runtime.
     */
    private val fixedDrawables = mapOf(
        "usesense_shutter_bg" to "shutter button (fixed list)",
        "usesense_ic_arrow_back" to "camera back button glyph, tinted at runtime",
        "usesense_icon_camera" to "page glyph, tinted with the theme",
        "usesense_icon_success" to "page glyph, tinted with the theme",
        // The pre-redesign challenge overlay: still in the layout, never shown (see legacyHidden).
        "usesense_dot" to "legacy challenge overlay, never shown",
        "usesense_badge_bg" to "legacy challenge overlay, never shown",
        "usesense_countdown_circle" to "legacy countdown overlay, never shown",
        "usesense_pill_bg" to "legacy countdown overlay, never shown",
        "usesense_instructions_modal_bg" to "legacy instructions overlay, never shown",
        "usesense_instructions_icon_circle" to "legacy instructions overlay, never shown",
        "usesense_instructions_cta_bg" to "legacy instructions overlay, never shown",
        "usesense_phrase_bg" to "legacy speak-phrase overlay, never shown",
        "usesense_progress_track" to "legacy challenge progress, never shown (tinted with the ring anyway)",
    )

    /** Views of the pre-redesign challenge UI that the hosted activity never makes visible. */
    private val legacyHidden = listOf(
        "challengeOverlay", "baselineOvalView", "dotView", "directionCircle", "phaseBadge",
        "qualityIndicator", "countdownOverlay", "instructionsOverlay", "qualityBanner",
        "directionText", "speakPhraseLayout", "challengeProgress", "recordingIndicator", "captureFooterText",
    )

    /** Literal colours in Kotlin: hex strings, colour-sized hex ints, android.graphics.Color constants and builders. */
    private val kotlinLiteral = Regex(
        """"#[0-9A-Fa-f]{3,8}"|\b0x[0-9A-Fa-f]{6,8}\b|\bColor\.(argb|rgb|valueOf|WHITE|BLACK|RED|GREEN|BLUE|GRAY|GREY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\b|\bColor\.parseColor\("|\bColor\(0x""",
    )

    /** Literal colours in XML: hex values, colour resources (static, so never branded), platform colours. */
    private val xmlLiteral = Regex("""#[0-9A-Fa-f]{3,8}\b|@color/|@android:color/(?!transparent)""")

    private fun stripComments(src: String): String =
        src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?<![:"])//[^\n]*"""), "")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")

    @Test fun `no literal colour in capture UI code`() {
        val hits = captureCode.flatMap { path ->
            val f = File(java, path)
            assertTrue("missing $path", f.exists())
            stripComments(f.readText()).lines().mapIndexedNotNull { i, line ->
                kotlinLiteral.find(line)?.let { "$path:${i + 1}: ${it.value}  ($line)" }
            }
        }
        if (hits.isNotEmpty()) fail("Literal colours in capture UI (use CaptureTheme tokens or CaptureFixed):\n" + hits.joinToString("\n"))
    }

    @Test fun `no literal colour in the capture layout`() {
        val hits = stripComments(layout.readText()).lines().mapIndexedNotNull { i, line ->
            xmlLiteral.find(line)?.let { "activity_usesense_hosted.xml:${i + 1}: ${it.value}" }
        }
        if (hits.isNotEmpty()) fail("Literal colours in the capture layout (give the view a theme role tag):\n" + hits.joinToString("\n"))
    }

    @Test fun `every drawable the layout uses is themed at runtime or on the fixed list`() {
        val used = Regex("""@drawable/([a-z0-9_]+)""").findAll(layout.readText()).map { it.groupValues[1] }.toSet()
        val unknown = used - fixedDrawables.keys
        assertTrue("Drawables with no theme or fixed-list reason: $unknown", unknown.isEmpty())
    }

    @Test fun `the legacy overlays allowed above are never shown`() {
        val src = File(java, "ui/HostedPageActivity.kt").readText()
        legacyHidden.forEach { v ->
            assertTrue("$v is shown; theme it instead", !Regex("""\b$v\.visibility\s*=\s*View\.VISIBLE""").containsMatchIn(src))
        }
    }

    @Test fun `every themed view in the layout names a known role`() {
        val roles = CaptureStyler.Role::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }.toSet()
        val tags = Regex("""android:tag="([^"]+)"""").findAll(layout.readText()).map { it.groupValues[1] }.toSet()
        assertTrue("Unknown roles: ${tags - roles}", (tags - roles).isEmpty())
    }

    @Test fun `the guard catches a literal`() {
        listOf("""setColor(Color.parseColor("#7A96FF"))""", "val c = 0xFF4F7CFF.toInt()", "Color.WHITE", """x = "#fff"""")
            .forEach { assertTrue(it, kotlinLiteral.containsMatchIn(it)) }
        listOf("""android:textColor="#475569"""", """android:background="@color/usesense_primary"""")
            .forEach { assertTrue(it, xmlLiteral.containsMatchIn(it)) }
    }
}
