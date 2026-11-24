package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.session.MediaController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Play, Play next and Add to queue on an album's, artist's or playlist's menu (M3b fixes, C2).
 * The songs are looked up away from the main thread; what is done with them goes to a real
 * MediaController, connected to the playback service's own session, which Media3 lets be
 * called only from its own thread. They used to be handed to it from the lookup's thread, and
 * Media3 threw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class MenuLookupTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var controller: MediaController
    /** As the menus' work runs: on a background dispatcher, failures not thrown at the test. */
    private val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e })

    @Before
    fun start() {
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        // The app's own connection to the service, as Android would bind it.
        shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, PlaybackService::class.java),
            service.onBind(android.content.Intent(androidx.media3.session.MediaLibraryService.SERVICE_INTERFACE)),
        )
        val connecting = MediaController.Builder(app, service.inside().session.token).buildAsync()
        settle()
        controller = connecting.get()
    }

    @After
    fun stop() {
        scope.cancel()
        controller.release()
        service.onDestroy()
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun until(what: String, done: () -> Boolean) {
        val limit = System.nanoTime() + 30_000_000_000L
        while (!done()) {
            settle()
            check(System.nanoTime() < limit) { "$what: not within thirty seconds" }
            Thread.yield()
        }
    }

    @Test
    fun whatALookupFindsReachesTheControllerOnItsOwnThread() {
        var acted = false
        // The lookup itself on another thread, as an album's is.
        app.graph.player.afterLookup(scope, { withContext(Dispatchers.IO) { listOf(Song(id = "s1")) } }) {
            controller.play()
            acted = true
        }
        until("the lookup came back") { acted || errors.isNotEmpty() }
        assertEquals(emptyList<Throwable>(), errors.toList())
        assertTrue(acted)
    }

    @Test
    fun aLookupOvertakenBySomethingChosenSinceDoesNothing() {
        var acted = false
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        app.graph.player.afterLookup(scope, {
            reached.complete(Unit)
            gate.await()
            listOf(Song(id = "s1"))
        }) { acted = true }
        until("the lookup is out") { reached.isCompleted }
        // Something else is chosen while it is out.
        app.graph.player.addToQueue(listOf(Song(id = "s9")))
        gate.complete(Unit)
        settle()
        assertEquals(false, acted)
    }

    @Test
    fun ofTwoTapsTheLaterWinsWhicheverLookupFinishesFirst() {
        // (M3b fixes 2, B1.) Two collections' Play, both needing a lookup, tapped in turn.
        val acted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        app.graph.player.afterLookup(scope, { first.await(); listOf(Song(id = "a")) }) { acted += "first" }
        app.graph.player.afterLookup(scope, { second.await(); listOf(Song(id = "b")) }) { acted += "second" }
        // The earlier tap's lookup comes back first: it has been overtaken.
        first.complete(Unit)
        settle()
        second.complete(Unit)
        until("the later lookup came back") { acted.isNotEmpty() || errors.isNotEmpty() }
        settle()
        assertEquals(listOf("second"), acted.toList())
    }
}
