package com.usesense.sdk.ui

import android.app.Activity
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.VerificationRequest
import com.usesense.sdk.api.ApiException
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.CreateSessionResponse
import com.usesense.sdk.api.models.SessionPolicy
import com.usesense.sdk.api.models.UploadConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The client token path (J1): `startVerificationWithToken` and
 * `UseSenseFragment.newInstanceWithToken` exchange a single-use token with
 * `POST /v1/sessions/exchange-token`, never sending an API key, and the launch
 * never spends the token twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenExchangeLaunchTest {
    private val server = MockWebServer().apply { start() }
    private val config = UseSenseConfig(apiKey = "", baseUrl = server.url("/").toString())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        CaptureLaunches.clearForTest()
        UseSense.reset()
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        CaptureLaunches.clearForTest()
        UseSense.reset()
    }

    private val sessionJson = """
        {"session_id":"ses_1","session_token":"st_1","expires_at":"2099-01-01T00:00:00Z","nonce":"n_1",
         "policy":{"requires_audio":false,"requires_stepup":false},
         "upload":{"max_frames":30,"target_fps":10,"capture_duration_ms":2500},
         "geometric_coherence":{"dual_path_enabled":true,"screen_illumination_enabled":true,
           "on_device_3dmm_required":true,"mesh_binding_challenge":"mbc"},
         "branding":{"display_name":"Acme"}}
    """.trimIndent()

    private fun session(id: String = "ses_1") = CreateSessionResponse(
        sessionId = id, sessionToken = "st", expiresAt = "2099-01-01T00:00:00Z", nonce = "n",
        policy = SessionPolicy(), upload = UploadConfig(30, 10, 2500),
    )

    private class Recorder : UseSenseCallback {
        val events = mutableListOf<String>()
        var error: UseSenseError? = null
        override fun onSuccess(result: UseSenseResult) { events += "success:${result.decision}" }
        override fun onError(error: UseSenseError) { events += "error:${error.serverCode ?: error.code}"; this.error = error }
        override fun onCancelled() { events += "cancelled" }
    }

    // ─── Wire contract ──────────────────────────────────────────────────

    @Test
    fun `exchange posts the client token with no API key and reads the session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val response = UseSenseApiClient(config).exchangeToken("cli_tok_abc").getOrThrow()

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!, req.path!!.startsWith("/v1/sessions/exchange-token"))
        assertNull(req.getHeader("x-api-key"))
        assertNull(req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("cli_tok_abc", body.getString("client_token"))
        assertTrue(body.getJSONArray("capabilities").length() > 0)
        assertEquals("ses_1", response.sessionId)
        assertEquals("Acme", response.branding?.displayName)
    }

    @Test
    fun `exchange errors keep the server code`() = runTest {
        for ((status, code) in listOf(404 to "token_not_found", 410 to "token_expired", 409 to "token_already_used")) {
            server.enqueue(
                MockResponse().setResponseCode(status)
                    .setBody("""{"error":{"code":"$code","message":"m","request_id":"r"}}"""),
            )
            val e = UseSenseApiClient(config).exchangeToken("cli_tok_abc").exceptionOrNull() as ApiException
            assertEquals(code, e.useSenseError.serverCode)
        }
    }

    // ─── The launch exchanges once ──────────────────────────────────────

    private fun tokenLaunch(callback: UseSenseCallback? = null) = CaptureLaunch(
        config,
        CaptureLaunch.Kind.TOKEN,
        request = VerificationRequest(sessionType = SessionType.ENROLLMENT),
        clientToken = "cli_tok_abc",
        callback = callback,
    )

    @Test
    fun `a screen recreated mid exchange waits for the same call`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson).setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        val launch = tokenLaunch()
        val first = launch.exchangeToken()
        val second = launch.exchangeToken() // the recreated screen
        assertSame(first, second)
        assertEquals("ses_1", second.await().getOrThrow().sessionId)
        assertEquals(1, server.requestCount)
        launch.release()
    }

    @Test
    fun `a failed exchange can be tried again`() = runTest {
        // A 4xx is not retried by the client, so this is one call.
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"invalid_request","message":"m"}}"""))
        val launch = tokenLaunch()
        assertTrue(launch.exchangeToken().await().isFailure)
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        assertEquals("ses_1", launch.exchangeToken().await().getOrThrow().sessionId)
        assertEquals(2, server.requestCount)
        launch.release()
    }

    // ─── Where each attempt's session comes from ────────────────────────

    @Test
    fun `token sessions are reused until uploaded, then spent`() {
        val launch = tokenLaunch()
        assertEquals(CaptureLaunch.SessionSource.Exchange("cli_tok_abc"), launch.sessionSource(resuming = false))

        launch.currentSession = session()
        // "Try again" before the upload and a recreated screen both keep the session.
        assertTrue(launch.sessionSource(resuming = false) is CaptureLaunch.SessionSource.Reuse)
        assertTrue(launch.sessionSource(resuming = true) is CaptureLaunch.SessionSource.Reuse)

        launch.uploadStarted = true
        assertEquals(CaptureLaunch.SessionSource.TokenSpent, launch.sessionSource(resuming = false))
        assertEquals(CaptureLaunch.SessionSource.TokenSpent, launch.sessionSource(resuming = true))
        launch.release()
    }

    @Test
    fun `api key sessions reuse only when the screen is recreated`() {
        val launch = CaptureLaunch(config, CaptureLaunch.Kind.DIRECT, request = VerificationRequest(SessionType.ENROLLMENT))
        assertEquals(CaptureLaunch.SessionSource.Create, launch.sessionSource(resuming = false))
        launch.currentSession = session()
        assertTrue(launch.sessionSource(resuming = true) is CaptureLaunch.SessionSource.Reuse)
        // "Try again" after a rejection keeps today's behaviour: a new session.
        assertEquals(CaptureLaunch.SessionSource.Create, launch.sessionSource(resuming = false))
        launch.uploadStarted = true
        assertEquals(CaptureLaunch.SessionSource.Create, launch.sessionSource(resuming = true))
        assertTrue(launch.ownsSession)
        assertTrue(!tokenLaunch().ownsSession)
    }

    @Test
    fun `flow runs use the pre-minted session first, then the factory`() {
        val factory: suspend () -> CreateSessionResponse = { session("fresh") }
        val launch = CaptureLaunch(
            config, CaptureLaunch.Kind.PREBUILT,
            request = VerificationRequest(SessionType.ENROLLMENT),
            prebuiltSession = session("pre"), sessionFactory = factory,
        )
        assertEquals(CaptureLaunch.SessionSource.Prebuilt(session("pre")), launch.sessionSource(resuming = false))
        launch.prebuiltSession = null
        assertEquals(CaptureLaunch.SessionSource.Refresh(factory), launch.sessionSource(resuming = false))
    }

    // ─── The callback ───────────────────────────────────────────────────

    @Test
    fun `a result reached while no callback is attached waits for one`() {
        val launch = tokenLaunch()
        launch.gate.success(UseSenseResult("ses_1", "enrollment", "idn_1", "APPROVE", "t"))
        launch.gate.cancel() // a later close: the gate already delivered
        val cb = Recorder()
        launch.callback = cb
        assertEquals(listOf("success:APPROVE"), cb.events)
        // Re-attaching after a configuration change delivers nothing twice.
        val again = Recorder()
        launch.callback = again
        assertEquals(emptyList<String>(), again.events)
        launch.release()
    }

    // ─── Public entry points ────────────────────────────────────────────

    @Test
    fun `a blank client token fails without launching`() {
        val activity = mockk<Activity>(relaxed = true)
        val cb = Recorder()
        UseSense.startVerificationWithToken(activity, "  ", SessionType.ENROLLMENT, cb)
        assertEquals(UseSenseError.INVALID_CONFIG, cb.error?.code)
        verify(exactly = 0) { activity.startActivity(any()) }
        assertEquals(0, CaptureLaunches.allForTest().size)
    }

    @Test
    fun `a client token launch needs no initialize and no API key`() {
        val activity = mockk<Activity>(relaxed = true)
        val cb = Recorder()
        UseSense.startVerificationWithToken(activity, " cli_tok_abc ", SessionType.LIVENESS, cb)
        verify(exactly = 1) { activity.startActivity(any()) }
        val launch = CaptureLaunches.allForTest().single()
        assertEquals(CaptureLaunch.Kind.TOKEN, launch.kind)
        assertEquals("cli_tok_abc", launch.clientToken)
        assertEquals(SessionType.LIVENESS, launch.sessionType)
        assertEquals("", launch.config.apiKey)
        assertSame(cb, launch.callback)
        assertTrue(launch.isSdkSequence)
    }

    @Test
    fun `a client token launch keeps initialize's branding and base url`() {
        val app = mockk<android.content.Context>(relaxed = true)
        every { app.applicationContext } returns app
        UseSense.initialize(app, config.copy(branding = com.usesense.sdk.BrandingConfig(displayName = "Code")))
        UseSense.startVerificationWithToken(mockk(relaxed = true), "cli_tok_abc", SessionType.ENROLLMENT, Recorder())
        val launch = CaptureLaunches.allForTest().single()
        assertEquals("Code", launch.config.branding?.displayName)
        assertEquals(config.baseUrl, launch.config.baseUrl)
    }

    @Test
    fun `startVerification without an API key fails fast`() {
        val app = mockk<android.content.Context>(relaxed = true)
        every { app.applicationContext } returns app
        UseSense.initialize(app, config) // token-only integration: blank key
        val activity = mockk<Activity>(relaxed = true)
        val cb = Recorder()
        UseSense.startVerification(activity, VerificationRequest(SessionType.ENROLLMENT), cb)
        assertEquals(UseSenseError.INVALID_CONFIG, cb.error?.code)
        verify(exactly = 0) { activity.startActivity(any()) }
    }

    @Test
    fun `the token fragment reports a blank token through its callback`() {
        val fragment = UseSenseFragment.newInstanceWithToken(" ", SessionType.ENROLLMENT)
        val launch = CaptureLaunches.allForTest().single()
        assertEquals(UseSenseError.INVALID_CONFIG, launch.initialError?.code)
        assertNotNull(fragment)
    }
}
