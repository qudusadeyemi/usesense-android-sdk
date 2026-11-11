package com.usesense.sdk.internal

import com.usesense.sdk.BrandingConfig
import com.usesense.sdk.EffectiveBranding
import com.usesense.sdk.ServerBranding
import com.usesense.sdk.flows.AppearanceColors
import com.usesense.sdk.flows.AppearanceLogo
import com.usesense.sdk.flows.FlowAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agreed precedence (docs/branding-precedence.md): per field, code beats dashboard; unset never overrides. */
class BrandingResolverTest {

    private val dashboard = ServerBranding(
        displayName = "XTransfer",
        logoUrl = "https://cdn.example/xt.png",
        primaryColor = "#FF6A00",
    )

    @Test
    fun `dashboard values apply when code sets nothing`() {
        val r = BrandingResolver.resolve(null, dashboard)
        assertEquals(Sourced("#FF6A00", BrandingSource.DASHBOARD), r.primaryColor)
        assertEquals(Sourced<String?>("XTransfer", BrandingSource.DASHBOARD), r.displayName)
        assertEquals("https://cdn.example/xt.png", r.logoUrl.value)
        assertTrue(r.isBrandColorSet)
    }

    @Test
    fun `code beats the dashboard field by field, without hiding other dashboard fields`() {
        val r = BrandingResolver.resolve(BrandingConfig(primaryColor = "#123456"), dashboard)
        assertEquals(Sourced("#123456", BrandingSource.CODE), r.primaryColor)
        // Setting only a colour in code must not hide the dashboard's logo or name.
        assertEquals(Sourced<String?>("https://cdn.example/xt.png", BrandingSource.DASHBOARD), r.logoUrl)
        assertEquals("XTransfer", r.displayName.value)
    }

    @Test
    fun `code appearance beats dashboard appearance per field`() {
        val code = BrandingConfig(appearance = FlowAppearance(colors = AppearanceColors(primary = "#00AA00")))
        val server = ServerBranding(appearance = FlowAppearance(colors = AppearanceColors(primary = "#FF6A00", foreground = "#111111")))
        val r = BrandingResolver.resolve(code, server)
        assertEquals("#00AA00", r.primaryColor.value)
        assertEquals("#111111", r.appearance?.colors?.foreground)
    }

    @Test
    fun `unset and blank values never override a lower layer`() {
        val r = BrandingResolver.resolve(
            BrandingConfig(displayName = "  ", logoUrl = "", primaryColor = null),
            dashboard,
        )
        assertEquals(BrandingSource.DASHBOARD, r.displayName.source)
        assertEquals(BrandingSource.DASHBOARD, r.logoUrl.source)
        assertEquals(BrandingSource.DASHBOARD, r.primaryColor.source)
    }

    @Test
    fun `no colour anywhere falls back to the built-in default and is not branded`() {
        val r = BrandingResolver.resolve(null, ServerBranding(primaryColor = null))
        assertEquals(Sourced(BrandingConfig.DEFAULT_PRIMARY_COLOR, BrandingSource.DEFAULT), r.primaryColor)
        assertFalse(r.isBrandColorSet)
    }

    @Test
    fun `header is the logo alone with the name only as fallback`() {
        val header = BrandingResolver.resolve(null, dashboard).header
        assertEquals(BrandHeader.Logo("https://cdn.example/xt.png", "XTransfer"), header)
    }

    @Test
    fun `header is the name when there is no logo, and none when neither is set`() {
        assertEquals(BrandHeader.Name("XTransfer"), BrandingResolver.resolve(null, ServerBranding(displayName = "XTransfer")).header)
        assertEquals(BrandHeader.None, BrandingResolver.resolve(null, null).header)
        // No invented "UseSense" name.
        assertNull(EffectiveBranding.merge(null, null).displayName)
    }

    @Test
    fun `logo placement none hides the header`() {
        val server = dashboard.copy(appearance = FlowAppearance(logo = AppearanceLogo(placement = AppearanceLogo.Placement.NONE)))
        val r = BrandingResolver.resolve(null, server)
        assertTrue(r.headerSuppressed)
        assertEquals(BrandHeader.None, r.header)
    }

    @Test
    fun `appearance logo beats the flat logo within a layer`() {
        val server = dashboard.copy(appearance = FlowAppearance(logo = AppearanceLogo(url = "https://cdn.example/appearance.png")))
        assertEquals("https://cdn.example/appearance.png", BrandingResolver.resolve(null, server).logoUrl.value)
    }

    @Test
    fun `trace lines name each field's source`() {
        val lines = BrandingResolver.resolve(BrandingConfig(primaryColor = "#123456"), dashboard).traceLines()
        assertTrue(lines.contains("primary=#123456 (code)"))
        assertTrue(lines.contains("name=XTransfer (dashboard)"))
        assertTrue(lines.contains("header=logo"))
    }

    // ── BRAND-v1 section 2 ──────────────────────────────────────────────

    @Test
    fun `a code legacy primaryColor beats a dashboard appearance primary, and reaches the capture ring`() {
        val server = ServerBranding(appearance = FlowAppearance(colors = AppearanceColors(primary = "#FF6A00", surface = "#222222")))
        val r = BrandingResolver.resolve(BrandingConfig(primaryColor = "#1F7A4D"), server)
        assertEquals(Sourced("#1F7A4D", BrandingSource.CODE), r.primaryColor)
        assertEquals("#1F7A4D", r.appearance?.colors?.primary)
        // Other dashboard fields still come through.
        assertEquals("#222222", r.appearance?.colors?.surface)
        val theme = com.usesense.sdk.ui.theme.CaptureTheme.resolve(r.appearance, systemDark = false)
        assertEquals(0xFF1F7A4D.toInt(), theme.page.primary)
        // On the camera the same green, lightened to 3:1.
        assertEquals(0xFF248E5A.toInt(), theme.camera.ring)
    }

    @Test
    fun `code appearance beats code legacy fields, legacy buttonRadius and fontFamily beat the dashboard`() {
        val code = BrandingConfig(
            primaryColor = "#111111", buttonRadius = 6, fontFamily = "Inter",
            appearance = FlowAppearance(colors = AppearanceColors(primary = "#00AA00")),
        )
        val server = ServerBranding(
            appearance = FlowAppearance(
                shape = com.usesense.sdk.flows.AppearanceShape(buttonRadius = 20),
                typography = com.usesense.sdk.flows.AppearanceTypography(fontFamily = "Georgia"),
            ),
        )
        val r = BrandingResolver.resolve(code, server)
        assertEquals("#00AA00", r.primaryColor.value)
        assertEquals(6, r.appearance?.shape?.buttonRadius)
        assertEquals("Inter", r.appearance?.typography?.fontFamily)
    }

    @Test
    fun `per-launch code beats SDK init, which beats the dashboard`() {
        val launch = BrandingConfig(appearance = FlowAppearance(colors = AppearanceColors(primary = "#0000AA")))
        val init = BrandingConfig(
            displayName = "Acme Bank", primaryColor = "#AA0000",
            appearance = FlowAppearance(colors = AppearanceColors(primary = "#00AA00", muted = "#555555")),
        )
        val code = BrandingResolver.mergeCode(launch, init)
        val r = BrandingResolver.resolve(code, ServerBranding(displayName = "Dash", appearance = FlowAppearance(colors = AppearanceColors(muted = "#999999"))))
        assertEquals("#0000AA", r.primaryColor.value)
        assertEquals("#555555", r.appearance?.colors?.muted)
        assertEquals(Sourced<String?>("Acme Bank", BrandingSource.CODE), r.displayName)
        assertEquals(init, BrandingResolver.mergeCode(null, init))
    }

    @Test
    fun `a Flow's run-view branding sits over its init-session branding`() {
        val runView = ServerBranding(displayName = "Flow brand", appearance = FlowAppearance(colors = AppearanceColors(primary = "#123456")))
        val initSession = ServerBranding(displayName = "Org", logoUrl = "https://cdn.example/org.png", appearance = FlowAppearance(colors = AppearanceColors(muted = "#777777")))
        val merged = BrandingResolver.mergeServer(runView, initSession)!!
        assertEquals("Flow brand", merged.displayName)
        assertEquals("https://cdn.example/org.png", merged.logoUrl)
        assertEquals("#123456", merged.appearance?.colors?.primary)
        assertEquals("#777777", merged.appearance?.colors?.muted)
        assertNull(BrandingResolver.mergeServer(null, null))
    }

    @Test
    fun `the trace names every appearance and copy field's layer`() {
        val code = BrandingConfig(
            appearance = FlowAppearance(mode = FlowAppearance.Mode.DARK, colors = AppearanceColors(dark = AppearanceColors(background = "#0B0B12"))),
            copy = com.usesense.sdk.flows.FlowCopy(capture = com.usesense.sdk.flows.CaptureCopy(strings = mapOf("leave.title" to "Leave?"))),
        )
        val server = ServerBranding(appearance = FlowAppearance(capture = com.usesense.sdk.flows.AppearanceCapture(challenge = "#FF4FA3")))
        val r = BrandingResolver.resolve(code, server)
        assertEquals(BrandingSource.CODE, r.fieldSources["mode"])
        assertEquals(BrandingSource.CODE, r.fieldSources["colors.dark.background"])
        assertEquals(BrandingSource.CODE, r.fieldSources["copy.capture.leave.title"])
        assertEquals(BrandingSource.DASHBOARD, r.fieldSources["capture.challenge"])
        assertTrue(r.traceLines().contains("capture.challenge (dashboard)"))
    }
}
