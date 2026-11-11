package com.usesense.demo

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * The BRAND-v1 test brands (capture-test-kit/BRAND-v1.md section 8), sent by
 * the mock server as the session's dashboard `branding`. Launch with
 * `--es brand acme` or `--es brand nightpay`; anything else sends none.
 */
object TestBrands {
    fun json(context: Context, name: String?): JSONObject? = when (name) {
        "acme" -> JSONObject(
            """
            { "display_name": "Acme Bank",
              "appearance": { "mode": "light",
                "colors": { "primary": "#1F7A4D", "background": "#FFFFFF", "surface": "#F3F6F4",
                            "foreground": "#13261C", "muted": "#5B6B62", "border": "#D8E0DB" },
                "typography": { "displayFamily": "Georgia", "fontFamily": "Inter" },
                "shape": { "radius": 12, "buttonRadius": 999, "buttonStyle": "filled" },
                "logo": { "url": "${logo(context)}", "placement": "header", "height": 28 } } }
            """.trimIndent(),
        )
        "nightpay" -> JSONObject(
            """
            { "display_name": "Night Pay",
              "appearance": { "mode": "dark",
                "colors": { "primary": "#3A1D8F", "dark": { "background": "#0B0B12", "surface": "#16162A",
                            "foreground": "#EDEBFF", "muted": "#9C98C9" } },
                "capture": { "challenge": "#FF4FA3" },
                "shape": { "buttonRadius": 4, "buttonStyle": "outline" } } }
            """.trimIndent(),
        )
        else -> null
    }

    /** The test logo (a synthetic "Acme Bank" mark) as a data URL. */
    private fun logo(context: Context): String {
        val bytes = context.assets.open("brand/acme-logo.png").use { it.readBytes() }
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
