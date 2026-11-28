package org.medianexus.music.nexus

import android.os.Looper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.BuildConfig
import org.medianexus.music.graph
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * **In a build without the Nexus server's client** (no `-PnexusExists=true`: every published
 * build), nothing of it exists: signed in over HTTPS to an origin whose backend answers `hello`,
 * the app in front, the library loaded, a minute of the client's looking in the tests' time:
 * no launcher, no thread of its, not one request under `/nexus/`. (The row under Settings is
 * behind the same switch.) Run whatever the build: it switches the client off for itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class NexusSwitchedOffTest {
    companion object {
        @JvmStatic @BeforeClass fun trust() = BackendStandIn.trust()
        @JvmStatic @AfterClass fun untrust() = BackendStandIn.untrust()
    }

    private val app = RuntimeEnvironment.getApplication()
    private val origin = BackendStandIn(BackendStandIn.Broken.ABSENT).also { it.answer = { BackendStandIn.hello() } }

    @Before
    fun start() {
        NexusBuild.forTests = false
        NexusLauncher.period = 50
        origin.start()
        runBlocking { app.graph.session.restored() }
        org.medianexus.music.core.Core.warm()
    }

    @After
    fun stop() {
        NexusBuild.forTests = null
        NexusLauncher.period = NexusLauncher.HOURLY_MILLIS
        origin.stop()
    }

    private fun clientThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("nexus-") }.toSet()

    /** A client's thread carries its generation; one of an earlier test's (in this process) is
     *  of a generation given out before this test began. */
    private fun ofThisTest(thread: Thread, since: Long) = thread.name.substringAfterLast('-').toLongOrNull()?.let { it > since } ?: true

    @Test
    fun withoutTheSwitchNothingOfTheClientExists() {
        // (Threads of an earlier test's app, in this process, are not this one's.)
        val before = clientThreads()
        val since = NexusLauncher.lastGeneration()
        app.graph.front.value = true
        origin.signIn(app)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertNull("a launcher was made", app.graph.nexus)
        assertTrue("asked under /nexus/: ${origin.asked}", origin.asked.none { "/nexus/" in it })
        assertEquals("a thread of the client's", emptyList<String>(), (clientThreads() - before).filter { ofThisTest(it, since) }.map { it.name })
        assertNull(app.graph.nexusShown(app.graph.session.state.value))
    }

    @Test
    fun theBuildsOwnAnswerIsTheProperty() {
        NexusBuild.forTests = null
        assertEquals(BuildConfig.NEXUS, NexusBuild.on)
    }
}
