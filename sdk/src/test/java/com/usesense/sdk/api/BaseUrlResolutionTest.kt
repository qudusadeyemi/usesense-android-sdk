package com.usesense.sdk.api

import com.usesense.sdk.UseSenseConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The doubled-`/v1` regression, pinned.
 *
 * The SDK put a version in the base URL *and* in every Retrofit path, so calls
 * resolved to `/v1/v1/sessions/...`. The server rejected that before reading
 * the request body, and because the signals upload carries megabytes the
 * rejection deadlocked instead of returning -- no client error, no server log.
 * It is why no Android integration ever completed a production verification.
 *
 * These assert the *resolved* URL rather than the base alone: the bug was only
 * visible once base and path were combined, so that is what has to be checked.
 */
class BaseUrlResolutionTest {

    /** Resolve a Retrofit-style relative path against the normalised base. */
    private fun resolve(baseUrl: String, path: String): String =
        UseSenseApiClient.normalizeBaseUrl(baseUrl).toHttpUrl().resolve(path)!!.toString()

    @Test
    fun `signals upload resolves to a single v1`() {
        assertEquals(
            "https://api.usesense.ai/v1/sessions/sess_abc/signals",
            resolve(UseSenseConfig.DEFAULT_BASE_URL, "v1/sessions/sess_abc/signals"),
        )
    }

    @Test
    fun `a base that still ends in v1 resolves identically`() {
        // Public configuration: integrators followed the old documentation and
        // pass this explicitly. Both forms must land on the same URL.
        assertEquals(
            resolve("https://api.usesense.ai", "v1/sessions/sess_abc/signals"),
            resolve("https://api.usesense.ai/v1", "v1/sessions/sess_abc/signals"),
        )
    }

    @Test
    fun `trailing slashes do not change resolution`() {
        val expected = "https://api.usesense.ai/v1/sessions/sess_abc/signals"
        for (base in listOf(
            "https://api.usesense.ai",
            "https://api.usesense.ai/",
            "https://api.usesense.ai/v1",
            "https://api.usesense.ai/v1/",
        )) {
            assertEquals(base, expected, resolve(base, "v1/sessions/sess_abc/signals"))
        }
    }

    @Test
    fun `every versioned session path resolves under exactly one v1`() {
        for (path in listOf(
            "v1/sessions",
            "v1/sessions/exchange-token",
            "v1/sessions/sess_abc/signals",
            "v1/sessions/sess_abc/complete",
            "v1/sessions/sess_abc/status",
        )) {
            val resolved = resolve(UseSenseConfig.DEFAULT_BASE_URL, path)
            assertEquals(path, "https://api.usesense.ai/$path", resolved)
        }
    }

    @Test
    fun `un-versioned routes hang off the base with no version injected`() {
        // The other reason the version cannot live in the base: these paths
        // are not under /v1 at all.
        for (path in listOf(
            "remote-enrollment/re_1/data",
            "remote-enrollment/re_1/init-session",
            "remote-session/rs_1/data",
            "remote-session/rs_1/dispute",
        )) {
            assertEquals(
                "https://api.usesense.ai/$path",
                resolve(UseSenseConfig.DEFAULT_BASE_URL, path),
            )
        }
    }

    @Test
    fun `a self-hosted base keeps its own path prefix`() {
        // Flows passes the Supabase function root directly.
        assertEquals(
            "https://proj.supabase.co/functions/v1/watchtower-api/v1/sessions/s/signals",
            resolve(
                "https://proj.supabase.co/functions/v1/watchtower-api",
                "v1/sessions/s/signals",
            ),
        )
    }

    @Test
    fun `the functions v1 in a supabase host is not mistaken for the API version`() {
        // Only a TRAILING /v1 is stripped. `/functions/v1/` sits mid-path and
        // must survive, or every self-hosted call 404s.
        val base = UseSenseApiClient.normalizeBaseUrl(
            "https://proj.supabase.co/functions/v1/watchtower-api",
        )
        assertEquals("https://proj.supabase.co/functions/v1/watchtower-api/", base)
    }

    @Test
    fun `default base url carries no version segment`() {
        assertEquals("https://api.usesense.ai", UseSenseConfig.DEFAULT_BASE_URL)
    }
}
