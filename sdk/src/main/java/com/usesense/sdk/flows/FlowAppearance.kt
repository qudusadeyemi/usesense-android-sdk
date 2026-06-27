package com.usesense.sdk.flows

import org.json.JSONObject

/**
 * The white-label customization contract (Phase 1) shared across surfaces and
 * SDKs. Mirrors `FlowAppearance` in usesense-web-sdk
 * (packages/sdk/src/flows/theme.ts) — keep both in sync.
 *
 * A [FlowAppearance] can be supplied two ways and is merged
 *   SDK-init > server(branding) > legacy primaryColor/buttonRadius > built-in:
 *   - by the developer at SDK init (BrandingConfig.appearance), and/or
 *   - by the operator in the dashboard, delivered on the flow-run branding payload.
 * Every field is optional; anything omitted falls back to the hosted-page tokens.
 */
data class FlowAppearance(
    val colors: AppearanceColors? = null,
    val typography: AppearanceTypography? = null,
    val shape: AppearanceShape? = null,
    val logo: AppearanceLogo? = null,
    val background: AppearanceBackground? = null,
    /** Custom illustrations for result screens / icon slots (image URLs). */
    val icons: AppearanceIcons? = null,
    /** Loading-animation preset or custom asset. */
    val loader: AppearanceLoader? = null,
    /** Force a palette or follow the OS (default [Mode.AUTO]). */
    val mode: Mode? = null,
) {
    /** Force a palette or follow the OS. */
    enum class Mode(val wire: String) {
        LIGHT("light"), DARK("dark"), AUTO("auto");

        companion object {
            fun fromWire(s: String?): Mode? = values().firstOrNull { it.wire == s }
        }
    }

    companion object {
        /**
         * Decode the wire shape. The server delivers the appearance under the
         * flow-run branding payload; every key is optional and snake_case on the
         * wire to match the rest of the branding contract.
         */
        fun decode(raw: JSONObject): FlowAppearance = FlowAppearance(
            colors = raw.optJSONObject("colors")?.let(AppearanceColors::decode),
            typography = raw.optJSONObject("typography")?.let(AppearanceTypography::decode),
            shape = raw.optJSONObject("shape")?.let(AppearanceShape::decode),
            logo = raw.optJSONObject("logo")?.let(AppearanceLogo::decode),
            background = raw.optJSONObject("background")?.let(AppearanceBackground::decode),
            icons = raw.optJSONObject("icons")?.let(AppearanceIcons::decode),
            loader = raw.optJSONObject("loader")?.let(AppearanceLoader::decode),
            mode = Mode.fromWire(raw.optStringOrNull("mode")),
        )
    }
}

/** A palette layer. [dark] overrides apply only in dark mode. */
data class AppearanceColors(
    val primary: String? = null,
    val primaryForeground: String? = null,
    val background: String? = null,
    val surface: String? = null,
    val foreground: String? = null,
    val muted: String? = null,
    val border: String? = null,
    val success: String? = null,
    val error: String? = null,
    val warning: String? = null,
    /** Overrides applied on top of the dark base (e.g. a darker background). */
    val dark: AppearanceColors? = null,
) {
    companion object {
        fun decode(raw: JSONObject, includeDark: Boolean = true): AppearanceColors = AppearanceColors(
            primary = raw.optStringOrNull("primary"),
            primaryForeground = raw.optStringOrNull("primary_foreground") ?: raw.optStringOrNull("primaryForeground"),
            background = raw.optStringOrNull("background"),
            surface = raw.optStringOrNull("surface"),
            foreground = raw.optStringOrNull("foreground"),
            muted = raw.optStringOrNull("muted"),
            border = raw.optStringOrNull("border"),
            success = raw.optStringOrNull("success"),
            error = raw.optStringOrNull("error"),
            warning = raw.optStringOrNull("warning"),
            dark = if (includeDark) raw.optJSONObject("dark")?.let { decode(it, includeDark = false) } else null,
        )
    }
}

data class AppearanceTypography(
    /** Body font-family name (e.g. "DM Sans"). */
    val fontFamily: String? = null,
    /** Heading/display font-family; defaults to [fontFamily] when omitted. */
    val displayFamily: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject): AppearanceTypography = AppearanceTypography(
            fontFamily = raw.optStringOrNull("font_family") ?: raw.optStringOrNull("fontFamily"),
            displayFamily = raw.optStringOrNull("display_family") ?: raw.optStringOrNull("displayFamily"),
        )
    }
}

data class AppearanceShape(
    /** Base corner radius in dp (cards, inputs). */
    val radius: Int? = null,
    /** Button corner radius in dp; defaults to [radius]. */
    val buttonRadius: Int? = null,
    val buttonStyle: ButtonStyle? = null,
) {
    enum class ButtonStyle(val wire: String) {
        FILLED("filled"), OUTLINE("outline");

        companion object {
            fun fromWire(s: String?): ButtonStyle? = values().firstOrNull { it.wire == s }
        }
    }

    companion object {
        fun decode(raw: JSONObject): AppearanceShape = AppearanceShape(
            radius = raw.optIntOrNull("radius"),
            buttonRadius = raw.optIntOrNull("button_radius") ?: raw.optIntOrNull("buttonRadius"),
            buttonStyle = ButtonStyle.fromWire(raw.optStringOrNull("button_style") ?: raw.optStringOrNull("buttonStyle")),
        )
    }
}

data class AppearanceLogo(
    val url: String? = null,
    val placement: Placement? = null,
    /** Logo height in dp. */
    val height: Int? = null,
) {
    enum class Placement(val wire: String) {
        HEADER("header"), CENTER("center"), NONE("none");

        companion object {
            fun fromWire(s: String?): Placement? = values().firstOrNull { it.wire == s }
        }
    }

    companion object {
        fun decode(raw: JSONObject): AppearanceLogo = AppearanceLogo(
            url = raw.optStringOrNull("url"),
            placement = Placement.fromWire(raw.optStringOrNull("placement")),
            height = raw.optIntOrNull("height"),
        )
    }
}

data class AppearanceBackground(
    val color: String? = null,
    val imageUrl: String? = null,
) {
    companion object {
        fun decode(raw: JSONObject): AppearanceBackground = AppearanceBackground(
            color = raw.optStringOrNull("color"),
            imageUrl = raw.optStringOrNull("image_url") ?: raw.optStringOrNull("imageUrl"),
        )
    }
}

/**
 * Custom illustration/icon overrides (image URLs replacing built-in glyphs).
 * The named result slots ([success]/[review]/[notVerified]) plus any extra named
 * slots are addressable via [slot]. Mirrors `AppearanceIcons` in the web SDK.
 */
data class AppearanceIcons(
    /** Success result screen. */
    val success: String? = null,
    /** Under-review result screen. */
    val review: String? = null,
    /** Not-verified result screen. */
    val notVerified: String? = null,
    /** Every named slot (including the three above) keyed by its wire name. */
    val slots: Map<String, String> = emptyMap(),
) {
    /** Look up a slot URL by name (e.g. "success", "review", "not_verified"). */
    fun slot(name: String): String? = slots[name]

    companion object {
        fun decode(raw: JSONObject): AppearanceIcons {
            val slots = LinkedHashMap<String, String>()
            for (key in raw.keys()) {
                if (raw.isNull(key)) continue
                val value = raw.optString(key, "").takeIf { it.isNotEmpty() } ?: continue
                slots[key] = value
            }
            return AppearanceIcons(
                success = slots["success"],
                review = slots["review"],
                notVerified = slots["not_verified"] ?: slots["notVerified"],
                slots = slots,
            )
        }
    }
}

/** Loading animation: a built-in preset or a custom asset. Mirrors `AppearanceLoader`. */
data class AppearanceLoader(
    /** Built-in preset. Default [Style.SPINNER]. */
    val style: Style? = null,
    /** Custom loader asset URL; overrides [style] when set. */
    val imageUrl: String? = null,
) {
    enum class Style(val wire: String) {
        SPINNER("spinner"), DOTS("dots"), BAR("bar");

        companion object {
            fun fromWire(s: String?): Style? = values().firstOrNull { it.wire == s }
        }
    }

    companion object {
        fun decode(raw: JSONObject): AppearanceLoader = AppearanceLoader(
            style = Style.fromWire(raw.optStringOrNull("style")),
            imageUrl = raw.optStringOrNull("image_url") ?: raw.optStringOrNull("imageUrl"),
        )
    }
}

/**
 * Deep-merge a higher-priority appearance over a lower one (for SDK > server).
 * Returns null only when both inputs are null. Mirrors `mergeAppearance` in the
 * web SDK.
 */
fun mergeAppearance(high: FlowAppearance?, low: FlowAppearance?): FlowAppearance? {
    if (high == null) return low
    if (low == null) return high
    return FlowAppearance(
        colors = mergeColors(high.colors, low.colors),
        typography = AppearanceTypography(
            fontFamily = high.typography?.fontFamily ?: low.typography?.fontFamily,
            displayFamily = high.typography?.displayFamily ?: low.typography?.displayFamily,
        ).takeIf { high.typography != null || low.typography != null },
        shape = AppearanceShape(
            radius = high.shape?.radius ?: low.shape?.radius,
            buttonRadius = high.shape?.buttonRadius ?: low.shape?.buttonRadius,
            buttonStyle = high.shape?.buttonStyle ?: low.shape?.buttonStyle,
        ).takeIf { high.shape != null || low.shape != null },
        logo = AppearanceLogo(
            url = high.logo?.url ?: low.logo?.url,
            placement = high.logo?.placement ?: low.logo?.placement,
            height = high.logo?.height ?: low.logo?.height,
        ).takeIf { high.logo != null || low.logo != null },
        background = AppearanceBackground(
            color = high.background?.color ?: low.background?.color,
            imageUrl = high.background?.imageUrl ?: low.background?.imageUrl,
        ).takeIf { high.background != null || low.background != null },
        icons = mergeIcons(high.icons, low.icons),
        loader = AppearanceLoader(
            style = high.loader?.style ?: low.loader?.style,
            imageUrl = high.loader?.imageUrl ?: low.loader?.imageUrl,
        ).takeIf { high.loader != null || low.loader != null },
        mode = high.mode ?: low.mode,
    )
}

private fun mergeIcons(high: AppearanceIcons?, low: AppearanceIcons?): AppearanceIcons? {
    if (high == null) return low
    if (low == null) return high
    val slots = LinkedHashMap<String, String>()
    slots.putAll(low.slots)
    slots.putAll(high.slots)
    return AppearanceIcons(
        success = high.success ?: low.success,
        review = high.review ?: low.review,
        notVerified = high.notVerified ?: low.notVerified,
        slots = slots,
    )
}

private fun mergeColors(high: AppearanceColors?, low: AppearanceColors?): AppearanceColors? {
    if (high == null) return low
    if (low == null) return high
    return AppearanceColors(
        primary = high.primary ?: low.primary,
        primaryForeground = high.primaryForeground ?: low.primaryForeground,
        background = high.background ?: low.background,
        surface = high.surface ?: low.surface,
        foreground = high.foreground ?: low.foreground,
        muted = high.muted ?: low.muted,
        border = high.border ?: low.border,
        success = high.success ?: low.success,
        error = high.error ?: low.error,
        warning = high.warning ?: low.warning,
        dark = mergeColors(high.dark, low.dark),
    )
}

/**
 * Flatten a colors layer for the active mode: in dark mode the `dark` overrides
 * win over the base layer; in light mode `dark` is ignored.
 */
internal fun AppearanceColors.forMode(dark: Boolean): AppearanceColors {
    if (!dark || this.dark == null) return this
    val d = this.dark
    return AppearanceColors(
        primary = d.primary ?: primary,
        primaryForeground = d.primaryForeground ?: primaryForeground,
        background = d.background ?: background,
        surface = d.surface ?: surface,
        foreground = d.foreground ?: foreground,
        muted = d.muted ?: muted,
        border = d.border ?: border,
        success = d.success ?: success,
        error = d.error ?: error,
        warning = d.warning ?: warning,
        dark = null,
    )
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name, "").takeIf { it.isNotEmpty() }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}
