package com.usesense.sdk.internal

import android.util.Log
import com.usesense.sdk.BrandingConfig
import com.usesense.sdk.ServerBranding
import com.usesense.sdk.flows.AppearanceColors
import com.usesense.sdk.flows.AppearanceLogo
import com.usesense.sdk.flows.AppearanceShape
import com.usesense.sdk.flows.AppearanceTypography
import com.usesense.sdk.flows.FlowAppearance
import com.usesense.sdk.flows.FlowCopy
import com.usesense.sdk.flows.mergeAppearance
import com.usesense.sdk.flows.mergeCopy

/** Which layer supplied a resolved branding field. */
internal enum class BrandingSource(val label: String) {
    CODE("code"),
    DASHBOARD("dashboard"),
    DEFAULT("default"),
}

internal data class Sourced<T>(val value: T, val source: BrandingSource)

/** What the screen header shows (identical rule to iOS `HostedPageHeader`). */
internal sealed class BrandHeader {
    /** The logo alone; [fallbackName] shows only while it loads or if it fails. */
    data class Logo(val url: String, val fallbackName: String?) : BrandHeader()
    data class Name(val name: String) : BrandHeader()
    object None : BrandHeader()
}

/**
 * The effective branding for one Flow run or session, every field resolved
 * independently. See docs/branding-precedence.md (agreed 2026-11-07):
 * per field, the most specific value that was explicitly set wins; code beats
 * the dashboard; nothing counts unless it was explicitly set.
 */
internal data class ResolvedBranding(
    val displayName: Sourced<String?>,
    val logoUrl: Sourced<String?>,
    val primaryColor: Sourced<String>,
    val redirectUrl: Sourced<String?>,
    /** Merged appearance (code over dashboard), for the Compose surfaces. */
    val appearance: FlowAppearance?,
    val copy: FlowCopy?,
    /** Appearance logo placement `none`: no header at all. */
    val headerSuppressed: Boolean,
) {
    val header: BrandHeader
        get() {
            if (headerSuppressed) return BrandHeader.None
            logoUrl.value?.let { return BrandHeader.Logo(it, displayName.value) }
            displayName.value?.let { return BrandHeader.Name(it) }
            return BrandHeader.None
        }

    /** True when an org or integrator chose the colour (not the built-in default). */
    val isBrandColorSet: Boolean get() = primaryColor.source != BrandingSource.DEFAULT

    /** One line per field with the layer it came from, for the debug trace. */
    fun traceLines(): List<String> = listOf(
        "primary=${primaryColor.value} (${primaryColor.source.label})",
        "logo=${logoUrl.value ?: "-"} (${logoUrl.source.label})",
        "name=${displayName.value ?: "-"} (${displayName.source.label})",
        "redirect=${redirectUrl.value ?: "-"} (${redirectUrl.source.label})",
        "header=${header.describe()}",
    )

    private fun BrandHeader.describe(): String = when (this) {
        is BrandHeader.Logo -> "logo"
        is BrandHeader.Name -> "name"
        BrandHeader.None -> "none"
    }
}

internal object BrandingResolver {
    private const val TAG = "UseSense"

    /**
     * Resolve [code] (per-launch / SDK-init branding, layers 1-2) over [server]
     * (dashboard Flow/brand/org branding, layers 3-4) over built-in defaults
     * (layer 5).
     */
    fun resolve(code: BrandingConfig?, server: ServerBranding?): ResolvedBranding {
        val codeLayer = mergeAppearance(
            code?.appearance?.normalized(),
            legacyLayer(code?.primaryColor, code?.buttonRadius, code?.logoUrl, code?.fontFamily),
        )
        val serverLayer = mergeAppearance(
            server?.appearance?.normalized(),
            legacyLayer(server?.primaryColor, null, server?.logoUrl, null),
        )
        val merged = mergeAppearance(codeLayer, serverLayer)

        val primary = pick(codeLayer?.colors?.primary, serverLayer?.colors?.primary)
        return ResolvedBranding(
            displayName = pick(code?.displayName, server?.displayName),
            logoUrl = pick(codeLayer?.logo?.url, serverLayer?.logo?.url),
            primaryColor = Sourced(primary.value ?: BrandingConfig.DEFAULT_PRIMARY_COLOR, primary.source),
            redirectUrl = pick(code?.redirectUrl, server?.redirectUrl),
            appearance = merged,
            copy = mergeCopy(code?.copy, server?.copy),
            headerSuppressed = merged?.logo?.placement == AppearanceLogo.Placement.NONE,
        )
    }

    /**
     * Debug trace of where each field came from. Enable with
     * `adb shell setprop log.tag.UseSense DEBUG`. Logs only when the result
     * changes, so a surface that re-resolves as server branding arrives logs
     * once per distinct outcome.
     */
    fun trace(surface: String, resolved: ResolvedBranding, last: String?): String {
        val lines = resolved.traceLines().joinToString(", ")
        if (lines != last && runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)) {
            Log.d(TAG, "branding[$surface]: $lines")
        }
        return lines
    }

    private fun pick(code: String?, server: String?): Sourced<String?> {
        code.clean()?.let { return Sourced(it, BrandingSource.CODE) }
        server.clean()?.let { return Sourced(it, BrandingSource.DASHBOARD) }
        return Sourced(null, BrandingSource.DEFAULT)
    }

    private fun legacyLayer(primary: String?, buttonRadius: Int?, logoUrl: String?, fontFamily: String?): FlowAppearance? {
        val p = primary.clean()
        val l = logoUrl.clean()
        val f = fontFamily.clean()
        if (p == null && buttonRadius == null && l == null && f == null) return null
        return FlowAppearance(
            colors = p?.let { AppearanceColors(primary = it) },
            shape = buttonRadius?.let { AppearanceShape(buttonRadius = it) },
            logo = l?.let { AppearanceLogo(url = it) },
            typography = f?.let { AppearanceTypography(fontFamily = it) },
        )
    }

    /** Blank strings are "unset", never a value that hides a lower layer. */
    private fun FlowAppearance.normalized(): FlowAppearance = copy(
        colors = colors?.copy(primary = colors.primary.clean()),
        logo = logo?.copy(url = logo.url.clean()),
    )

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
