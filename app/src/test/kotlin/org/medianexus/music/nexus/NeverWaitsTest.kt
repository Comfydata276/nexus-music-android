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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 * its real entry points (the saved sign-in, the playback service, its buttons and the car's
 * calls, the downloads' job and service, offline mode, a counted play, a change of account)
 * while everything under `/nexus/` at the music server's origin is broken in each of the
 * design's ways ([Broken]). The music server itself, at the same origin, answers as ever.
 *
 * Each test times what it does (see [Times]): run first on the app as released, to fix the
 * numbers (experiment E13a), and then on every build after, held to them.
 *
 * Nothing here waits for an event by the clock: the main thread moves only when a test lets
 * it ([settle]), and a test waits for another thread's work by letting the main thread run
 * until that work is done, with the main thread's clock standing still. So a time limit of
 * the app's (the service's seven seconds for a start that comes to nothing, the server
 * watch's ask) never runs out inside one of these, and what is timed is work.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = App::class)
class NeverWaitsTest(private val broken: Broken) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Broken.entries.map { arrayOf(it) }

        /** Before the app's objects are made for the first test: their HTTP client must trust
         *  the stand-in's certificate. */
        @JvmStatic
        @BeforeClass
        fun trust() = BackendStandIn.trust()

        @JvmStatic
        @AfterClass
        fun untrust() = BackendStandIn.untrust()

        /** Android's limit for a service started in the foreground to say so (`startForeground`
         *  within five seconds of `startForegroundService`, or the app is killed). */
        const val ANDROID_FOREGROUND_LIMIT_MILLIS = 5_000.0
    }

    /** The audio decoders every phone has, as Robolectric stands in for the system's list: a
     *  download is checked against them before it counts as there. */
    @get:org.junit.Rule val decoders = org.medianexus.music.downloads.phoneDecoders()

    private val app = RuntimeEnvironment.getApplication()
    private val standIn = BackendStandIn(broken)
    private val graphs = ArrayList<Graph>()
    private val services = ArrayList<android.app.Service>()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
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

    /** What [Broken.WORKERS_HELD] holds the backend's client's threads with: work that does
     *  not end while the test runs (and ends, interrupted, when the client is let go of). */
    private val holding = java.util.concurrent.CountDownLatch(1)

    /** How many of the backend's clients have been put in this case's state so far: each holds
     *  one request at the stand-in, in the cases where it is held. */
    private var broke = 0
    private val seen = HashSet<NexusClient>()

    /** Where the backend's client would keep its files; the DISK cases leave it unusable. */
    private val nexusFiles get() = File(app.filesDir, "nexus")

    @Before
    fun start() {
        DownloadKeeper.visible = false
        when (broken) {
            Broken.DISK_BLOCKED -> check(ProcessBuilder("mkfifo", nexusFiles.path).start().waitFor() == 0) { "no pipe made" }
            Broken.DISK_FULL -> nexusFiles.writeBytes(ByteArray(0))
            else -> {}
        }
        standIn.start()
        runBlocking { app.graph.session.restored() }
    }

    private fun hold(workers: List<java.util.concurrent.ExecutorService>) {
        for (worker in workers) {
            runCatching { worker.execute { try { holding.await() } catch (_: InterruptedException) {} } }
        }
    }

    /** The modes in which the stand-in takes the backend's `hello` and never answers it. */
    private val holdsHello get() = broken != Broken.ABSENT && broken != Broken.SESSION_ENDED

    /**
     * The backend's client of the account `graph` has signed in now, once it is in the state
     * this case puts it in: its `hello` held at the stand-in (or, where the stand-in answers,
     * answered); and with [Broken.WORKERS_HELD] every thread of its own held too. What a test
     * does after this, it does with the backend's client stuck.
     */
    private fun componentBroken(graph: Graph = app.graph): NexusClient {
        var made: NexusClient? = null
        until("the backend's client is made for the account") { graph.nexus.current?.takeIf { it !in seen }?.also { made = it } != null }
        val client = made!!
        seen += client
        broke++
        if (holdsHello) {
            until("the backend's client is held at the stand-in") { standIn.nexusHeld >= broke }
        } else {
            until("the backend's client has had its answer") { client.asked.get() > 0 }
        }
        if (broken == Broken.WORKERS_HELD) hold(client.workers + graph.nexus.workers)
        return client
    }

    @After
    fun stop() {
        // What each test did, it did with the backend's client stuck: its requests are held
        // still, nothing was found, and where it would keep its files is as the case left it.
        if (holdsHello) assertTrue("the stand-in let a hello go", standIn.nexusHeld >= broke)
        assertEquals(NexusFound.None, app.graph.nexus.found.value)
        when (broken) {
            Broken.DISK_BLOCKED -> assertTrue(java.nio.file.Files.getAttribute(nexusFiles.toPath(), "unix:mode").let { (it as Int) and 0xF000 == 0x1000 })
            Broken.DISK_FULL -> assertTrue(nexusFiles.isFile && nexusFiles.length() == 0L)
            else -> {}
        }
        holding.countDown()
        for (service in services.asReversed()) runCatching { service.onDestroy() }
        settle()
        graphs.forEach { it.scope.cancel() }
        (graphs + app.graph).forEach { it.http.connectionPool.evictAll() }
        // **The music server's own connections were never the backend's**: no connection
        // carried both a request of the music server's and one under /nexus/.
        assertTrue(
            "a connection carried both the music server's requests and the backend's",
            standIn.musicConnections.intersect(standIn.nexusConnections).isEmpty(),
        )
        assertTrue("the app's own client has calls waiting", app.graph.http.dispatcher.queuedCallsCount() == 0)
        standIn.stop()
    }

    // ---- How the tests are run ---------------------------------------------------------------

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the main thread run until `done`, with its clock standing still (see the class's
     *  comment). Fails after thirty seconds of the wall clock. */
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

    /** Times `block` as `step` of `test`, records it, and holds it to the released app's. */
    private fun <T> timed(test: String, step: String, block: () -> T): T {
        val began = System.nanoTime()
        val result = block()
        val millis = (System.nanoTime() - began) / 1e6
        Times.record(test, broken.name, step, millis)
        Times.held(test, step)?.let { limit ->
            assertTrue("$test, $step: ${"%.0f".format(millis)} ms, held to three times the released app's slowest and half a second more: ${"%.0f".format(limit)}", millis <= limit)
        }
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

    /** Signs in, and has the playback service keep a queue of the album's songs, playing
     *  from the second; then lets the service go, as Android does when it wants the memory. */
    private fun savedQueue() {
        standIn.signIn(app)
        componentBroken()
        val first = service(Foreground())
        until("the first service has read what was saved") { settled(first) }
        first.inside().player.play(songs(), 1, "First Light", source = "album:al-1", playNow = false)
        until("the queue is on the disk") { queueFile().isFile && "\"s3\"" in queueFile().readText() }
        first.onDestroy()
        services -= first
        settle()
    }

    private fun playButton() = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(app, PlaybackService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))

    // ---- The tests ------------------------------------------------------------------------------

    @Test
    fun coldStartPublishesTheSavedAccount() {
        // Someone signed in, with the password saved; the app is ended.
        val before = Graph(app, PlainBox).also { graphs += it }
        before.session.signIn(standIn.address, "tester", "a password")
        runBlocking { withTimeout(30_000) { before.accounts.state.first { it != null } } }
        componentBroken(before)
        runBlocking { before.settings.flush() }
        before.scope.cancel()
        // The app starts: its objects are made, and the saved sign-in is read and published.
        var graph: Graph? = null
        val restored = timed("cold_start_publishes_the_saved_account", "restored") {
            val made = Graph(app, PlainBox).also { graphs += it; graph = it }
            // With every thread of the backend's client held from the moment the app's objects
            // exist, before anything is read: the account is published all the same.
            if (broken == Broken.WORKERS_HELD) hold(made.nexus.workers)
            runBlocking { withTimeout(30_000) { made.accounts.restored() } }
        }
        assertNotNull("the saved account was not published", restored)
        assertEquals("tester", restored!!.account.username)
        if (broken == Broken.WORKERS_HELD) {
            // (Its launcher held, the backend's client was never made: nothing waited for it.)
            assertEquals(null, graph!!.nexus.current)
        } else {
            // Made afterwards, for the account published, and stuck.
            componentBroken(graph!!)
        }
    }

    @Test
    fun coldStartPutsTheQueueBackAndPlays() {
        savedQueue()
        val android = Foreground()
        timed("cold_start_puts_the_queue_back_and_plays", "restore") {
            val service = service(android)
            until("the saved queue is the player's") { settled(service) }
        }
        val inside = services.last().let { (it as PlaybackService).inside() }
        assertEquals("s2", inside.player.queue.current()?.id)
        timed("cold_start_puts_the_queue_back_and_plays", "play") {
            inside.player.play()
            until("playing, in the foreground") { inside.exo.playWhenReady && android.inIt }
        }
    }

    @Test
    fun aDirectPlayStartsItCold() {
        savedQueue()
        // (The receiver only looks for a saved password's file: this stand-in phone has no
        // Keystore to seal one with, so one is put there.)
        Graph.passwordFile(app).let { if (!it.isFile || it.length() == 0L) { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(16)) } }
        // The button reaches the app's receiver (Media3's, with the app's own say on whether
        // there is anything to resume), which starts the service.
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

    @Test
    fun theCarStartsTheServiceCold() {
        savedQueue()
        val android = Foreground()
        val service = service(android)
        val inside = service.inside()
        val test = "the_car_starts_the_service_cold"
        // A song chosen in the car: in the foreground before anything is looked up.
        val asked = System.nanoTime()
        val chosen = inside.callback.onSetMediaItems(inside.session, car, listOf(MediaItem.Builder().setMediaId("track/0/s1/album/al-1").build()), C.INDEX_UNSET, C.TIME_UNSET)
        assertTrue("not in the foreground when the car's choice arrived", android.inIt)
        val foreground = (android.enteredAt - asked) / 1e6
        Times.record(test, broken.name, "foreground", foreground)
        assertTrue("in the foreground after ${"%.0f".format(foreground)} ms", foreground < ANDROID_FOREGROUND_LIMIT_MILLIS)
        timed(test, "chosen") { answer(chosen) }
        timed(test, "children") {
            answer(inside.callback.onGetLibraryRoot(inside.session, car, null))
            for (id in listOf("root", "home", "playlists", "library", "downloads", "album/al-1")) {
                assertEquals(id, SessionResult.RESULT_SUCCESS, answer(inside.callback.onGetChildren(inside.session, car, id, 0, Int.MAX_VALUE, null)).resultCode)
            }
        }
        timed(test, "search") {
            answer(inside.callback.onSearch(inside.session, car, "lantern", null))
            answer(inside.callback.onGetSearchResult(inside.session, car, "lantern", 0, 50, null))
        }
        timed(test, "voice") {
            val words = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("first light").build()).build()
            answer(inside.callback.onSetMediaItems(inside.session, car, listOf(words), C.INDEX_UNSET, C.TIME_UNSET))
        }
        timed(test, "add") {
            answer(inside.callback.onAddMediaItems(inside.session, car, listOf(MediaItem.Builder().setMediaId("track/2/s3/album/al-1").build())))
        }
        timed(test, "resumption_card") {
            answer(inside.callback.onPlaybackResumption(inside.session, car, false))
        }
    }

    @Test
    fun theNotificationsButtonsWork() {
        standIn.signIn(app)
        componentBroken()
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
            until("liked") { account().likes.isLiked("s1") }
        }
        timed(test, "shuffle_and_repeat") {
            val systemUi = inside.session.mediaNotificationControllerInfo!!
            assertEquals(SessionResult.RESULT_SUCCESS, answer(inside.callback.onCustomCommand(inside.session, systemUi, PlaybackService.SHUFFLE, Bundle.EMPTY)).resultCode)
            assertTrue(inside.player.queue.shuffle)
            assertEquals(SessionResult.RESULT_SUCCESS, answer(inside.callback.onCustomCommand(inside.session, systemUi, PlaybackService.REPEAT, Bundle.EMPTY)).resultCode)
        }
        until("the like reached the music server") { standIn.asked.any { "/rest/star" in it } }
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
            // Added next, and at the end; the one at the end moved to straight after the song
            // playing; the last of the album's taken out.
            player.enqueue(listOf(songs()[0]), playNext = true)
            player.enqueue(listOf(songs()[2]), playNext = false)
            settle()
            val uids = player.queue.playingUids()
            player.moveEntry(uids.last(), after = null, queued = true)
            settle()
            player.removeEntry(player.queue.playingUids()[player.queue.playingUids().size - 2])
            settle()
            val now = player.queue.playingOrder().map { it.id }
            until("the change is on the disk: $now") { saved() == now }
        }
        val kept = inside.player.queue.playingOrder().map { it.id }
        service.onDestroy()
        services -= service
        settle()
        timed(test, "kept") {
            val again = service(Foreground())
            until("the changed queue is back") { settled(again) }
            assertEquals(kept, again.inside().player.queue.playingOrder().map { it.id })
        }
        timed(test, "cleared") {
            val again = services.last() as PlaybackService
            again.inside().player.clear()
            until("nothing is kept") { saved().isNullOrEmpty() }
        }
    }

    @Test
    fun downloadsStartAndResume() {
        standIn.signIn(app)
        componentBroken()
        val test = "downloads_start_and_resume"
        val downloads = account().downloads!!
        // (Robolectric's phone is on mobile data; whether downloads wait for Wi-Fi is not what
        // this is about.)
        app.graph.settings.update { it.copy(downloadWifiOnly = false) }
        // Asked for by hand.
        timed(test, "by_hand") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[0])) }
            until("downloaded") { account().catalogue.has("s1") }
        }
        // The scheduled job, alone, with no screen: something is waiting for it.
        timed(test, "job") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[1])) }
            val job = Robolectric.buildService(DownloadsJob::class.java).create().get()
            services += job
            assertTrue(job.onStartJob(jobParameters()))
            until("the job carried the download to its end") { account().catalogue.has("s2") && shadowOf(job).isJobFinished }
        }
        // The foreground service (Android 12 and 13's way): in the foreground at once, and
        // stopped when nothing is left.
        timed(test, "service") {
            app.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) { downloads.download(listOf(songs()[2])) }
            val held = Robolectric.buildService(DownloadsService::class.java).create().get()
            services += held
            held.onStartCommand(Intent(app, DownloadsService::class.java), 0, 1)
            assertEquals(DownloadKeeper.NOTIFICATION_ID, shadowOf(held).lastForegroundNotificationId)
            until("downloaded, and the service stopped") { account().catalogue.has("s3") && shadowOf(held).isStoppedBySelf }
        }
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
        standIn.signIn(app)
        componentBroken()
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

    @Test
    fun aPlayIsCountedAsReleased() {
        standIn.signIn(app)
        componentBroken()
        val test = "a_play_is_counted_as_released"
        val log = File(account().account.dirs.data, "plays.log")
        timed(test, "counted") {
            runBlocking { account().playLog.flushed() }
            account().played("s1", 1_000_000_000L, "playlist:p", report = false)
            account().played("s2", 1_000_240_000L, "", report = false)
            runBlocking { account().playLog.flushed() }
        }
        // The log's bytes are the released app's for the same plays.
        assertEquals("[\"s1\",1000000,\"playlist:p\"]\n[\"s2\",1000240]\n", log.readText())
    }

    @Test
    fun anAccountChangeIsNotHeldUp() {
        standIn.signIn(app)
        val old = componentBroken()
        val test = "an_account_change_is_not_held_up"
        timed(test, "next_account") {
            app.graph.session.signOut()
            standIn.signIn(app, username = "another")
        }
        assertEquals("another", account().account.username)
        assertTrue(app.graph.session.state.value is SessionState.SignedIn)
        // The old account's client is let go of (once its launcher can run: with every thread
        // of its own held, the account was published without it), and stops by itself: every
        // thread of its own ends. What it still brings back is not shown.
        if (broken == Broken.WORKERS_HELD) holding.countDown()
        until("the old account's client is let go of") { old.isStopped }
        for (worker in old.workers) assertTrue("a thread of the old client's is still running", worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        // The new account's is made, and is stuck as the old one was.
        if (broken != Broken.WORKERS_HELD) assertTrue(componentBroken() !== old)
    }
}
