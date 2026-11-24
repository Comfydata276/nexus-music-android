package org.medianexus.music.sync

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.library.Library
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.PlayQueue
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Added
import org.medianexus.music.store.AddedNote
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.RecentlyPlayed

/**
 * The stores the shared record is made from and taken into: when a time may be put on a note
 * of when a song was added, Recently played taking in another device's, the rules for the
 * shared queue, and the sync playlist being nowhere among the playlists.
 */
class SharedStoresTest {
    /** No test here may wait for ever: one that did would hold the machine's one turn at
     *  building, for everyone, until someone noticed. */
    @get:org.junit.Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun songs(vararg ids: String) = ids.map { Song(id = it) }

    private fun added(name: String = "added.json") = Added(File(folder.root, name), null, Dispatchers.IO)

    // ---- when songs were added: rule 1 ------------------------------------------------------------

    @Test
    fun whatThePhoneOnlyNoticesInAPlaylistIsNeverPutOnRecord() {
        val added = added()
        var told = 0
        added.changedHere = { told++ }
        // A first look, then a song that turns up later (added on a PC, or in another app).
        added.sawCopy("p", songs("a", "b"), 100)
        added.sawCopy("p", songs("a", "b", "c", "a"), 200)
        // The phone dates it for its own "Date added"...
        assertEquals(listOf(0L, 0L, 200L, 200L), added.dates("p", songs("a", "b", "c", "a")).toList())
        // ...and has nothing to say of it to other devices: Windows would share "c, added at
        // 200" here, and every PC would take the phone's late look for the day it was added.
        assertTrue(added.shared().isEmpty())
        assertEquals(0, told)
        // The PC that added it says when: that is taken, over what was noticed.
        assertTrue(added.merge(mapOf("p" to mapOf("c" to AddedNote(listOf(150), 150)))))
        assertEquals(listOf(0L, 0L, 150L, 200L), added.dates("p", songs("a", "b", "c", "a")).toList())
        // A song noticed gone: a note of the phone's own goes with it; another device's dated
        // note is left exactly as it is (the phone's copy may only be an old one).
        added.sawCopy("p", songs("b"), 300)
        assertEquals(mapOf("p" to mapOf("c" to AddedNote(listOf(150), 150))), added.shared())
        assertEquals(0, told)
        // Back again, noticed: the dated note still stands, unaltered.
        added.sawCopy("p", songs("b", "c", "c"), 400)
        assertEquals(mapOf("p" to mapOf("c" to AddedNote(listOf(150), 150))), added.shared())
    }

    @Test
    fun whatThePersonChangesHereIsDatedNow() {
        val added = added()
        var told = 0
        added.changedHere = { told++ }
        added.sawCopy("p", songs("a", "b"), 100)
        // Added on this phone: dated, and worth telling.
        added.changed("p", listOf("a", "b"), listOf("a", "b", "c", "a"), 200)
        assertEquals(mapOf("p" to mapOf("c" to AddedNote(listOf(200), 200), "a" to AddedNote(listOf(0, 200), 200))), added.shared())
        assertEquals(1, told)
        // Taken out on this phone: the note stays, emptied, so other devices hear that it went;
        // one of two copies gone keeps the older date. A song nothing was known of leaves no note.
        added.changed("p", listOf("a", "b", "c", "a"), listOf("a"), 300)
        assertEquals(mapOf("p" to mapOf("c" to AddedNote(emptyList(), 300), "a" to AddedNote(listOf(0), 300))), added.shared())
        assertEquals(2, told)
        // A song the change didn't touch is not looked at, whatever its note says.
        added.merge(mapOf("p" to mapOf("z" to AddedNote(listOf(50), 50))))
        added.changed("p", listOf("a"), listOf("a", "d"), 400)
        assertEquals(AddedNote(listOf(50), 50), added.shared().getValue("p")["z"])
        assertEquals(AddedNote(listOf(400), 400), added.shared().getValue("p")["d"])
        // Put back after being taken out elsewhere (its dated note was left as it was, out of
        // step): dated afresh, not with the day it was first added.
        added.merge(mapOf("p" to mapOf("old" to AddedNote(listOf(10), 10))))
        added.changed("p", listOf("a", "d"), listOf("a", "d", "old"), 500)
        assertEquals(AddedNote(listOf(500), 500), added.shared().getValue("p")["old"])
        // A change that changes no song (a move) says nothing.
        added.changed("p", listOf("a", "d", "old"), listOf("old", "d", "a"), 600)
        assertEquals(3 + 1, told)
    }

    // ---- Recently played ----------------------------------------------------------------------------

    @Test
    fun recentlyPlayedTakesInAnotherDevicesInOrder() = runBlocking {
        var clock = 1_000L
        val history = RecentlyPlayed(File(folder.root, "history.json"), scope, Dispatchers.Unconfined) { clock }
        var told = 0
        history.changedHere = { told++ }
        // Before the phone's own list has been read, nothing is merged into it.
        assertFalse(history.take(listOf(Play("x", 5))) { emptyMap() })
        history.open()
        history.played(Song(id = "a", title = "A"))
        clock = 3_000
        history.played(Song(id = "c", title = "C"))
        assertEquals(2, told)
        val asked = ArrayList<String>()
        val theirs = listOf(Play("d", 4_000), Play("b", 2_000), Play("a", 1_000))
        assertTrue(history.take(theirs) { ids -> asked += ids; ids.associateWith { Song(id = it, title = it.uppercase()) } })
        assertEquals(listOf("d" to 4_000L, "c" to 3_000L, "b" to 2_000L, "a" to 1_000L), history.state.value.map { it.song.id to it.at })
        // Only the songs this phone hasn't met are asked for; its own keep what it knew.
        assertEquals(listOf("d", "b"), asked)
        assertEquals("C", history.state.value[1].song.title)
        // The same again changes nothing, and taking in is not a change made here.
        assertFalse(history.take(theirs) { error("nothing to ask") })
        assertEquals(2, told)
        // It survives a restart.
        assertEquals(listOf("d", "c", "b", "a"), RecentlyPlayed.load(File(folder.root, "history.json")).map { it.song.id })
    }

    // ---- the queue -----------------------------------------------------------------------------------

    private fun queue(vararg ids: String, current: String, position: Long = 0, changed: String, by: String = "NexusMusic-0badc0de") =
        PlayQueue(ids.map { Song(id = it, title = "T$it", artist = "Someone", duration = 200) }, current, position, changed, by)

    @Test
    fun aLongQueueIsPassedOnAsFiftyBehindAndFourHundredAndFiftyAhead() {
        val songs = (0 until 2_000).map { Song(id = "s$it", radioUrl = if (it == 1_010) "http://radio.example/stream" else "") }
        val upload = QueueShare.window(songs.size, 1_000, { songs[it] }, 12.6)!!
        assertEquals("s1000" to 12_600L, upload.current to upload.positionMs)
        // (A station among them isn't something another device could pick up by id.)
        assertEquals(499, upload.ids.size)
        assertEquals("s950" to "s1449", upload.ids.first() to upload.ids.last())
        assertFalse("s1010" in upload.ids)
        // Near the start, and a short queue.
        assertEquals(455, QueueShare.window(songs.size, 5, { songs[it] }, 0.0)!!.ids.size)
        assertEquals(listOf("s0", "s1", "s2"), QueueShare.window(3, 1, { songs[it] }, -4.0)!!.let { assertEquals(0L, it.positionMs); it.ids })
        // Nothing playing, or a station: nothing is saved.
        assertNull(QueueShare.window(0, 0, { songs[it] }, 0.0))
        assertNull(QueueShare.window(songs.size, 1_010, { songs[it] }, 0.0))
    }

    @Test
    fun aQueueFromAnotherDeviceIsTakenUpOfferedOrLeftAsWindowsDoes() {
        val mine = "NexusMusic-11112222"
        val now = 86_400L + 100
        val theirs = queue("a", "b", "c", current = "b", position = 30_000, changed = "1970-01-02T00:00:00Z")
        assertEquals(86_400L, QueueShare.changedAt(theirs))
        // Taken up, paused, only if another device changed it after anything was done here,
        // and nothing is playing here.
        assertTrue(QueueShare.adopted(theirs, mine, stamp = 86_399, playing = false))
        assertFalse(QueueShare.adopted(theirs, mine, stamp = 86_400, playing = false))
        assertFalse(QueueShare.adopted(theirs, mine, stamp = 0, playing = true))
        assertFalse(QueueShare.adopted(theirs.copy(changedBy = mine), mine, stamp = 0, playing = false))
        assertFalse(QueueShare.adopted(theirs.copy(songs = emptyList()), mine, stamp = 0, playing = false))
        // Offered as "Continue here" for half an hour, unless it is this device's own or the
        // song is the one here already.
        assertTrue(QueueShare.offered(theirs, mine, currentId = "z", now = now))
        assertTrue(QueueShare.offered(theirs, mine, currentId = null, now = now))
        assertFalse(QueueShare.offered(theirs, mine, currentId = "b", now = now))
        assertFalse(QueueShare.offered(theirs.copy(changedBy = mine), mine, currentId = "z", now = now))
        assertFalse(QueueShare.offered(theirs, mine, currentId = "z", now = 86_400L + 30 * 60))
        assertTrue(QueueShare.offered(theirs, mine, currentId = "z", now = 86_400L + 30 * 60 - 1))
        // (A queue saved "after now", by a clock that is ahead, is not offered.)
        assertFalse(QueueShare.offered(theirs, mine, currentId = "z", now = 86_399))
        // Picked up where it had got to; from the start of a song that had all but ended, and
        // at the first song if the one it names isn't among them.
        assertEquals(1 to 30_000L, QueueShare.place(theirs))
        assertEquals(1 to 0L, QueueShare.place(theirs.copy(position = 199_000)))
        assertEquals(0 to 30_000L, QueueShare.place(theirs.copy(current = "gone")))
        assertEquals("NexusMusic-0000beef", QueueShare.deviceName("0000beef"))
        assertTrue(Regex("[0-9a-f]{8}").matches(QueueShare.newDeviceId()))
    }

    // ---- the playlist that holds the record is never among the playlists ---------------------------

    @Test
    fun aPlaylistOfThePersonsOwnCannotTakeTheReservedName() {
        val playlists = org.medianexus.music.library.Playlists
        assertTrue(playlists.reserved("Nexus Music sync"))
        assertTrue(playlists.reserved("  Nexus Music sync "))
        assertFalse(playlists.reserved("Nexus Music sync 2"))
        assertFalse(playlists.reserved("nexus music sync"))
        // Making one of that name, or renaming one to it, is refused before anything is asked
        // of the server.
        val asked = ArrayList<String>()
        val server = object : org.medianexus.music.library.PlaylistServer {
            override suspend fun playlist(id: String): Playlist = error("not asked")
            override suspend fun createPlaylist(name: String, songIds: List<String>): Playlist = Playlist(id = "new").also { asked += "create $name" }
            override suspend fun renamePlaylist(id: String, name: String) { asked += "rename $name" }
            override suspend fun addToPlaylist(id: String, songIds: List<String>) = Unit
            override suspend fun replacePlaylist(id: String, songIds: List<String>) = Unit
            override suspend fun removeManyFromPlaylist(id: String, positions: List<Int>) = Unit
            override suspend fun deletePlaylist(id: String) = Unit
        }
        val pages = PageCache(folder.newFolder(), scope, Dispatchers.Unconfined)
        val mine = org.medianexus.music.library.Playlists(
            server, pages, { org.medianexus.music.store.PageSpec("playlists", kotlinx.serialization.builtins.ListSerializer(Playlist.serializer())) { emptyList() } },
            { org.medianexus.music.store.PageSpec("playlist:$it", Playlist.serializer()) { error("not asked") } }, reachable = { true }, username = "tester", scope = scope,
        )
        val reserved = org.medianexus.music.library.Playlists.Unavailable.RESERVED_NAME
        assertEquals(reserved, mine.create("Nexus Music sync", emptyList()))
        assertEquals(reserved, mine.rename(Playlist(id = "p1", name = "Road trip", owner = "tester"), " Nexus Music sync"))
        assertTrue(asked.isEmpty())
    }

    private fun answer(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
        .body("""{"subsonic-response":{"status":"ok","version":"1.16.1",$body}}""").build()

    @Test
    fun theSyncPlaylistIsNeverListedAndCannotBeOpened() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Subsonic(server.url("/").toString(), "tester", "pass", okhttp3.OkHttpClient(), OfflineSwitch())
            val library = Library(api, PageCache(folder.newFolder(), scope, Dispatchers.Unconfined))
            // (Answered by what is asked, in any order and however often.)
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse = when {
                    "getPlaylists" in request.url.encodedPath -> answer(""""playlists":{"playlist":[{"id":"p1","name":"Road trip","owner":"tester"},{"id":"sync","name":"Nexus Music sync","owner":"tester","comment":"{}"},{"id":"p2","name":"Nexus Music sync notes","owner":"tester"}]}""")
                    else -> answer(""""playlist":{"id":"sync","name":"Nexus Music sync","owner":"tester","comment":"{}"}""")
                }
            }
            // As the server sends it: left out of the list every screen, the car and search read.
            assertEquals(listOf("p1", "p2"), library.fresh(library.playlists()).map { it.id })
            // Asked for by its id (an old link, a car that kept it): not found.
            val refused = runCatching { library.fresh(library.playlist("sync")) }.exceptionOrNull()
            assertEquals(70L, (refused as ApiError.Server).code)
            // A copy of the list kept on the phone by a version that didn't know of it: read
            // without it, with no server to put it right.
            val dir = folder.newFolder()
            val old = PageCache(dir, scope, Dispatchers.Unconfined)
            val list = listOf(Playlist(id = "p1", name = "Road trip"), Playlist(id = "sync", name = "Nexus Music sync", comment = "{}"))
            old.fresh(org.medianexus.music.store.PageSpec("playlists", kotlinx.serialization.builtins.ListSerializer(Playlist.serializer())) { list })
            old.fresh(org.medianexus.music.store.PageSpec("playlist:sync", Playlist.serializer()) { list[1] })
            old.fresh(org.medianexus.music.store.PageSpec("playlist:p1", Playlist.serializer()) { list[0] })
            // (Read by a cache of its own, from the files.)
            var later = Library(api, PageCache(dir, scope, Dispatchers.Unconfined))
            repeat(100) {
                if (later.pages.kept(later.playlist("p1")) == null) {
                    kotlinx.coroutines.delay(20)
                    later = Library(api, PageCache(dir, scope, Dispatchers.Unconfined))
                }
            }
            assertEquals("p1", later.pages.kept(later.playlist("p1"))?.id)
            assertEquals(listOf("p1"), later.pages.kept(later.playlists())?.map { it.id })
            assertNull(later.pages.kept(later.playlist("sync")))
        } finally {
            server.close()
        }
    }
}
