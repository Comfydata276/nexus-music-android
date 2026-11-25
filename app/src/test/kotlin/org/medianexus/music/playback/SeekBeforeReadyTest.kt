package org.medianexus.music.playback

import android.app.Notification
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.medianexus.music.sync.QueueHere
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A seek in a song the player has not yet made ready: one put in the player and never played
 * in this run (another device's queue taken up; the last run's queue put back). Every seek
 * here is sent the way a person's is, **through a real Media3 controller connected to the
 * session**: the app's own screens' ([PlayerConnection]), and an outside app's that the
 * person allowed. A controller decides for itself whether a command may be sent at all, from
 * what the player says is available, and what it decides not to send the service never hears
 * of: a test that calls the service's side directly goes round the very place a seek was
 * being lost (the update walk for 0.5.1 found that; `ContinueHereTest` had not).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class SeekBeforeReadyTest {
    // (No JUnit time limit: see ContinueHereTest. Everything that waits has its own.)

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val server = StandInServer()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** Android's foreground, which lets the service in. */
    private class Android : PlaybackService.Foreground {
        var inIt = false
        override fun isIn() = inIt
        override fun enter(notification: Notification) { inIt = true }
        override fun leave() { inIt = false }
    }

    @Before
    fun start() {
        org.medianexus.music.sync.InStepBuild.forTests = true
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        server.start()
        server.signIn(app)
        startService()
    }

    private fun startService() {
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        inside = service.inside()
        // (Where a controller that names the service finds it.)
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, PlaybackService::class.java),
            service.onBind(Intent(MediaLibraryService.SERVICE_INTERFACE)),
        )
        settle()
    }

    @After
    fun stop() {
        scope.cancel()
        app.graph.player.release()
        service.onDestroy()
        settle()
        server.stop()
        org.medianexus.music.sync.InStepBuild.forTests = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun until(what: String, done: () -> Boolean) {
        val limit = System.nanoTime() + 30_000_000_000L
        while (!done()) {
            settle()
            check(System.nanoTime() < limit) { "not within thirty seconds: $what" }
            Thread.sleep(5)
        }
        settle()
    }

    /** Runs `block` on the main thread and lets that thread turn until it is done. */
    private fun <T> wait(block: suspend () -> T): T {
        val doing = scope.async { block() }
        until("what was being waited for") { doing.isCompleted }
        @Suppress("OPT_IN_USAGE")
        return doing.getCompleted()
    }

    private fun here(): QueueHere = app.graph.accounts.state.value!!.queueStep!!.here!!

    private fun mine() = List(5) { Song(id = "s$it", title = "Song $it", duration = 200) }
    private fun theirs() = List(3) { Song(id = "pc$it", title = "Theirs $it", duration = 200) }
    private fun later() = System.currentTimeMillis() / 1000 + 60
    private fun now() = System.currentTimeMillis() / 1000

    /** What the server answers for the saved queue: one another device saved, just now. */
    private fun savedElsewhere() = """"playQueue":{"current":"pc2","position":5000,"changed":"${java.time.Instant.now().plusSeconds(120)}","changedBy":"NexusMusic-0badc0de","entry":[""" +
        (0..2).joinToString(",") { """{"id":"pc$it","title":"Theirs $it","duration":200}""" } + "]}"

    /** The app's own screens, connected as they connect. */
    private fun screens(): PlayerConnection {
        val controllers = inside.session.connectedControllers.size
        app.graph.player.connect()
        until("the screens' controller is connected") { inside.session.connectedControllers.size > controllers }
        return app.graph.player
    }

    /** Every place the screens have shown since this was called, in order. */
    private val places = java.util.concurrent.CopyOnWriteArrayList<Long>()

    /** What the screens show as the place in the song, as it changes. */
    private fun shown(player: PlayerConnection): () -> Long {
        scope.launch { player.position.collect { places += it } }
        until("the screens' place is being read") { places.isNotEmpty() }
        return {
            // (What the screens are shown reaches them by way of other threads: a moment,
            // with a limit, for whatever was set to arrive.)
            repeat(40) {
                settle()
                Thread.sleep(10)
            }
            places.last()
        }
    }

    /** What is kept for the next run. */
    private fun kept(): SavedQueue? {
        kotlinx.coroutines.runBlocking { app.graph.queueSaver.flush() }
        return QueueFiles.of(app.graph.accounts.state.value!!.account.dirs.data).load()
    }

    @Test
    fun aSeekInAQueueTakenUpAndNeverPlayedIsMadeIsKeptAndMakesTheQueueThisPhonesOwn() {
        val screens = screens()
        // Another device's queue, taken up: in the player, paused, and never made ready.
        assertTrue(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = Choices.latest, stampThen = here().stamp(), still = { true }) })
        settle()
        assertEquals("2026-01-01T00:00:00Z", here().taken())
        assertEquals(Player.STATE_IDLE, inside.exo.playbackState)
        assertEquals(30_000L, inside.exo.currentPosition)
        val shows = shown(screens)

        // The person drags the bar on the app's own player to 1:00.
        screens.seek(60_000)
        settle()

        // The place is moved in the player, and the screen shows the place the player holds.
        assertEquals("the place in the player", 60_000L, inside.exo.currentPosition)
        assertEquals("the place on the screen", inside.exo.currentPosition, shows())
        // It is the person's own doing: the queue is this phone's own from the drag, by
        // this phone's clock.
        assertNull("the queue is still the other device's", here().taken())
        assertTrue(here().stamp() in now() - 5..now() + 1)
        // The place is what a save of the queue carries, and what is kept for the next run.
        assertEquals(60.0, here().queue()!!.positionSeconds, 0.01)
        val file = kept()!!
        assertEquals(60.0, file.position, 0.01)
        assertNull(file.taken)

        // And when the other device saves again, its queue is offered, not put in this
        // one's place.
        server.answers["getPlayQueue"] = savedElsewhere()
        val step = app.graph.accounts.state.value!!.queueStep!!
        val looking = scope.async { step.check() }
        until("the look ran") { looking.isCompleted }
        assertEquals("Theirs 2", step.elsewhere.value?.title)
        assertEquals("pc1", inside.player.queue.current()?.id)
        assertEquals(60_000L, inside.exo.currentPosition)
    }

    @Test
    fun aSeekInTheLastRunsQueueThenPlayStartsFromTheDraggedPlace() {
        // Something chosen and left paused; then the app is gone, and back.
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        service.onDestroy()
        settle()
        startService()
        until("the last run's queue is back") { inside.player.queue.current()?.id == "s1" }
        assertEquals(Player.STATE_IDLE, inside.exo.playbackState)
        val screens = screens()
        val shows = shown(screens)

        // A drag on the paused song's bar, then Play.
        screens.seek(45_000)
        settle()
        assertEquals("the place in the player", 45_000L, inside.exo.currentPosition)
        assertEquals("the place on the screen", 45_000L, shows())
        screens.playPause()
        until("the player is asked to play") { inside.exo.playWhenReady }
        assertEquals("s1", inside.player.queue.current()?.id)
        assertTrue("it starts from ${inside.exo.currentPosition} ms, not from the dragged place", inside.exo.currentPosition in 45_000L..47_000L)
    }

    @Test
    fun whatAControllerMaySendInEachStateOfAPlayerNotYetMadeReady() {
        // A controller sends only what the player says is available, and drops the rest
        // without a word: so what the player says, in each state a person can meet, is what
        // their buttons do. Read from a real controller, connected as the screens' is.
        val connecting = MediaController.Builder(app, inside.session.token).buildAsync()
        until("a controller is connected") { connecting.isDone }
        val controller = connecting.get()
        fun may(command: Int): Boolean {
            settle()
            return controller.isCommandAvailable(command)
        }
        val seeking = listOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        val skipping = listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)

        // Nothing in the player: Play may be pressed (the service answers it), and there is
        // nothing to skip to or seek in.
        assertTrue(inside.player.queue.isEmpty)
        assertTrue(may(Player.COMMAND_PLAY_PAUSE))
        for (command in seeking + skipping) assertTrue("command $command with nothing in the player", !may(command))

        // A song put in the player and not made ready: everything a person can press.
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        assertEquals(Player.STATE_IDLE, inside.exo.playbackState)
        for (command in seeking + skipping + Player.COMMAND_PLAY_PAUSE + Player.COMMAND_SEEK_TO_MEDIA_ITEM + Player.COMMAND_STOP) {
            assertTrue("command $command on a song not yet made ready", may(command))
        }
        assertTrue("the song is said to be one that can't be sought in", controller.isCurrentMediaItemSeekable)
        assertEquals(200_000L, controller.duration)

        // A radio station there instead: it can be played, stopped and skipped past, and
        // has nowhere to seek to, ready or not.
        inside.player.play(listOf(Song(id = "station", title = "A station", radioUrl = "http://127.0.0.1:9/stream"), mine()[0]), 0, "Radio", playNow = false)
        settle()
        assertEquals("station", inside.player.queue.current()?.id)
        for (command in seeking) assertTrue("command $command on a station", !may(command))
        for (command in skipping + Player.COMMAND_PLAY_PAUSE) assertTrue("command $command on a station", may(command))
        // And a seek the player does not take is not shown as made: the screens show the
        // place the player holds, whatever was asked for.
        val screens = screens()
        val shows = shown(screens)
        screens.seek(30_000)
        settle()
        assertEquals(0L, inside.exo.currentPosition)
        assertEquals(0L, shows())
        // (Not even for a moment, until the player's next word put it right.)
        assertTrue("the screen showed a place the player was never at: $places", 30_000L !in places)
        controller.release()
    }

    /** Puts an app on the stand-in phone: this package at this uid, signed with this. */
    private fun install(name: String, uid: Int, certificate: ByteArray) {
        val manager = shadowOf(app.packageManager)
        manager.installPackage(
            PackageInfo().apply {
                packageName = name
                @Suppress("DEPRECATION")
                signatures = arrayOf(Signature(certificate))
                signingInfo = SigningInfo().also {
                    org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(certificate)))
                }
                applicationInfo = ApplicationInfo().apply {
                    packageName = name
                    this.uid = uid
                    nonLocalizedLabel = "Watch plug-in"
                }
            },
        )
        manager.setPackagesForUid(uid, name)
        app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:$name")))
        settle()
    }

    /** Does something as the app at `uid` does it: what the session is told of its caller. */
    private fun <T> asUid(uid: Int, block: () -> T): T {
        val mine = android.os.Process.myUid()
        org.robolectric.shadows.ShadowBinder.setCallingUid(uid)
        try {
            return block().also { settle() }
        } finally {
            org.robolectric.shadows.ShadowBinder.setCallingUid(mine)
        }
    }

    @Test
    fun anAppThePersonAllowedCanSeekInASongNotYetMadeReadyToo() {
        // A watch's plug-in on the phone, with a Media3 controller of its own, allowed by
        // the person. (It names the service, as any app may.)
        val name = "com.example.watchplugin"
        val uid = 10_777
        install(name, uid, "a watch plug-in's certificate".toByteArray())
        val asTheWatch = object : ContextWrapper(app) {
            override fun getPackageName() = name
        }
        fun connect(): MediaController? {
            val connecting = asUid(uid) { MediaController.Builder(asTheWatch, inside.session.token).buildAsync().also { settle() } }
            until("the plug-in's connection is answered") { connecting.isDone }
            return runCatching { connecting.get() }.getOrNull()
        }
        assertNull("an app nobody allowed was let in", connect())
        val entry = app.graph.settings.state.value.playbackApps.single { it.packageName == name }
        app.graph.controlApps.allow(entry, true)
        val watch = connect() ?: error("the app the person allowed was refused")

        // Another device's queue, taken up and never made ready.
        assertTrue(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = Choices.latest, stampThen = here().stamp(), still = { true }) })
        settle()
        assertEquals(Player.STATE_IDLE, inside.exo.playbackState)
        assertTrue("the plug-in is not told it may seek", watch.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertEquals("the song's length, as the plug-in is told it", 200_000L, watch.duration)

        // The watch's own bar is dragged.
        asUid(uid) { watch.seekTo(90_000) }
        settle()
        assertEquals("the place in the player", 90_000L, inside.exo.currentPosition)
        assertEquals("the place the plug-in is told", 90_000L, watch.currentPosition)
        assertNull("the queue is still the other device's", here().taken())
        asUid(uid) { watch.release() }
    }
}
