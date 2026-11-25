package org.medianexus.music.jam

import android.content.Intent
import android.net.Uri
import android.os.Looper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.playback.StandInServer
import org.medianexus.music.ui.shell.Navigator
import org.medianexus.music.ui.shell.Route
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Jams and linked devices are switched off in this build ([JamBuild]): with them off nothing
 * of them runs, whatever would set it off, nothing leads to them, and the app offers other
 * apps nothing for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class SwitchedOffTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = StandInServer()
    private val relay = MockWebServer()

    @Before
    fun start() {
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        relay.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) = MockResponse.Builder().code(404).build()
        }
        relay.start()
        server.start()
    }

    @After
    fun stop() {
        server.stop()
        relay.close()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun switchedOffNothingOfThemRunsOrIsReachable() {
        assertFalse("jams exist in this build", JamBuild.EXISTS)
        // Everything set as for hosting and linking devices, at a relay of the test's own.
        app.graph.settings.update { it.copy(jamHost = true, linkDevices = true, jamRelay = relay.url("/").toString()) }
        server.signIn(app)
        settle()
        // What would make them, or speak to them: music playing and stopping, a sign-out and
        // back in, the screens' way to the jam page.
        app.graph.playing = true
        settle()
        app.graph.playing = false
        app.graph.session.signOut()
        settle()
        server.signIn(app)
        settle()
        val nav = Navigator.fresh()
        nav.open(Route.Jam())
        nav.open(Route.Jam("https://relay.example/j/abc#key"))
        assertEquals(listOf(Route.Home), nav.visible.map { it.route })
        Thread.sleep(500)
        settle()

        // Never made, and can't be.
        assertNull(app.graph.jamsIfMade())
        assertTrue(runCatching { app.graph.jams }.exceptionOrNull() is IllegalStateException)
        assertNull(app.graph.jamsIfMade())
        assertNull(app.graph.steered.shown.value)
        assertNull(app.graph.steered.hands)
        // The relay was never asked anything.
        assertEquals(0, relay.requestCount)
        // No key was kept or asked for.
        assertFalse(app.graph.relayKeyKept())

        // Nothing for other apps: no activity takes a jam's link, shared or opened; no service.
        val packages = app.packageManager
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        assertTrue(packages.queryIntentActivities(shared, 0).isEmpty())
        val opened = Intent(Intent.ACTION_VIEW, Uri.parse("https://relay.example/j/abc")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
        assertTrue(packages.queryIntentActivities(opened, 0).isEmpty())
        assertNull(packages.resolveService(Intent().setClassName(app.packageName, "org.medianexus.music.jam.JamService"), 0))
        assertNull(packages.resolveActivity(Intent().setClassName(app.packageName, "org.medianexus.music.jam.JamLinkActivity"), 0))
        @Suppress("DEPRECATION")
        val asked = packages.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in asked)
        assertTrue(generateSequence { shadowOf(app).nextStartedService }.none { it.component?.className == "org.medianexus.music.jam.JamService" })
    }
}
