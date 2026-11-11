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
 * independently (docs/branding-precedence.md, BRAND-v1 section 2). Per field,
 * highest first: per-launch code, SDK init (`appearance`, then the legacy
 * primaryColor / buttonRadius / fontFamily / logoUrl), the dashboard (Flow,
 * then org; `appearance`, then its flat primary_color / logo_url), the Sense
 * defaults. Code beats the dashboard, so a legacy code field sits above a
 * dashboard `appearance` value. Nothing counts unless it was explicitly set.
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
    /** Every set appearance and copy field with the layer it came from (`colors.primary` -> code). */
    val fieldSources: Map<String, BrandingSource> = emptyMap(),
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
    ) + fieldSources.map { (field, source) -> "$field (${source.label})" }

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
        val codeCopy = code?.copy
        val serverCopy = server?.copy
        val sources = LinkedHashMap<String, BrandingSource>()
        val codeFields = fields(codeLayer, codeCopy)
        val serverFields = fields(serverLayer, serverCopy)
        (codeFields + serverFields).sorted().forEach { k ->
            sources[k] = if (k in codeFields) BrandingSource.CODE else BrandingSource.DASHBOARD
        }
        return ResolvedBranding(
            displayName = pick(code?.displayName, server?.displayName),
            logoUrl = pick(codeLayer?.logo?.url, serverLayer?.logo?.url),
            primaryColor = Sourced(primary.value ?: BrandingConfig.DEFAULT_PRIMARY_COLOR, primary.source),
            redirectUrl = pick(code?.redirectUrl, server?.redirectUrl),
            appearance = merged,
            copy = mergeCopy(code?.copy, server?.copy),
            headerSuppressed = merged?.logo?.placement == AppearanceLogo.Placement.NONE,
            fieldSources = sources,
        )
    }

    /**
     * The two code layers as one (BRAND-v1 section 2): per-launch [launch]
     * (a Flow's run options) over `UseSense.initialize` [init]. The legacy
     * fields come from [init] and stay below both appearances.
     */
    fun mergeCode(launch: BrandingConfig?, init: BrandingConfig?): BrandingConfig? {
        if (launch == null) return init
        if (init == null) return launch
        return init.copy(
            displayName = launch.displayName.clean() ?: init.displayName,
            logoUrl = launch.logoUrl.clean() ?: init.logoUrl,
            primaryColor = launch.primaryColor.clean() ?: init.primaryColor,
            redirectUrl = launch.redirectUrl.clean() ?: init.redirectUrl,
            buttonRadius = launch.buttonRadius ?: init.buttonRadius,
            fontFamily = launch.fontFamily.clean() ?: init.fontFamily,
            appearance = mergeAppearance(launch.appearance, init.appearance),
            copy = mergeCopy(launch.copy, init.copy),
        )
    }

    /**
     * Two dashboard layers as one: [high] (a Flow's run view) over [low] (its
     * init-session), per field. Null only when both are.
     */
    fun mergeServer(high: ServerBranding?, low: ServerBranding?): ServerBranding? {
        if (high == null) return low
        if (low == null) return high
        return ServerBranding(
            displayName = high.displayName.clean() ?: low.displayName,
            logoUrl = high.logoUrl.clean() ?: low.logoUrl,
            primaryColor = high.primaryColor.clean() ?: low.primaryColor,
            redirectUrl = high.redirectUrl.clean() ?: low.redirectUrl,
            appearance = mergeAppearance(high.appearance, low.appearance),
            copy = mergeCopy(high.copy, low.copy),
        )
    }

    /** The names of every set field in a layer, for the trace. */
    private fun fields(a: FlowAppearance?, copy: com.usesense.sdk.flows.FlowCopy?): Set<String> {
        val out = LinkedHashSet<String>()
        fun add(name: String, v: Any?) { if (v != null && (v !is String || v.isNotBlank())) out += name }
        a?.colors?.let { c ->
            fun colors(prefix: String, c: AppearanceColors) {
                add("${prefix}primary", c.primary); add("${prefix}primaryForeground", c.primaryForeground)
                add("${prefix}background", c.background); add("${prefix}surface", c.surface)
                add("${prefix}foreground", c.foreground); add("${prefix}muted", c.muted); add("${prefix}border", c.border)
                add("${prefix}success", c.success); add("${prefix}error", c.error); add("${prefix}warning", c.warning)
            }
            colors("colors.", c)
            c.dark?.let { colors("colors.dark.", it) }
        }
        a?.typography?.let { add("typography.fontFamily", it.fontFamily); add("typography.displayFamily", it.displayFamily) }
        a?.shape?.let { add("shape.radius", it.radius); add("shape.buttonRadius", it.buttonRadius); add("shape.buttonStyle", it.buttonStyle) }
        a?.logo?.let { add("logo.url", it.url); add("logo.placement", it.placement); add("logo.height", it.height) }
        a?.background?.let { add("background.color", it.color); add("background.imageUrl", it.imageUrl) }
        a?.icons?.let { add("icons.success", it.success); add("icons.review", it.review); add("icons.notVerified", it.notVerified) }
        a?.loader?.let { add("loader.style", it.style); add("loader.imageUrl", it.imageUrl) }
        add("mode", a?.mode)
        a?.capture?.let { add("capture.ring", it.ring); add("capture.challenge", it.challenge); add("capture.warning", it.warning) }
        copy?.capture?.let { c ->
            c.strings.forEach { (k, v) -> add("copy.capture.$k", v) }
            c.lists.forEach { (k, v) -> if (v.any { it.isNotBlank() }) out += "copy.capture.$k" }
        }
        return out
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
