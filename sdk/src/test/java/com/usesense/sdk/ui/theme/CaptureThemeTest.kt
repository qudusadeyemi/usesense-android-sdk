package com.usesense.sdk.ui.theme

import com.usesense.sdk.flows.AppearanceBackground
import com.usesense.sdk.flows.AppearanceCapture
import com.usesense.sdk.flows.AppearanceColors
import com.usesense.sdk.flows.AppearanceLoader
import com.usesense.sdk.flows.AppearanceLogo
import com.usesense.sdk.flows.AppearanceShape
import com.usesense.sdk.flows.AppearanceTypography
import com.usesense.sdk.flows.FlowAppearance
import com.usesense.sdk.flows.mergeAppearance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BRAND-v1 section 4: one resolved capture theme, page tokens per mode, gated camera accents. */
class CaptureThemeTest {

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** BRAND-v1 section 8. */
    private val acmeBank = FlowAppearance(
        mode = FlowAppearance.Mode.LIGHT,
        colors = AppearanceColors(
            primary = "#1F7A4D", background = "#FFFFFF", surface = "#F3F6F4",
            foreground = "#13261C", muted = "#5B6B62", border = "#D8E0DB",
        ),
        typography = AppearanceTypography(displayFamily = "Georgia", fontFamily = "Inter"),
        shape = AppearanceShape(radius = 12, buttonRadius = 999, buttonStyle = AppearanceShape.ButtonStyle.FILLED),
        logo = AppearanceLogo(url = "https://cdn.example/acme.png", placement = AppearanceLogo.Placement.HEADER, height = 28),
    )

    private val nightPay = FlowAppearance(
        mode = FlowAppearance.Mode.DARK,
        colors = AppearanceColors(
            primary = "#3A1D8F",
            dark = AppearanceColors(background = "#0B0B12", surface = "#16162A", foreground = "#EDEBFF", muted = "#9C98C9"),
        ),
        capture = AppearanceCapture(challenge = "#FF4FA3"),
        shape = AppearanceShape(buttonRadius = 4, buttonStyle = AppearanceShape.ButtonStyle.OUTLINE),
    )

    // ── Sense defaults ──────────────────────────────────────────────────

    @Test fun `no appearance is today's dark capture look`() {
        val t = CaptureTheme.resolve(null, systemDark = false)
        assertTrue(t.isDark)
        assertEquals("#12141B", hex(t.page.background))
        assertEquals("#1D2029", hex(t.page.surface))
        assertEquals("#F4F5F8", hex(t.page.foreground))
        assertEquals("#A3A9B8", hex(t.page.muted))
        assertEquals(0x14FFFFFF, t.page.border)
        assertEquals("#4F7CFF", hex(t.page.primary))
        assertEquals("#00D4AA", hex(t.page.success))
        assertEquals("#FF6B4A", hex(t.page.error))
        assertEquals("#FFB84D", hex(t.page.warning))
        assertEquals(14, t.shape.buttonRadiusDp)
        assertEquals(20, t.shape.dialogRadiusDp)
        assertEquals(28, t.shape.sheetRadiusDp)
        assertEquals(12, t.shape.tileRadiusDp)
        assertEquals(AppearanceShape.ButtonStyle.FILLED, t.shape.buttonStyle)
        assertEquals(AppearanceLoader.Style.SPINNER, t.loader.style)
        assertEquals(28, t.logo.heightDp)
        assertNull(t.type.displayFamily)
        assertNull(t.type.fontFamily)
    }

    @Test fun `light Sense defaults`() {
        val t = CaptureTheme.resolve(FlowAppearance(mode = FlowAppearance.Mode.LIGHT), systemDark = true)
        assertFalse(t.isDark)
        assertEquals("#FDFCFA", hex(t.page.background))
        assertEquals("#F5F3EF", hex(t.page.surface))
        assertEquals("#1C1A17", hex(t.page.foreground))
        assertEquals("#6B6760", hex(t.page.muted))
        assertEquals("#E8E5DE", hex(t.page.border))
    }

    @Test fun `mode auto follows the device, unset mode uses the surface's default`() {
        val auto = FlowAppearance(mode = FlowAppearance.Mode.AUTO)
        assertTrue(CaptureTheme.resolve(auto, systemDark = true).isDark)
        assertFalse(CaptureTheme.resolve(auto, systemDark = false).isDark)
        // Unset: capture pages stay dark even on a light device; a remote link's screens stay light.
        assertTrue(CaptureTheme.resolve(FlowAppearance(), systemDark = false).isDark)
        assertFalse(CaptureTheme.resolve(FlowAppearance(), systemDark = true, unsetMode = FlowAppearance.Mode.LIGHT).isDark)
    }

    @Test fun `primary foreground is white or ink, whichever reads better`() {
        assertEquals(0xFFFFFFFF.toInt(), CaptureTheme.resolve(acmeBank, false).page.primaryForeground)
        val yellow = FlowAppearance(colors = AppearanceColors(primary = "#FFD400"))
        assertEquals(SenseDefaults.INK, CaptureTheme.resolve(yellow, false).page.primaryForeground)
        val set = FlowAppearance(colors = AppearanceColors(primary = "#FFD400", primaryForeground = "#112233"))
        assertEquals("#112233", hex(CaptureTheme.resolve(set, false).page.primaryForeground))
    }

    // ── Test brands (section 8) ─────────────────────────────────────────

    @Test fun `Acme Bank resolves its light palette, fonts and shape`() {
        val t = CaptureTheme.resolve(acmeBank, systemDark = true)
        assertFalse(t.isDark)
        assertEquals("#FFFFFF", hex(t.page.background))
        assertEquals("#F3F6F4", hex(t.page.surface))
        assertEquals("#13261C", hex(t.page.foreground))
        assertEquals("#5B6B62", hex(t.page.muted))
        assertEquals("#D8E0DB", hex(t.page.border))
        assertEquals("#1F7A4D", hex(t.page.primary))
        assertEquals("Georgia", t.type.displayFamily)
        assertEquals("Inter", t.type.fontFamily)
        assertEquals(999, t.shape.buttonRadiusDp)
        assertEquals(12, t.shape.dialogRadiusDp)
        assertEquals(24, t.shape.sheetRadiusDp) // min(2 x 12, 28)
        assertEquals(12, t.shape.tileRadiusDp)
        assertEquals("https://cdn.example/acme.png", t.logo.url)
        // #1F7A4D is 2.4:1 on the camera (under 3:1), so the ring is the brand green lightened.
        assertFalse(CaptureTheme.passes(t.page.primary))
        assertEquals("#248E5A", hex(t.camera.ring))
        assertTrue(CaptureTheme.passes(t.camera.ring))
    }

    @Test fun `Night Pay takes its dark layer, the Sense dark default for unset keys, and outline buttons`() {
        val t = CaptureTheme.resolve(nightPay, systemDark = false)
        assertTrue(t.isDark)
        assertEquals("#0B0B12", hex(t.page.background))
        assertEquals("#16162A", hex(t.page.surface))
        assertEquals("#EDEBFF", hex(t.page.foreground))
        assertEquals("#9C98C9", hex(t.page.muted))
        assertEquals(SenseDefaults.DARK.border, t.page.border) // colors.dark.border unset: derive nothing
        assertEquals("#3A1D8F", hex(t.page.primary)) // a brand colour serves both modes
        assertEquals(4, t.shape.buttonRadiusDp)
        assertEquals(AppearanceShape.ButtonStyle.OUTLINE, t.shape.buttonStyle)
        assertEquals("#FF4FA3", hex(t.camera.challenge))
    }

    // ── Contrast gating and lightening (section 4.2) ────────────────────

    @Test fun `Night Pay's primary fails on the camera, so the ring comes out lightened`() {
        val primary = CaptureColor.parse("#3A1D8F")!!
        assertFalse(CaptureTheme.passes(primary))
        val ring = CaptureTheme.resolve(nightPay, false).camera.ring
        assertNotEquals(primary, ring)
        assertNotEquals(SenseDefaults.PRIMARY, ring)
        assertTrue(CaptureColor.contrast(ring, CaptureFixed.CONTRAST_REFERENCE) >= 3.0)
        // The smallest number of 5% steps that passes: one step fewer still fails.
        var c = primary
        var steps = 0
        while (!CaptureTheme.passes(c)) { c = CaptureColor.lighten(c, 0.05f); steps++ }
        assertEquals(c, ring)
        assertTrue(steps in 1..8)
        // Same hue: lightening keeps it Night Pay purple.
        assertEquals(CaptureColor.toHsl(primary)[0], CaptureColor.toHsl(ring)[0], 1.5f)
    }

    @Test fun `ring order - capture ring, then primary, then lightened primary, then Sense blue`() {
        val passingRing = FlowAppearance(capture = AppearanceCapture(ring = "#22D3EE"), colors = AppearanceColors(primary = "#2EC27E"))
        assertEquals("#22D3EE", hex(CaptureTheme.cameraTokens(passingRing).ring))
        val failingRing = FlowAppearance(capture = AppearanceCapture(ring = "#111111"), colors = AppearanceColors(primary = "#2EC27E"))
        assertEquals("#2EC27E", hex(CaptureTheme.cameraTokens(failingRing).ring))
        // A dark mode primary is the camera's primary.
        val darkPrimary = FlowAppearance(colors = AppearanceColors(primary = "#123456", dark = AppearanceColors(primary = "#2EC27E")))
        assertEquals("#2EC27E", hex(CaptureTheme.cameraTokens(darkPrimary).ring))
        // Black can't be lightened to 3:1 in 8 steps (L 0 -> 40%: #666666 is 2.2:1): Sense blue.
        val black = FlowAppearance(colors = AppearanceColors(primary = "#000000"))
        assertEquals(SenseDefaults.PRIMARY, CaptureTheme.cameraTokens(black).ring)
        assertEquals(SenseDefaults.PRIMARY, CaptureTheme.cameraTokens(null).ring)
    }

    @Test fun `challenge, warning and success are gated with their own fallbacks`() {
        val dark = FlowAppearance(
            capture = AppearanceCapture(challenge = "#202020", warning = "#101010"),
            colors = AppearanceColors(warning = "#F5A524", success = "#0A2A1A"),
        )
        val c = CaptureTheme.cameraTokens(dark)
        assertEquals(SenseDefaults.CHALLENGE, c.challenge) // no lightening for challenge
        assertEquals("#F5A524", hex(c.warning)) // capture.warning fails -> colors.warning
        assertEquals(SenseDefaults.SUCCESS, c.success) // colors.success fails -> #00D4AA
        assertEquals("#A878FC", hex(CaptureTheme.cameraTokens(null).challenge))
    }

    @Test fun `every Sense camera default clears 3 to 1 against 333333`() {
        val c = CaptureTheme.cameraTokens(null)
        listOf(c.ring, c.challenge, c.warning, c.success).forEach {
            assertTrue(hex(it), CaptureColor.contrast(it, 0xFF333333.toInt()) >= 3.0)
        }
    }

    @Test fun `WCAG luminance and contrast`() {
        assertEquals(21.0, CaptureColor.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, CaptureColor.contrast(0xFF333333.toInt(), 0xFF333333.toInt()), 0.0001)
        assertEquals(12.63, CaptureColor.contrast(0xFFFFFFFF.toInt(), 0xFF333333.toInt()), 0.01)
    }

    @Test fun `hsl round trip`() {
        listOf("#3A1D8F", "#1F7A4D", "#FF4FA3", "#808080", "#4F7CFF").forEach {
            val c = CaptureColor.parse(it)!!
            assertEquals(it, hex(CaptureColor.fromHsl(CaptureColor.toHsl(c))))
        }
    }

    @Test fun `hex parsing`() {
        assertEquals(0xFF112233.toInt(), CaptureColor.parse("#112233"))
        assertEquals(0x80112233.toInt(), CaptureColor.parse("80112233"))
        assertEquals(0xFFAABBCC.toInt(), CaptureColor.parse("#abc"))
        assertNull(CaptureColor.parse(""))
        assertNull(CaptureColor.parse("#12345"))
        assertNull(CaptureColor.parse("rgba(0,0,0,1)"))
    }

    // ── Page token rules ────────────────────────────────────────────────

    @Test fun `background color wins in light mode only`() {
        val a = FlowAppearance(
            background = AppearanceBackground(color = "#FAFAFA", imageUrl = "https://cdn.example/bg.jpg"),
            colors = AppearanceColors(background = "#EEEEEE", dark = AppearanceColors(background = "#050505")),
        )
        assertEquals("#FAFAFA", hex(CaptureTheme.resolve(a.copy(mode = FlowAppearance.Mode.LIGHT), false).page.background))
        assertEquals("#050505", hex(CaptureTheme.resolve(a.copy(mode = FlowAppearance.Mode.DARK), false).page.background))
        assertEquals("https://cdn.example/bg.jpg", CaptureTheme.resolve(a, false).backgroundImageUrl)
    }

    @Test fun `dark mode never derives a neutral from the light layer`() {
        val lightOnly = FlowAppearance(mode = FlowAppearance.Mode.DARK, colors = AppearanceColors(background = "#FFFFFF", foreground = "#000000"))
        val t = CaptureTheme.resolve(lightOnly, false)
        assertEquals(SenseDefaults.DARK.background, t.page.background)
        assertEquals(SenseDefaults.DARK.foreground, t.page.foreground)
    }

    @Test fun `button radius falls back to shape radius, then 14`() {
        assertEquals(10, CaptureTheme.resolve(FlowAppearance(shape = AppearanceShape(radius = 10)), false).shape.buttonRadiusDp)
        assertEquals(20, CaptureTheme.resolve(FlowAppearance(shape = AppearanceShape(radius = 10)), false).shape.sheetRadiusDp)
        assertEquals(28, CaptureTheme.resolve(FlowAppearance(shape = AppearanceShape(radius = 20)), false).shape.sheetRadiusDp)
    }

    @Test fun `tone tiles are the tone at 16 percent`() {
        val t = CaptureTheme.resolve(acmeBank, false)
        assertEquals(0x281F7A4D, t.toneTile(CaptureTheme.Tone.PRIMARY))
        assertEquals(t.camera.challenge, t.toneColor(CaptureTheme.Tone.CHALLENGE))
        assertEquals(t.page.error, t.toneColor(CaptureTheme.Tone.ERROR))
    }

    @Test fun `fixed list values are the spec's`() {
        assertEquals(0xFF333333.toInt(), CaptureFixed.CONTRAST_REFERENCE)
        assertEquals(3.0, CaptureFixed.MIN_CAMERA_CONTRAST, 0.0)
        assertEquals("#0D0F14", hex(CaptureFixed.CAMERA_BACKGROUND))
        assertEquals("#FFF4E2", hex(CaptureFixed.FILL_LIGHT))
        assertEquals("#FFFFFF", hex(CaptureFixed.GUIDANCE_TEXT))
    }

    // ── Retired contour colours (section 4.2) ───────────────────────────

    @Test fun `retired contour_start and contour_end are accepted and ignored`() {
        val a = FlowAppearance.decode(
            JSONObject("""{"capture":{"ring":"#22D3EE","contour_start":"#7A96FF","contourEnd":"#A878FC","challenge":"#FF4FA3"}}"""),
        )
        assertEquals("#22D3EE", a.capture?.ring)
        assertEquals("#FF4FA3", a.capture?.challenge)
        @Suppress("DEPRECATION")
        assertNull(a.capture?.contourStart)
        val merged = mergeAppearance(FlowAppearance(capture = AppearanceCapture(warning = "#F5A524")), a)
        assertEquals("#22D3EE", merged?.capture?.ring)
        assertEquals("#F5A524", merged?.capture?.warning)
        assertEquals("#FF4FA3", merged?.capture?.challenge)
        // The lines are drawn in the ring colour; nothing else reads them.
        assertEquals("#22D3EE", hex(CaptureTheme.cameraTokens(a).ring))
    }
}
