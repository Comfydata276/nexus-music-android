package org.medianexus.music.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.session.MediaSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.library.Arrange
import org.medianexus.music.library.ListView
import org.medianexus.music.library.columns
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.ui.lists.songsShown
import org.medianexus.music.ui.lists.songsTap
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What plays is what was on the screen: a tap goes through what the screen really sends
 * (the Songs page's tap, a long playlist's), to the playback service itself, which is held at
 * a gate between the tap and the list it starts; while it is held, the list the screen drew
 * from is replaced by one in which the tapped song has moved and its neighbours are others.
 * Opened, the queue is the rows that were drawn, in the order drawn, from the song tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class DrawnPlaysTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = StandInServer()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val gate = CompletableDeferred<Unit>()
    private val held = CompletableDeferred<Unit>()
    private val said = CopyOnWriteArrayList<String>()
    private val listening = CoroutineScope(Dispatchers.Unconfined)

    /** The app's own screens, as the session sees them. */
    private val screens by lazy {
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            app.packageName, android.os.Process.myPid(), android.os.Process.myUid(), MediaLibraryInfo.VERSION_INT, 0, false, Bundle.EMPTY, true,
        )
    }

    /** The first copy of the library: thirty songs, ten with "amber" in the title, one a day. */
    private val first = List(30) { n -> song("s$n", if (n % 3 == 0) "Amber ${'a' + n}" else "Grey ${'a' + n}", day = n) }

    /** The second: the songs that were around the one tapped are gone, others match "amber",
     *  and the one tapped has a new date and title, so it moves in both orders. */
    private fun second(tapped: String) = first.filter { it.id == tapped || it.title.startsWith("Grey") }.map {
        if (it.id == tapped) it.copy(title = "Amber zz", created = date(40)) else it
    } + List(6) { n -> song("t$n", "Amber ${'b' + n}x", day = 50 + n) }

    private fun date(day: Int) = "${java.time.LocalDate.of(2026, 1, 1).plusDays(day.toLong())}T10:00:00Z"

    private fun song(id: String, title: String, day: Int) = Song(id = id, title = title, artist = "Someone", album = "Somewhere", albumId = "al", created = date(day), duration = 200)

    private fun json(songs: List<Song>) = songs.joinToString(",", "[", "]") {
        """{"id":"${it.id}","title":"${it.title}","artist":"${it.artist}","album":"${it.album}","albumId":"${it.albumId}","created":"${it.created}","duration":${it.duration}}"""
    }

    private fun serve(songs: List<Song>, scan: String) {
        server.answers["getScanStatus"] = """"scanStatus":{"scanning":false,"count":${songs.size},"lastScan":"$scan"}"""
        server.answers["search3"] = """"searchResult3":{"song":${json(songs)}}"""
    }

    private val account get() = app.graph.accounts.state.value!!

    @Before
    fun start() {
        serve(first, "2026-03-01T00:00:00Z")
        server.start()
        server.signIn(app)
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        inside = service.inside()
        // The account taken on, its saved queue read (there is none): other threads' work,
        // waited for before the part being tested.
        until("the service took the account on") { inside.session.sessionExtras.getBoolean(PlaybackService.EXTRA_SETTLED) }
        runBlocking { account.copy.check() }
        until("the copy arrived") { account.copy.state.value?.complete == true && account.copy.state.value?.songs?.size == first.size }
        // What was playing before the tap.
        inside.player.play(List(3) { Song(id = "before$it", title = "Before $it") }, 0, "Before", playNow = false)
        settle()
        service.beforeNamedStart = {
            held.complete(Unit)
            gate.await()
        }
        listening.launch { app.graph.toasts.shown.collect { said += it } }
    }

    @After
    fun stop() {
        listening.cancel()
        service.onDestroy()
        settle()
        server.stop()
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

    /** The tap reaches the service, which is then held at the gate. */
    private fun tap(args: Bundle) {
        inside.callback.onCustomCommand(inside.session, screens, AppCommands.PLAY, args)
        until("the named start reached its gate") { held.isCompleted }
    }

    private fun queue() = inside.player.queue.playingOrder().map { it.id }

    @Test
    fun aTapOnAFoundSongsListPlaysTheRowsDrawnWhateverTheCopyBecomes() {
        val copy = account.copy.state.value!!
        val shown = songsShown(copy, ListView(ListView.TITLE, filter = "amber"))
        val drawn = shown.order.map { copy.songs.id(it) }
        val row = 4
        val tapped = drawn[row]
        tap(songsTap(shown, row, any = false, context = "Songs")!!)
        // Held: the library changes under it (a refresh, or the first fetch going on).
        serve(second(tapped), "2026-03-02T00:00:00Z")
        assertTrue(runBlocking { account.copy.check() })
        val now = account.copy.state.value!!
        assertNotEquals(drawn, songsShown(now, ListView(ListView.TITLE, filter = "amber")).order.map { now.songs.id(it) })
        gate.complete(Unit)
        until("the tap played") { inside.player.queue.context == "Songs" }
        assertEquals(tapped, inside.player.queue.current()?.id)
        assertEquals(drawn, queue())
        assertEquals(row, inside.player.queue.cursor)
    }

    @Test
    fun thePlayButtonOfTheSongsPagePlaysItsTopRowAsDrawn() {
        val copy = account.copy.state.value!!
        val shown = songsShown(copy, ListView(ListView.TITLE, descending = true, filter = "amber"))
        val drawn = shown.order.map { copy.songs.id(it) }
        tap(songsTap(shown, 0, any = true, context = "Songs")!!)
        serve(second(drawn[0]), "2026-03-02T00:00:00Z")
        assertTrue(runBlocking { account.copy.check() })
        gate.complete(Unit)
        until("the play button played") { inside.player.queue.context == "Songs" }
        assertEquals(drawn[0], inside.player.queue.current()?.id)
        assertEquals(drawn, queue())
    }

    /** Offline, with every song of the copy downloaded. */
    private fun offlineWithAllDownloaded() {
        val copy = account.copy.state.value!!
        for (n in 0 until copy.songs.size) {
            val song = copy.songs.song(n)
            account.catalogue.add(org.medianexus.music.downloads.Downloaded(song, "${song.id}.flac", 4, at = 1, suffix = "flac"))
        }
        app.graph.watch.manual(true)
        settle()
    }

    @Test
    fun offlineATappedSongThatCanNoLongerPlayStartsNothingNotItsNeighbour() {
        offlineWithAllDownloaded()
        val copy = account.copy.state.value!!
        val shown = songsShown(copy, ListView(ListView.TITLE, filter = "amber"))
        val row = 4
        val tapped = copy.songs.id(shown.order[row])
        tap(songsTap(shown, row, any = false, context = "Songs")!!)
        // Held: the song tapped stops being on the phone (its download removed).
        account.catalogue.remove(setOf(tapped))
        gate.complete(Unit)
        until("the person was told") { said.isNotEmpty() }
        // Nothing started (it used to start the next row's song): what was there stays.
        assertEquals(listOf(app.getString(org.medianexus.music.R.string.cant_play_offline)), said)
        assertEquals("Before", inside.player.queue.context)
        assertEquals("before0", inside.player.queue.current()?.id)
    }

    @Test
    fun offlineThePlayButtonStillPassesOverWhatCannotPlay() {
        offlineWithAllDownloaded()
        val copy = account.copy.state.value!!
        val shown = songsShown(copy, ListView(ListView.TITLE, filter = "amber"))
        val drawn = shown.order.map { copy.songs.id(it) }
        tap(songsTap(shown, 0, any = true, context = "Songs")!!)
        account.catalogue.remove(setOf(drawn[0]))
        gate.complete(Unit)
        until("the play button played") { inside.player.queue.context == "Songs" }
        // It asks for the list, not a song: from the first of it that plays.
        assertEquals(drawn.drop(1), queue())
    }

    @Test
    fun aTapOnALongPlaylistPlaysTheRowsDrawnWhateverItsPageBecomes() {
        val library = account.library
        val songs = List(260) { n -> song("p$n", if (n % 4 == 0) "Amber $n" else "Grey $n", day = n % 50) }
        runBlocking { library.pages.put(library.playlist("p"), Playlist(id = "p", name = "Mix", songs = songs)) }
        // As the playlist's page arranges it, and as its row sends the tap (SongListPage).
        val view = ListView(ListView.TITLE, filter = "amber")
        val order = Arrange.arrange(songs.columns(), view)
        val drawn = order.map { songs[it].id }
        val row = 7
        val tapped = drawn[row]
        tap(PlayerConnection.playArgs(songs, order[row], "Mix", "playlist:p", view = view, shown = order))
        // Held: the playlist is refreshed. The songs around the one tapped have gone, it has
        // moved, and others match.
        val changed = songs.filter { it.id == tapped || it.title.startsWith("Grey") }.map { if (it.id == tapped) it.copy(title = "Amber zz") else it } +
            List(30) { n -> song("q$n", "Amber q$n", day = 1) }
        runBlocking { library.pages.put(library.playlist("p"), Playlist(id = "p", name = "Mix", songs = changed)) }
        gate.complete(Unit)
        until("the tap played") { inside.player.queue.context == "Mix" }
        assertEquals(tapped, inside.player.queue.current()?.id)
        assertEquals(drawn, queue())
    }
}
