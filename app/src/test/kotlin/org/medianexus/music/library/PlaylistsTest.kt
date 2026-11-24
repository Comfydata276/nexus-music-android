package org.medianexus.music.library

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.store.HeldChanges
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec
import org.medianexus.music.store.Pending

/**
 * Editing a playlist from two devices. The phone is the app's own: its playlists, its one
 * ordered sender writing to a file, its kept pages. The other device is the test, changing the
 * stand-in server's playlist directly, at the moments the test chooses: before the phone's
 * change is sent, while it is held offline, or while the server is in the middle of it (each
 * request of the phone's can be held at a gate). Nothing here waits on time.
 */
class PlaylistsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun song(id: String) = Song(id = id, title = id.uppercase(), duration = 60)
    private fun songs(text: String) = text.split(' ').filter { it.isNotEmpty() }.map(::song)
    private fun ids(playlist: Playlist?) = playlist?.songs?.joinToString(" ") { it.id }

    /** The server: each playlist's songs, and every change it was asked to make, in order. */
    private inner class Server : PlaylistServer {
        val lists = Collections.synchronizedMap(LinkedHashMap<String, MutableList<Song>>())
        val names = Collections.synchronizedMap(HashMap<String, String>())
        val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
        /** A request of the phone's named here waits at its gate (after being noted, before
         *  it is carried out) until the gate is opened. */
        val gates = Collections.synchronizedMap(HashMap<String, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>())

        fun hold(request: String): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> =
            (CompletableDeferred<Unit>() to CompletableDeferred<Unit>()).also { gates[request] = it }

        private suspend fun at(request: String) {
            asked += request
            gates.remove(request.substringBefore(' '))?.let { (reached, open) ->
                reached.complete(Unit)
                open.await()
            }
        }

        private fun of(id: String) = lists[id] ?: throw ApiError.Server(70, "Playlist not found")

        fun now(id: String) = synchronized(lists) { lists[id]?.joinToString(" ") { it.id } }

        /** Another device changes the playlist. */
        fun elsewhere(id: String, text: String) = synchronized(lists) { lists[id] = songs(text).toMutableList() }

        override suspend fun playlist(id: String): Playlist {
            at("get $id")
            return synchronized(lists) { Playlist(id = id, name = names[id] ?: "Mix", owner = "tester", songs = of(id).toList(), songCount = of(id).size) }
        }

        override suspend fun createPlaylist(name: String, songIds: List<String>): Playlist {
            at("create $name")
            val id = "p${lists.size + 1}"
            synchronized(lists) {
                lists[id] = songIds.map(::song).toMutableList()
                names[id] = name
            }
            return Playlist(id = id, name = name)
        }

        override suspend fun renamePlaylist(id: String, name: String) {
            at("rename $id $name")
            of(id)
            names[id] = name
        }

        override suspend fun addToPlaylist(id: String, songIds: List<String>) {
            at("add $id ${songIds.joinToString(",")}")
            synchronized(lists) { of(id).addAll(songIds.map(::song)) }
        }

        override suspend fun replacePlaylist(id: String, songIds: List<String>) {
            at("replace $id ${songIds.joinToString(" ")}")
            synchronized(lists) {
                of(id)
                lists[id] = songIds.map(::song).toMutableList()
            }
        }

        override suspend fun removeManyFromPlaylist(id: String, positions: List<Int>) {
            at("remove $id ${positions.joinToString(",")}")
            synchronized(lists) {
                val list = of(id)
                for (at in positions.sortedDescending()) list.removeAt(at)
            }
        }

        override suspend fun deletePlaylist(id: String) {
            at("delete $id")
            synchronized(lists) { of(id) }
            lists.remove(id)
        }
    }

    private val server = Server()
    @Volatile private var offline = false
    private lateinit var pages: PageCache
    private lateinit var held: HeldChanges
    private lateinit var playlists: Playlists

    private fun listSpec() = PageSpec("playlists", ListSerializer(Playlist.serializer())) { playlists.onTopOfList(server.lists.keys.map { Playlist(id = it, name = server.names[it] ?: "Mix") }) }
    private fun spec(id: String) = PageSpec("playlist:$id", Playlist.serializer()) { playlists.fetched(id) { server.playlist(id) } }

    @Before
    fun start() {
        HeldChanges.restarted(java.io.File(folder.root, "held.json"))
        pages = PageCache(folder.newFolder("pages"), scope, Dispatchers.IO)
        held = HeldChanges(
            java.io.File(folder.root, "held.json"), Dispatchers.IO,
            send = { playlists.send(it as Pending.PlaylistChange) },
            offline = { offline },
            answered = { change, refusal -> playlists.answered(change, refusal) },
            moved = { playlists.heldChanged() },
        )
        playlists = Playlists(
            server, pages, ::listSpec, ::spec,
            submit = { change, written -> held.submit(change, written) },
            held = held.queued, offline = { offline }, username = "tester", scope = scope,
        )
        server.elsewhere("p1", "a b c d e")
        server.names["p1"] = "Mix"
    }

    @After
    fun stop() = scope.cancel()

    /** The phone opens the playlist: what it shows. */
    private suspend fun opened(id: String = "p1"): Playlist = pages.fresh(spec(id))

    /** Waits until the sender has nothing left and the server has had everything. */
    private suspend fun settled() = withTimeout(10_000) {
        held.waiting.first { it == 0 }
        // (The change has left the queue; the page's own catching up may still be on its way.)
        held.flush()
    }

    private suspend fun shown(id: String = "p1") = pages.kept(spec(id))

    // ---- Two devices -------------------------------------------------------------------------

    @Test
    fun aRemovalFromAPageThatIsOutOfDateRemovesTheSongMeant() = runBlocking {
        val page = opened()
        // Meanwhile the other device reverses the playlist and adds a song.
        server.elsewhere("p1", "e d c b a x")
        // The phone removes its second row: "b".
        playlists.remove(page, listOf(1))
        settled()
        assertEquals("e d c a x", server.now("p1"))
        // Sent as the server had it then: its fourth song, by its place there.
        assertTrue(server.asked.contains("remove p1 3"))
    }

    @Test
    fun theSameSongTwiceIsToldApart() = runBlocking {
        server.elsewhere("p1", "a b a c a")
        val page = opened()
        // The phone removes the second "a"; the other device has removed the first.
        server.elsewhere("p1", "b a c a")
        playlists.remove(page, listOf(2))
        settled()
        // One copy each went: two of three gone, the songs between them where they were.
        assertEquals("b a c", server.now("p1"))
        // With only one copy left on the server, there is no second copy to take out.
        server.elsewhere("p1", "a b a")
        val again = opened()
        server.elsewhere("p1", "b a")
        playlists.remove(again, listOf(2))
        settled()
        assertEquals("b a", server.now("p1"))
    }

    @Test
    fun aRemovalCrossingAReorderTakesOutTheSongWhereverItNowIs() = runBlocking {
        val page = opened()
        // The phone's removal is on its way: the server is reading the playlist for it.
        val (reached, open) = server.hold("get")
        playlists.remove(page, listOf(1))
        reached.await()
        // The other device's reorder lands before the phone's read is answered.
        server.elsewhere("p1", "b d a c e")
        open.complete(Unit)
        settled()
        assertEquals("d a c e", server.now("p1"))
    }

    @Test
    fun aMoveFollowsItsNeighbourAndAddedSongsAreKept() = runBlocking {
        val page = opened()
        server.elsewhere("p1", "x a b c d e y")
        // "d" dragged up to follow "a".
        playlists.move(page, 3, 1)
        settled()
        assertEquals("x a d b c e y", server.now("p1"))
        // The neighbour taken out elsewhere first: it goes to the end.
        val again = opened()
        server.elsewhere("p1", "x d b c e y")
        playlists.move(again, 5, 2)
        settled()
        assertEquals("x d b c y e", server.now("p1"))
    }

    @Test
    fun twoQuickEditsFromThePhoneGoOneAtATimeInTheOrderMade() = runBlocking {
        val page = opened()
        // The first removal is held at the server as it is carried out.
        val (reached, open) = server.hold("remove")
        playlists.remove(page, listOf(0))
        reached.await()
        // The second edit is made from the page as it now shows ("b c d e"): "e" to the top.
        val now = shown()!!
        assertEquals("b c d e", ids(now))
        playlists.move(now, 4 - 1, 0)
        // The second waits its turn: nothing of it has reached the server.
        held.waiting.first { it == 2 }
        assertTrue("the move was sent past the removal", server.asked.none { it.startsWith("replace") })
        open.complete(Unit)
        settled()
        assertEquals("e b c d", server.now("p1"))
        val order = server.asked.filter { it.startsWith("remove") || it.startsWith("replace") }
        assertEquals(listOf("remove p1 0", "replace p1 e b c d"), order)
    }

    // ---- Offline --------------------------------------------------------------------------

    @Test
    fun offlineAnEditIsHeldShownOnTopOfWhatTheServerSaysAndSentWhenBack() = runBlocking {
        val page = opened()
        offline = true
        val shownAtOnce = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { pages.changes.first { (key, value) -> key == "playlist:p1" && ids(value as Playlist) == "a c d e" } }
        }
        playlists.remove(page, listOf(1))
        // Shown at once, from the phone's copy; nothing asked of the server.
        shownAtOnce.await()
        held.waiting.first { it == 1 }
        assertEquals(1, held.waiting.value)
        val asked = server.asked.size
        // The server changes meanwhile (another device adds "y"); the phone, back online, is
        // shown the server's copy with the removal still held on top of it.
        server.elsewhere("p1", "a b c d e y")
        offline = false
        // (The page is asked for before the sender has sent: as when the screen refreshes
        // first.) The held removal is on top.
        assertEquals("a c d e y", ids(opened()))
        held.flush()
        settled()
        assertEquals("a c d e y", server.now("p1"))
        assertTrue(server.asked.size > asked)
    }

    @Test
    fun aFetchCrossingASendNeverShowsTheChangeTwice() = runBlocking {
        server.elsewhere("p1", "a b")
        opened()
        // The phone adds "z"; the server has carried it out but not yet answered.
        val (reached, open) = server.hold("add")
        playlists.add("p1", "Mix", listOf(song("z")))
        reached.await()
        // A refresh begins now, and has to wait for the send's turn.
        val fetching = async { opened() }
        open.complete(Unit)
        assertEquals("a b z", ids(fetching.await()))
        settled()
        assertEquals("a b z", ids(opened()))
    }

    // ---- Refused, undone, made, someone else's ------------------------------------------

    @Test
    fun anEditToAPlaylistDeletedElsewhereIsPutRightAndSaid() = runBlocking {
        val page = opened()
        val said = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { playlists.refused.first() } }
        server.lists.remove("p1")
        playlists.remove(page, listOf(0))
        val refusal = said.await()
        assertEquals("p1", refusal.playlist)
        assertEquals(70L, (refusal.error as ApiError.Server).code)
        settled()
        assertEquals(0, held.waiting.value)
    }

    @Test
    fun undoPutsRemovedSongsBackBesideTheirNeighboursAsThePlaylistNowIs() = runBlocking {
        val page = opened()
        playlists.remove(page, listOf(1, 2))
        settled()
        assertEquals("a d e", server.now("p1"))
        // Something was added elsewhere before the undo: it stays.
        server.elsewhere("p1", "a x d e")
        assertTrue(playlists.undoLast("Mix"))
        settled()
        assertEquals("a b c x d e", server.now("p1"))
        // Nothing left to undo.
        assertFalse(playlists.undoLast("Mix"))
    }

    @Test
    fun undoAMoveTakesTheSongBackAfterTheOneItFollowed() = runBlocking {
        val page = opened()
        playlists.move(page, 0, 3)
        settled()
        assertEquals("b c d a e", server.now("p1"))
        assertTrue(playlists.undoLast("Mix"))
        settled()
        assertEquals("a b c d e", server.now("p1"))
    }

    @Test
    fun aNewPlaylistIsNotMadeOfflineAndIsMadeOnline() = runBlocking {
        offline = true
        assertFalse(playlists.create("Road trip", songs("a b")))
        assertEquals(0, held.waiting.value)
        offline = false
        assertTrue(playlists.create("Road trip", songs("a b")))
        settled()
        assertEquals("Road trip", server.names["p2"])
        assertEquals("a b", server.now("p2"))
    }

    @Test
    fun someoneElsesPlaylistIsNotTheAccountsToChange() {
        assertTrue(playlists.canEdit(Playlist(id = "p1", owner = "tester")))
        assertTrue(playlists.canEdit(Playlist(id = "p1", owner = "Tester")))
        assertTrue(playlists.canEdit(Playlist(id = "p1")))
        assertFalse(playlists.canEdit(Playlist(id = "p9", owner = "guest")))
        // The server's own word, whoever it names.
        assertFalse(playlists.canEdit(Playlist(id = "p1", owner = "tester", readonly = true)))
    }

    @Test
    fun theWarningBeforeAddingNamesTheSongsAlreadyThereAsTheServerHasThemNow() = runBlocking {
        opened()
        server.elsewhere("p1", "a b c d e f")
        assertEquals(setOf("f"), playlists.alreadyIn("p1", songs("f g")))
        assertEquals(emptySet<String>(), playlists.alreadyIn("p1", songs("g")))
        assertEquals(null, playlists.alreadyIn("p9", songs("g")))
    }

    @Test
    fun aRenameAndADeleteShowAtOnceInTheList() = runBlocking {
        server.elsewhere("p2", "a")
        server.names["p2"] = "Other"
        pages.fresh(listSpec())
        offline = true
        val shownAtOnce = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { pages.changes.first { (key, value) -> key == "playlists" && (value as List<*>).map { (it as Playlist).name } == listOf("Morning") } }
        }
        playlists.rename("p1", "Morning")
        playlists.delete("p2", "Other")
        shownAtOnce.await()
        offline = false
        held.flush()
        settled()
        assertEquals("Morning", server.names["p1"])
        assertFalse("p2" in server.lists)
    }
}
