package org.medianexus.music.nexus

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.SessionState
import org.medianexus.music.downloads.DownloadKeeper
import org.medianexus.music.downloads.DownloadsJob
import org.medianexus.music.downloads.DownloadsService
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.medianexus.music.nexus.BackendStandIn.Broken
import org.medianexus.music.playback.PlayButtonReceiver
import org.medianexus.music.playback.PlaybackService
import org.medianexus.music.playback.QueueFiles
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.accountDirs
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * **What never waits for the backend** (docs/backend-design.md, 5.3): the phone driven through
 * its real entry points while everything under `/nexus/` at the music server's origin is broken
 * in each of the design's ways ([Broken]), the music server itself answering as ever.
 *
 * **The proof is by construction** ([ComponentHolds]): before an entry point is driven, the
 * Nexus server's client is put in the case's state and every hold on it acknowledged (its
 * `hello` kept by the stand-in, or its own threads held); the entry point is driven to its
 * outcome, which is checked; and the holds are checked to be in place still. An entry point
 * that waited on the client could not have completed.
 *
 * The cold ones are also driven **before the account is published**: the app's objects made
 * over a saved sign-in whose reading is held (a password box that opens only when the test
 * says), the entry point driven, the reading let go, and the entry point carried to its outcome,
 * with the client's launcher held from its first instruction in [Broken.WORKERS_HELD].
 *
 * Each step is timed as well, a second and looser alarm (see [Times]): first on the app as
 * released, to fix the numbers (experiment E13a), and then held to them.
 *
 * Nothing here waits for an event by the clock: the main thread moves only when a test lets it
 * ([settle]), and a test waits for another thread's work by letting the main thread run until
 * that work is done, with the main thread's clock standing still.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = App::class)
class NeverWaitsTest(private val broken: Broken) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Broken.entries.map { arrayOf(it) }

        @JvmStatic
        @BeforeClass
        fun trust() = BackendStandIn.trust()

        @JvmStatic
        @AfterClass
        fun untrust() = BackendStandIn.untrust()

        /** Android's limit for a service started in the foreground to say so. */
        const val ANDROID_FOREGROUND_LIMIT_MILLIS = 5_000.0
    }

    /** The audio decoders every phone has, as Robolectric stands in for the system's list. */
    @get:org.junit.Rule val decoders = org.medianexus.music.downloads.phoneDecoders()

    private val app = RuntimeEnvironment.getApplication()
    private val standIn = BackendStandIn(broken)
    private val graphs = ArrayList<Graph>()
    private val services = ArrayList<android.app.Service>()
    private val holds = ComponentHolds(broken, standIn, app.filesDir) { what, done -> until(what, done) }

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    /** A saved password that is read only when the test lets it: the account is not published
     *  until then. */
    private class GatedBox : SecretBox {
        val opening = CountDownLatch(1)
        val open = CountDownLatch(1)
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray): ByteArray {
            opening.countDown()
            open.await(30, TimeUnit.SECONDS)
            return sealed
        }
    }

    /** Android's foreground, for a service whose entering it is what is being timed. */
    private class Foreground : PlaybackService.Foreground {
        @Volatile var inIt = false
        @Volatile var enteredAt = 0L
        override fun isIn() = inIt
        override fun enter(notification: Notification) {
            if (!inIt) enteredAt = System.nanoTime()
            inIt = true
        }
        override fun leave() {
            inIt = false
        }
    }

    /** A car, as Media3 describes one that speaks the platform's older protocol. */
    private val car = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.example.car", 4_321, 10_123, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, Bundle.EMPTY, true,
    )

    @Before
    fun start() {
        DownloadKeeper.visible = false
        holds.beforeAppStarts()
        standIn.start()
        holds.inUse(app.graph)
        runBlocking { app.graph.session.restored() }
    }

    @After
    fun stop() {
        try {
            holds.after()
            // **The music server's own connections were never the backend's.**
            assertTrue(
                "a connection carried both the music server's requests and the backend's",
                standIn.musicConnections.intersect(standIn.nexusConnections).isEmpty(),
            )
            // Never a backend shown under the account published now.
            assertNull(holds.shown(app.graph))
        } finally {
            for (service in services.asReversed()) runCatching { service.onDestroy() }
            runCatching { settle() }
            graphs.forEach { it.scope.cancel() }
            (graphs + app.graph).forEach { runCatching { it.http.connectionPool.evictAll() } }
            standIn.stop()
        }
    }

    // ---- How the tests are run ---------------------------------------------------------------

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the main thread run until `done`, with its clock standing still. Fails after thirty
     *  seconds of the wall clock. */
    private fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 30_000_000_000L
        while (true) {
            settle()
            if (done()) return
            check(System.nanoTime() < end) { "not so within thirty seconds: $what. Lately noted: ${app.graph.diagnostics.entries().takeLast(8).map { it.text }}" }
            Thread.sleep(2)
        }
    }

    private fun <T> answer(future: ListenableFuture<T>): T {
        until("answered") { future.isDone }
        return future.get()
    }

    /** Times `block` as `step` of `test`, records it, and holds it to the released app's
     *  (see [Times]); then checks that every hold on the client is still in place. */
    private fun <T> timed(test: String, step: String, block: () -> T): T {
        val began = System.nanoTime()
        val result = block()
        val millis = (System.nanoTime() - began) / 1e6
        Times.record(test, broken.name, step, millis)
        if (broken != Broken.WARM_UP) {
            Times.heldOneCase(test, step)?.let { limit ->
                assertTrue("$test, $step: ${"%.0f".format(millis)} ms; one case is held to the released app's median and a second more: ${"%.0f".format(limit)}", millis <= limit)
            }
            val run = Times.thisRun(test, step)
            assertTrue(
                "$test, $step: this run's median over its cases, ${"%.0f".format(Times.middle(run))} ms, is over the released app's median and 300 ms: ${Times.held(test, step)?.let { "%.0f".format(it) }}",
                !Times.overRun(test, step, run),
            )
        }
        holds.stillStuck("$test, $step")
        return result
    }

    private fun account() = app.graph.accounts.state.value!!

    private fun songs() = standIn.songs.map { id ->
        Song(id = id, title = "Song ${id.removePrefix("s")}", album = "First Light", albumId = "al-1", artist = "Paper Lanterns", artistId = "ar-1", track = id.removePrefix("s").toInt(), duration = 3, suffix = "flac")
    }

    private fun service(foreground: Foreground? = null): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).create().get().also { service ->
            foreground?.let { service.foreground = it }
            services += service
        }

    private fun settled(service: PlaybackService) = service.inside().session.sessionExtras.getBoolean(PlaybackService.EXTRA_SETTLED)

    private fun queueFile(): File = QueueFiles.queueFile(accountDirs(app.filesDir, app.cacheDir, account().account.key).data)

    private fun signedIn() {
        standIn.signIn(app)
        holds.stuck(app.graph)
    }

    /** Signs in, and has the playback service keep a queue of the album's songs, playing from
     *  the second; then lets the service go, as Android does when it wants the memory. The
     *  account's client is then stuck. */
    private fun savedQueue() {
        standIn.signIn(app)
        val first = service(Foreground())
        until("the first service has read what was saved") { settled(first) }
        first.inside().player.play(songs(), 1, "First Light", source = "album:al-1", playNow = false)
        until("the queue is on the disk") { queueFile().isFile && "\"s3\"" in queueFile().readText() }
        first.onDestroy()
        services -= first
        settle()
        holds.stuck(app.graph)
    }

    private fun playButton() = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(app, PlaybackService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))

    /** Makes `graph` the app's own, as a new process would have it. */
    private fun becomeTheApp(graph: Graph) {
        App::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
    }

    /**
     * A process ended and started again by Android: someone was signed in, with a queue saved;
     * the app's objects are made again over that, and the saved sign-in's reading is held, so
     * the account is **not published** until the test lets [GatedBox.open] go. The client's
     * launcher is held from its first instruction in [Broken.WORKERS_HELD].
     */
    private fun processStartedAgain(): Pair<Graph, GatedBox> {
        val before = Graph(app, PlainBox).also { graphs += it }
        becomeTheApp(before)
        standIn.signIn(app)
        val first = service(Foreground())
        until("the first service has read what was saved") { settled(first) }
        first.inside().player.play(songs(), 1, "First Light", source = "album:al-1", playNow = false)
        until("the queue is on the disk") { queueFile().isFile && "\"s3\"" in queueFile().readText() }
        first.onDestroy()
        services -= first
        settle()
        runBlocking { before.settings.flush() }
        before.scope.cancel()
        before.http.connectionPool.evictAll()
        // (The Play button's receiver looks for a saved password's file; PlainBox wrote one.)
        holds.holdTheLauncherFromItsStart()
        val box = GatedBox()
        val graph = Graph(app, box).also { graphs += it }
        becomeTheApp(graph)
        holds.inUse(graph)
        assertTrue("the saved sign-in is not being read", box.opening.await(30, TimeUnit.SECONDS))
        assertNull("published before the test let it be", graph.accounts.state.value)
        return graph to box
    }

    /** After the account is published, cold: the client, made for it, is stuck (or, held from
     *  its launcher's start, was never made). */
    private fun afterPublication(graph: Graph) {
        if (broken != Broken.WORKERS_HELD) holds.stuck(graph)
    }

    // ---- The tests ------------------------------------------------------------------------------

    @Test
    fun coldStartPublishesTheSavedAccount() {
        // Someone signed in, with the password saved; the app is ended.
        val before = Graph(app, PlainBox).also { graphs += it }
        holds.inUse(before)
        before.session.signIn(standIn.address, "tester", "a password")
        runBlocking { withTimeout(30_000) { before.accounts.state.first { it != null } } }
        runBlocking { before.settings.flush() }
        before.scope.cancel()
        // The app starts again, the client's launcher held from its first instruction where the
        // case holds it: the saved sign-in is read and the account published.
        holds.holdTheLauncherFromItsStart()
        val graph = Graph(app, PlainBox).also { graphs += it }
        holds.inUse(graph)
        val restored = timed("cold_start_publishes_the_saved_account", "restored") {
            runBlocking { withTimeout(30_000) { graph.accounts.restored() } }
        }
        assertNotNull("the saved account was not published", restored)
        assertEquals("tester", restored!!.account.username)
        afterPublication(graph)
    }

    @Test
    fun coldStartPutsTheQueueBackAndPlays() {
        savedQueue()
        val android = Foreground()
        timed("cold_start_puts_the_queue_back_and_plays", "restore") {
            val service = service(android)
            until("the saved queue is the player's") { settled(service) }
        }
        val inside = (services.last() as PlaybackService).inside()
        assertEquals("s2", inside.player.queue.current()?.id)
        timed("cold_start_puts_the_queue_back_and_plays", "play") {
            inside.player.play()
            until("playing, in the foreground") { inside.exo.playWhenReady && android.inIt }
        }
    }

    @Test
    fun aDirectPlayStartsItCold() {
        savedQueue()
        Graph.passwordFile(app).let { if (!it.isFile || it.length() == 0L) { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(16)) } }
        val started = timed("a_direct_play_starts_it_cold", "receiver") {
            PlayButtonReceiver().onReceive(app, Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.packageName).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
            shadowOf(app).nextStartedService
        }
        assertEquals("the Play button's receiver did not start the player", PlaybackService::class.java.name, started?.component?.className)
        timed("a_direct_play_starts_it_cold", "media_button") {
            val service = service()
            val inside = service.inside()
            service.addSession(inside.session)
            service.onStartCommand(started, 0, 1)
            assertTrue("not in the foreground when the start command returned", shadowOf(service).lastForegroundNotificationId != 0)
            until("playing what was saved") { inside.exo.playWhenReady && inside.player.queue.current()?.id == "s2" }
        }
    }

    /** The name the first builds gave the button's receiver (Media3's own), which Android goes
     *  on sending a Play button to until the app is next opened. */
    @Test
    fun aDirectPlayByTheReceiversOldNameStartsItCold() {
        savedQueue()
        Graph.passwordFile(app).let { if (!it.isFile || it.length() == 0L) { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(16)) } }
        val started = timed("a_direct_play_starts_it_cold", "old_receiver") {
            androidx.media3.session.MediaButtonReceiver().onReceive(app, Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(app, "androidx.media3.session.MediaButtonReceiver")).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
            shadowOf(app).nextStartedService
        }
        assertEquals(PlaybackService::class.java.name, started?.component?.className)
        timed("a_direct_play_starts_it_cold", "old_media_button") {
            val service = service()
            val inside = service.inside()
            service.addSession(inside.session)
            service.onStartCommand(started, 0, 1)
            assertTrue(shadowOf(service).lastForegroundNotificationId != 0)
            until("playing what was saved") { inside.exo.playWhenReady && inside.player.queue.current()?.id == "s2" }
        }
    }

    @Test
    fun aPlayButtonBeforeTheAccountIsPublishedStartsItCold() {
        val (graph, box) = processStartedAgain()
        val test = "a_direct_play_starts_it_cold"
        timed(test, "before_publication") {
            PlayButtonReceiver().onReceive(app, Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.packageName).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
            val started = shadowOf(app).nextStartedService
            assertEquals(PlaybackService::class.java.name, started?.component?.className)
            val service = service()
            val inside = service.inside()
            service.addSession(inside.session)
            service.onStartCommand(started, 0, 1)
            assertTrue("not in the foreground when the start command returned", shadowOf(service).lastForegroundNotificationId != 0)
            assertNull(graph.accounts.state.value)
            box.open.countDown()
            until("playing what was saved") { inside.exo.playWhenReady && inside.player.queue.current()?.id == "s2" }
        }
        afterPublication(graph)
    }

    @Test
    fun theCarStartsTheServiceCold() {
        savedQueue()
        val android = Foreground()
        val service = service(android)
        val inside = service.inside()
        val test = "the_car_starts_the_service_cold"
        val asked = System.nanoTime()
        val chosen = inside.callback.onSetMediaItems(inside.session, car, listOf(MediaItem.Builder().setMediaId("track/0/s1/album/al-1").build()), C.INDEX_UNSET, C.TIME_UNSET)
        assertTrue("not in the foreground when the car's choice arrived", android.inIt)
        val foreground = (android.enteredAt - asked) / 1e6
        Times.record(test, broken.name, "foreground", foreground)
        assertTrue("in the foreground after ${"%.0f".format(foreground)} ms", foreground < ANDROID_FOREGROUND_LIMIT_MILLIS)
        holds.stillStuck("$test, foreground")
        timed(test, "chosen") { assertChosen(answer(chosen)) }
        timed(test, "children") {
            assertEquals(SessionResult.RESULT_SUCCESS, answer(inside.callback.onGetLibraryRoot(inside.session, car, null)).resultCode)
            for (id in listOf("root", "home", "playlists", "library", "downloads")) {
                assertEquals(id, SessionResult.RESULT_SUCCESS, answer(inside.callback.onGetChildren(inside.session, car, id, 0, Int.MAX_VALUE, null)).resultCode)
            }
            val album = answer(inside.callback.onGetChildren(inside.session, car, "album/al-1", 0, Int.MAX_VALUE, null))
            assertTrue("the album's songs: ${album.value?.map { it.mediaMetadata.title }}", album.value!!.any { it.mediaMetadata.title.toString() == "Song 2" })
        }
        timed(test, "search") {
            answer(inside.callback.onSearch(inside.session, car, "lantern", null))
            val found = answer(inside.callback.onGetSearchResult(inside.session, car, "lantern", 0, 50, null))
            assertTrue("nothing found", found.value.orEmpty().isNotEmpty())
        }
        timed(test, "voice") {
            val words = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("first light").build()).build()
            val spoken = answer(inside.callback.onSetMediaItems(inside.session, car, listOf(words), C.INDEX_UNSET, C.TIME_UNSET))
            assertTrue("nothing to play for the words", spoken.mediaItems.isNotEmpty())
        }
        timed(test, "add") {
            val added = answer(inside.callback.onAddMediaItems(inside.session, car, listOf(MediaItem.Builder().setMediaId("track/2/s3/album/al-1").build())))
            assertEquals(1, added.size)
        }
        timed(test, "resumption_card") {
            val card = answer(inside.callback.onPlaybackResumption(inside.session, car, false))
            assertTrue("the card has nothing", card.mediaItems.isNotEmpty())
        }
    }

    /** What the service answers a car's choice of the album's first song with: the album, from
     *  that song. (Media3 hands it to the player; called here as Media3 calls it.) */
    private fun assertChosen(chosen: androidx.media3.session.MediaSession.MediaItemsWithStartPosition) {
        val titles = chosen.mediaItems.map { it.mediaMetadata.title.toString() }
        assertTrue("the car's choice: $titles from ${chosen.startIndex}", titles.getOrNull(maxOf(chosen.startIndex, 0)) == "Song 1")
    }

    @Test
    fun theCarBeforeTheAccountIsPublishedStartsItCold() {
        val (graph, box) = processStartedAgain()
        val android = Foreground()
        val service = service(android)
        val inside = service.inside()
        val test = "the_car_starts_the_service_cold"
        timed(test, "before_publication") {
            val chosen = inside.callback.onSetMediaItems(inside.session, car, listOf(MediaItem.Builder().setMediaId("track/0/s1/album/al-1").build()), C.INDEX_UNSET, C.TIME_UNSET)
            assertTrue("not in the foreground when the car's choice arrived", android.inIt)
            val resume = inside.callback.onPlaybackResumption(inside.session, car, false)
            assertNull(graph.accounts.state.value)
            box.open.countDown()
            assertChosen(answer(chosen))
            assertTrue(answer(resume).mediaItems.isNotEmpty())
        }
        afterPublication(graph)
    }

    @Test
    fun theNotificationsButtonsWork() {
        signedIn()
        val service = service()
        val inside = service.inside()
        until("settled") { settled(service) }
        inside.player.play(songs(), 0, "First Light", playNow = false)
        service.addSession(inside.session)
        settle()
        inside.player.prepare()
        settle()
        val test = "the_notifications_buttons_work"
        timed(test, "like") {
            val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.lastOrNull()
            assertNotNull("no media notification was posted", posted)
            val like = posted!!.actions.orEmpty().first { it.title == app.getString(R.string.add_to_liked) }
            service.onStartCommand(shadowOf(like.actionIntent).savedIntent, 0, 1)
            until("liked, and the like at the music server") { account().likes.isLiked("s1") && standIn.asked.any { "/rest/star" in it } }
        }
        timed(test, "shuffle_and_repeat") {
            val systemUi = inside.session.mediaNotificationControllerInfo!!
            val repeatBefore = inside.player.queue.repeat
            assertEquals(SessionResult.RESULT_SUCCESS, answer(inside.callback.onCustomCommand(inside.session, systemUi, PlaybackService.SHUFFLE, Bundle.EMPTY)).resultCode)
            assertTrue(inside.player.queue.shuffle)
            assertEquals(SessionResult.RESULT_SUCCESS, answer(inside.callback.onCustomCommand(inside.session, systemUi, PlaybackService.REPEAT, Bundle.EMPTY)).resultCode)
            assertTrue("repeat did not change", inside.player.queue.repeat != repeatBefore)
        }
    }

    @Test
    fun theQueueIsChangedAndKept() {
        savedQueue()
        val test = "the_queue_is_changed_and_kept"
        val service = service(Foreground())
        val inside = service.inside()
        until("settled") { settled(service) }
        val saved = { QueueFiles.of(accountDirs(app.filesDir, app.cacheDir, account().account.key).data).load()?.let { q -> q.order.map { q.songs[it].id } } }
        timed(test, "changed") {
            val player = inside.player
            player.enqueue(listOf(songs()[0]), playNext = true)
            player.enqueue(listOf(songs()[2]), playNext = false)
            settle()
            player.moveEntry(player.queue.playingUids().last(), after = null, queued = true)
            settle()
            player.removeEntry(player.queue.playingUids()[player.queue.playingUids().size - 2])
            settle()
            // s1 added next, s3 added last and moved up straight after the song playing, then
            // the first added taken out again.
            assertEquals(listOf("s1", "s2", "s3", "s3"), player.queue.playingOrder().map { it.id })
            until("the change is on the disk") { saved() == listOf("s1", "s2", "s3", "s3") }
        }
        service.onDestroy()
        services -= service
        settle()
        timed(test, "kept") {
            val again = service(Foreground())
            until("the changed queue is back") { settled(again) }
            assertEquals(listOf("s1", "s2", "s3", "s3"), again.inside().player.queue.playingOrder().map { it.id })
        }
        timed(test, "cleared") {
            (services.last() as PlaybackService).inside().player.clear()
            until("nothing is kept") { saved().isNullOrEmpty() }
        }
    }

    @Test
    fun downloadsStartAndResume() {
        signedIn()
        val test = "downloads_start_and_resume"
        val downloads = account().downloads!!
        app.graph.settings.update { it.copy(downloadWifiOnly = false) }
        timed(test, "by_hand") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[0])) }
            until("downloaded") { account().catalogue.has("s1") }
        }
        timed(test, "job") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[1])) }
            val job = Robolectric.buildService(DownloadsJob::class.java).create().get()
            services += job
            assertTrue(job.onStartJob(jobParameters()))
            until("the job carried the download to its end") { account().catalogue.has("s2") && shadowOf(job).isJobFinished }
        }
        timed(test, "service") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[2])) }
            val held = Robolectric.buildService(DownloadsService::class.java).create().get()
            services += held
            held.onStartCommand(Intent(app, DownloadsService::class.java), 0, 1)
            assertEquals(DownloadKeeper.NOTIFICATION_ID, shadowOf(held).lastForegroundNotificationId)
            until("downloaded, and the service stopped") { account().catalogue.has("s3") && shadowOf(held).isStoppedBySelf }
        }
    }

    /** A download cut off part-way, and resumed: the whole song arrives. */
    @Test
    fun aDownloadCutOffIsResumed() {
        signedIn()
        val test = "downloads_start_and_resume"
        val downloads = account().downloads!!
        app.graph.settings.update { it.copy(downloadWifiOnly = false) }
        standIn.cutOnce += "s1"
        app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[0])) }
        until("the first answer cut off") { standIn.asked.count { "/rest/stream" in it } >= 1 && !standIn.cutOnce.contains("s1") }
        timed(test, "resumed") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.retryFailed() }
            until("downloaded whole after the cut: ${downloads.state.value.failed}") { account().catalogue.has("s1") }
        }
        assertTrue(standIn.asked.count { "/rest/stream" in it } >= 2)
    }

    @Test
    fun theJobBeforeTheAccountIsPublishedCarriesDownloadsOn() {
        val (graph, box) = processStartedAgain()
        val test = "downloads_start_and_resume"
        timed(test, "job_before_publication") {
            val job = Robolectric.buildService(DownloadsJob::class.java).create().get()
            services += job
            assertTrue(job.onStartJob(jobParameters()))
            assertNull(graph.accounts.state.value)
            box.open.countDown()
            // Nothing is waiting: the job looks, and ends.
            until("the job ended") { shadowOf(job).isJobFinished }
        }
        afterPublication(graph)
    }

    /** What Android hands a job: built by its own constructor, as the scheduler does. */
    private fun jobParameters(): android.app.job.JobParameters {
        val made = android.app.job.JobParameters::class.java.declaredConstructors.maxBy { it.parameterCount }.apply { isAccessible = true }
        var ints = 0
        val args: List<Any?> = made.parameterTypes.map { type ->
            when (type) {
                Int::class.javaPrimitiveType -> if (ints++ == 0) DownloadKeeper.JOB_ID else 0
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return made.newInstance(*args.toTypedArray()) as android.app.job.JobParameters
    }

    @Test
    fun offlineByHandAndByLoss() {
        savedQueue()
        val test = "offline_by_hand_and_by_loss"
        val service = service(Foreground())
        val inside = service.inside()
        until("settled") { settled(service) }
        timed(test, "by_hand") {
            app.graph.settings.update { it.copy(offline = true) }
            until("offline") { app.graph.watch.state.value.offline }
            assertEquals(SessionResult.RESULT_SUCCESS, answer(inside.callback.onGetChildren(inside.session, car, "downloads", 0, Int.MAX_VALUE, null)).resultCode)
            answer(inside.callback.onGetChildren(inside.session, car, "home", 0, Int.MAX_VALUE, null))
        }
        timed(test, "back") {
            app.graph.settings.update { it.copy(offline = false) }
            until("online") { !app.graph.watch.state.value.offline }
        }
        timed(test, "by_loss") {
            app.graph.watch.noNetwork()
            until("offline") { app.graph.watch.state.value.offline }
            answer(inside.callback.onGetChildren(inside.session, car, "home", 0, Int.MAX_VALUE, null))
            inside.player.play()
            until("told to play") { inside.exo.playWhenReady }
        }
    }

    @Test
    fun aLikeAndAPlayReachTheMusicServer() {
        signedIn()
        val test = "a_like_and_a_play_reach_the_music_server"
        timed(test, "like") {
            account().likes.set(songs()[2], true)
            until("the like reached the music server") { standIn.asked.any { "/rest/star" in it } }
        }
        timed(test, "play") {
            account().played("s3", System.currentTimeMillis(), "", report = true)
            until("the play reached the music server") { standIn.asked.any { "/rest/scrobble" in it } }
        }
    }

    /** A play the music server could not be told of is told again once it answers. */
    @Test
    fun aPlayNotToldIsToldAgain() {
        signedIn()
        val test = "a_like_and_a_play_reach_the_music_server"
        standIn.failing += "scrobble"
        account().played("s3", System.currentTimeMillis(), "", report = true)
        until("the play held, the music server not told") { standIn.asked.any { "/rest/scrobble" in it } && account().held.waiting.value > 0 }
        val asked = standIn.asked.count { "/rest/scrobble" in it }
        standIn.failing -= "scrobble"
        timed(test, "play_again") {
            var next = 0L
            until("told again") {
                // (Each half second: the watch asks whether the server is back, and a pass over
                // what is held is made, as when it is.)
                if (System.nanoTime() > next) {
                    next = System.nanoTime() + 500_000_000
                    app.graph.checkIfDown()
                    app.graph.scope.launch { account().flush() }
                }
                standIn.asked.count { "/rest/scrobble" in it } > asked && account().held.waiting.value == 0
            }
        }
    }

    @Test
    fun aPlayIsCountedAsReleased() {
        signedIn()
        val test = "a_play_is_counted_as_released"
        val log = File(account().account.dirs.data, "plays.log")
        timed(test, "counted") {
            runBlocking { account().playLog.flushed() }
            account().played("s1", 1_000_000_000L, "playlist:p", report = false)
            account().played("s2", 1_000_240_000L, "", report = false)
            runBlocking { account().playLog.flushed() }
        }
        assertEquals("[\"s1\",1000000,\"playlist:p\"]\n[\"s2\",1000240]\n", log.readText())
    }

    @Test
    fun anAccountChangeIsNotHeldUp() {
        signedIn()
        // The launcher held too, so the old client is not let go of while the step runs: the new
        // account is published with all of the old one's client stuck.
        holds.holdTheLauncher(app.graph)
        val test = "an_account_change_is_not_held_up"
        timed(test, "next_account") {
            app.graph.session.signOut()
            standIn.signIn(app, username = "another")
            assertEquals("another", account().account.username)
            assertTrue(app.graph.session.state.value is SessionState.SignedIn)
        }
        // Then let go: the old client is let go of, and its threads end.
        holds.oldClientLetGo()
    }

    /** The timings are an alarm a dependency of 400 ms trips: the car's folders, each time with
     *  400 ms more, as three cases of a run would have them, are over the run's limit. */
    @Test
    fun aWaitOfFourHundredMillisecondsInAnEntryPointIsCaught() {
        assumeTrue(Times.released != null && broken == Broken.STALLED)
        savedQueue()
        val service = service(Foreground())
        val inside = service.inside()
        until("settled") { settled(service) }
        val slowed = List(3) {
            val began = System.nanoTime()
            answer(inside.callback.onGetLibraryRoot(inside.session, car, null))
            for (id in listOf("root", "home", "playlists", "library", "downloads", "album/al-1")) answer(inside.callback.onGetChildren(inside.session, car, id, 0, Int.MAX_VALUE, null))
            Thread.sleep(400)
            (System.nanoTime() - began) / 1e6
        }
        assertTrue("a step 400 ms slower in every case was not caught: $slowed", Times.overRun("the_car_starts_the_service_cold", "children", slowed))
    }
}
