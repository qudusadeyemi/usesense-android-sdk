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
}
