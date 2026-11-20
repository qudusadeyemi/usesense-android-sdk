package com.usesense.sdk.ui

import android.Manifest
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.usesense.sdk.R
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.VerificationRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * J2: the capture runs embedded ([UseSenseFragment]) and full screen
 * ([HostedPageActivity]) on the same Fragment. A mock server stands in for
 * the API; each test drives the screen to the primer (the first page after
 * the session is ready) and then exercises one lifecycle event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureScreenLifecycleTest {

    /** The app's own screen that embeds the capture. */
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_UseSense)
            super.onCreate(savedInstanceState)
        }
    }

    private class Recorder : UseSenseCallback {
        val events = mutableListOf<String>()
        override fun onSuccess(result: UseSenseResult) { events += "success:${result.decision}" }
        override fun onError(error: UseSenseError) { events += "error:${error.serverCode ?: error.code}" }
        override fun onCancelled() { events += "cancelled" }
    }

    private lateinit var server: MockWebServer
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val sessionJson = """
        {"session_id":"ses_1","session_token":"st_1","expires_at":"2099-01-01T00:00:00Z","nonce":"n_1",
         "policy":{"requires_audio":false,"requires_stepup":false},
         "upload":{"max_frames":30,"target_fps":10,"capture_duration_ms":2500},
         "geometric_coherence":{"dual_path_enabled":false,"screen_illumination_enabled":false,
           "on_device_3dmm_required":false,"mesh_binding_challenge":"mbc"},
         "branding":{"display_name":"Acme"}}
    """.trimIndent()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        CaptureLaunches.clearForTest()
        UseSense.reset()
    }

    @After fun tearDown() {
        server.shutdown()
        CaptureLaunches.clearForTest()
        UseSense.reset()
    }

    private fun initialize(apiKey: String) =
        UseSense.initialize(app, UseSenseConfig(apiKey = apiKey, baseUrl = server.url("/").toString()))

    /** Run the main looper until [done] holds (network replies land on it). */
    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun primerShown(activity: AppCompatActivity): Boolean =
        activity.findViewById<View>(R.id.hostedPage)?.visibility == View.VISIBLE &&
            activity.findViewById<View>(R.id.hostedPagePrimary)?.visibility == View.VISIBLE

    private fun embed(fragment: UseSenseFragment): ActivityController<HostActivity> {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment, TAG)
            .commitNow()
        return controller
    }

    // ─── Embedded ───────────────────────────────────────────────────────

    @Test
    fun `embedded token capture survives rotation without a second exchange`() {
        initialize(apiKey = "") // token-only app: no key on the device
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val cb = Recorder()
        val controller = embed(UseSenseFragment.newInstanceWithToken("cli_tok_x", SessionType.ENROLLMENT).apply { setCallback(cb) })
        waitFor("the primer") { primerShown(controller.get()) }
        val exchange = server.takeRequest()
        assertTrue(exchange.path!!.startsWith("/v1/sessions/exchange-token"))
        assertEquals(null, exchange.getHeader("x-api-key"))

        controller.recreate()
        val cb2 = Recorder()
        val restored = controller.get().supportFragmentManager.findFragmentByTag(TAG) as UseSenseFragment
        restored.setCallback(cb2)
        waitFor("the primer after rotation") { primerShown(controller.get()) }

        assertEquals("a configuration change delivers nothing", emptyList<String>(), cb.events + cb2.events)
        assertEquals("the single-use token is exchanged once", 1, server.requestCount)
    }

    @Test
    fun `back press on the embedded capture cancels once`() {
        initialize(apiKey = "pk_sandbox_x")
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val cb = Recorder()
        val controller = embed(UseSenseFragment.newInstance(VerificationRequest(SessionType.ENROLLMENT)).apply { setCallback(cb) })
        waitFor("the primer") { primerShown(controller.get()) }
        assertEquals("pk_sandbox_x", server.takeRequest().getHeader("x-api-key"))

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cancelled"), cb.events)

        // The app removes the Fragment in its callback: nothing fires twice.
        val fm = controller.get().supportFragmentManager
        fm.beginTransaction().remove(fm.findFragmentByTag(TAG)!!).commitNow()
        assertEquals(listOf("cancelled"), cb.events)
        assertTrue(CaptureLaunches.allForTest().isEmpty())
    }

    @Test
    fun `the host activity finishing cancels the embedded capture`() {
        initialize(apiKey = "pk_sandbox_x")
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val cb = Recorder()
        val controller = embed(UseSenseFragment.newInstance(VerificationRequest(SessionType.ENROLLMENT)).apply { setCallback(cb) })
        waitFor("the primer") { primerShown(controller.get()) }

        controller.get().finish()
        controller.pause().stop().destroy()
        assertEquals(listOf("cancelled"), cb.events)
    }

    @Test
    fun `the embedded capture asks for the camera from the fragment`() {
        initialize(apiKey = "pk_sandbox_x")
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val controller = embed(UseSenseFragment.newInstance(VerificationRequest(SessionType.ENROLLMENT)).apply { setCallback(Recorder()) })
        waitFor("the primer") { primerShown(controller.get()) }

        controller.get().findViewById<View>(R.id.hostedPagePrimary).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val asked = shadowOf(controller.get()).lastRequestedPermission
        assertNotNull("a permission request went out", asked)
        assertTrue(asked.requestedPermissions.contains(Manifest.permission.CAMERA))
    }

    @Test
    fun `a host activity implementing the callback is picked up after rotation`() {
        initialize(apiKey = "pk_sandbox_x")
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val controller = Robolectric.buildActivity(CallbackHost::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, UseSenseFragment.newInstance(VerificationRequest(SessionType.ENROLLMENT)), TAG)
            .commitNow()
        waitFor("the primer") { primerShown(controller.get()) }
        controller.recreate()
        waitFor("the primer after rotation") { primerShown(controller.get()) }
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cancelled"), controller.get().events)
        assertEquals("rotation reuses the session", 1, server.requestCount)
    }

    class CallbackHost : AppCompatActivity(), UseSenseCallback {
        val events = mutableListOf<String>()
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_UseSense)
            super.onCreate(savedInstanceState)
        }
        override fun onSuccess(result: UseSenseResult) { events += "success" }
        override fun onError(error: UseSenseError) { events += "error" }
        override fun onCancelled() { events += "cancelled" }
    }

    /**
     * A host on a plain AppCompat theme (a fresh React Native app's AppTheme).
     * MaterialButton in the capture layout requires Theme.MaterialComponents:
     * the Fragment must bring its own theme instead of the host's.
     */
    class PlainAppCompatHost : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
            super.onCreate(savedInstanceState)
        }
    }

    @Test
    fun `embedded capture runs in a host without a Material theme`() {
        initialize(apiKey = "")
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        val cb = Recorder()
        val controller = Robolectric.buildActivity(PlainAppCompatHost::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, UseSenseFragment.newInstanceWithToken("cli_tok_x", SessionType.ENROLLMENT).apply { setCallback(cb) }, TAG)
            .commitNow()
        waitFor("the primer") { primerShown(controller.get()) }

        // The capture's views carry the SDK theme; the host's own theme is untouched.
        val button = controller.get().findViewById<View>(R.id.errorPrimaryButton)
        val attr = android.util.TypedValue()
        assertTrue(button.context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryVariant, attr, true))
        assertTrue(!controller.get().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryVariant, attr, true))

        // Rotation re-inflates under the same theme.
        controller.recreate()
        (controller.get().supportFragmentManager.findFragmentByTag(TAG) as UseSenseFragment).setCallback(cb)
        waitFor("the primer after rotation") { primerShown(controller.get()) }
        assertEquals(emptyList<String>(), cb.events)
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cancelled"), cb.events)
    }

    // ─── Full screen (unchanged behaviour) ──────────────────────────────

    private fun startFullScreen(cb: Recorder): ActivityController<HostedPageActivity> {
        initialize(apiKey = "pk_sandbox_x")
        server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
        HostedPageActivity.startDirect(app, UseSense.currentConfig!!, VerificationRequest(SessionType.ENROLLMENT), cb)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(HostedPageActivity::class.java.name, intent.component?.className)
        return Robolectric.buildActivity(HostedPageActivity::class.java, intent).setup()
    }

    @Test
    fun `full screen back press finishes and cancels once on destroy`() {
        val cb = Recorder()
        val controller = startFullScreen(cb)
        waitFor("the primer") { primerShown(controller.get()) }

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(controller.get().isFinishing)
        assertEquals("delivered from onDestroy, as before", emptyList<String>(), cb.events)
        controller.pause().stop().destroy()
        assertEquals(listOf("cancelled"), cb.events)
    }

    @Test
    fun `full screen rotation keeps the verification and its session`() {
        val cb = Recorder()
        val controller = startFullScreen(cb)
        waitFor("the primer") { primerShown(controller.get()) }
        controller.recreate()
        waitFor("the primer after rotation") { primerShown(controller.get()) }
        assertEquals(emptyList<String>(), cb.events)
        assertEquals(1, server.requestCount)
        controller.get().finish()
        controller.pause().stop().destroy()
        assertEquals(listOf("cancelled"), cb.events)
    }

    private companion object {
        const val TAG = "usesense"
    }
}
