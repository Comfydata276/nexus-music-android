package org.medianexus.music.library

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.library.Playlists.Unavailable
import org.medianexus.music.library.PlaylistRefused.Outcome
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/**
 * Changing a playlist from two devices (M3b fixes 2, A: the strict rule). The phone is the
 * app's own playlists and kept pages; the other device is the test, changing the stand-in
 * server directly at the moments it chooses. Each of the phone's requests can be held at a
 * gate after the server has it and before it is carried out, made to fail, or have its answer
 * lost after it was carried out; every request is counted. Nothing here waits on time.
 */
class PlaylistsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun song(id: String) = Song(id = id, title = id.uppercase(), duration = 60)
    private fun songs(text: String) = text.split(' ').filter { it.isNotEmpty() }.map(::song)
    private fun ids(playlist: Playlist?) = playlist?.songs?.joinToString(" ") { it.id }

    /** What the stand-in server does with one kind of request, once. */
    private enum class Trouble { REFUSE, LOSE_ANSWER, GONE }

    private inner class Server : PlaylistServer {
        val lists = Collections.synchronizedMap(LinkedHashMap<String, MutableList<Song>>())
        val names = Collections.synchronizedMap(HashMap<String, String>())
        val owners = Collections.synchronizedMap(HashMap<String, String>())
        val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
        val gates = Collections.synchronizedMap(HashMap<String, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>())
        val trouble = Collections.synchronizedMap(HashMap<String, Trouble>())

        /** Songs gone from the library: as Navidrome 0.64.2 does (docs/TESTING.md), a playlist
         *  keeps their entries, counts them in its positions and in the list's count, and
         *  leaves them out of what it lists. */
        val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

        fun hold(request: String): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> =
            (CompletableDeferred<Unit>() to CompletableDeferred<Unit>()).also { gates[request] = it }

        /** The request named arrives: noted, held at its gate if it has one, then refused, or
         *  carried out (`work`) with its answer lost, or carried out. */
        private suspend fun <T> at(request: String, work: () -> T): T {
            asked += request
            val kind = request.substringBefore(' ')
            gates.remove(kind)?.let { (reached, open) ->
                reached.complete(Unit)
                open.await()
            }
            when (trouble.remove(kind)) {
                Trouble.REFUSE -> throw ApiError.Server(50, "")
                Trouble.GONE -> throw ApiError.Server(70, "")
                Trouble.LOSE_ANSWER -> {
                    work()
                    throw ApiError.Network("The connection dropped.")
                }
                null -> return work()
            }
        }

        private fun of(id: String) = lists[id] ?: throw ApiError.Server(70, "")

        fun now(id: String) = synchronized(lists) { lists[id]?.joinToString(" ") { it.id } }

        /** Another device changes the playlist. */
        fun elsewhere(id: String, text: String) = synchronized(lists) { lists[id] = songs(text).toMutableList() }

        fun count(kind: String) = asked.count { it.startsWith("$kind ") }

        // (With the count the list gives, as the app's own reads are made: Subsonic.playlistCounted.)
        override suspend fun playlist(id: String) = at("get $id") {
            synchronized(lists) {
                val held = of(id)
                Playlist(id = id, name = names[id] ?: "Mix", owner = owners[id] ?: "tester", songs = held.filter { it.id !in missing }, held = held.size)
            }
        }

        override suspend fun createPlaylist(name: String, songIds: List<String>) = at("create $name") {
            val id = "p${lists.size + 1}"
            synchronized(lists) {
                lists[id] = songIds.map(::song).toMutableList()
                names[id] = name
            }
            Playlist(id = id, name = name)
        }

        override suspend fun renamePlaylist(id: String, name: String) = at("rename $id $name") {
            of(id)
            names[id] = name
        }

        override suspend fun addToPlaylist(id: String, songIds: List<String>) = at("add $id ${songIds.joinToString(",")}") {
            synchronized(lists) { of(id).addAll(songIds.map(::song)) }
            Unit
        }

        override suspend fun replacePlaylist(id: String, songIds: List<String>) = at("replace $id ${songIds.joinToString(" ")}") {
            synchronized(lists) {
                of(id)
                lists[id] = songIds.map(::song).toMutableList()
            }
        }

        override suspend fun removeManyFromPlaylist(id: String, positions: List<Int>) = at("remove $id ${positions.joinToString(",")}") {
            synchronized(lists) {
                val list = of(id)
                for (at in positions.sortedDescending()) list.removeAt(at)
            }
        }

        override suspend fun deletePlaylist(id: String) = at("delete $id") {
            synchronized(lists) { of(id) }
            lists.remove(id)
            Unit
        }
    }

    private val server = Server()
    @Volatile private var reachable = true
    private lateinit var pages: PageCache
    private lateinit var playlists: Playlists

    private fun listSpec() = PageSpec("playlists", ListSerializer(Playlist.serializer())) {
        synchronized(server.lists) { server.lists.map { (id, songs) -> Playlist(id = id, name = server.names[id] ?: "Mix", owner = "tester", songCount = songs.size) } }
    }
    private fun spec(id: String) = PageSpec("playlist:$id", Playlist.serializer()) { playlists.fetched(id) { server.playlist(id) } }

    @Before
    fun start() {
        pages = PageCache(folder.newFolder("pages"), scope, Dispatchers.IO)
        playlists = Playlists(server, pages, ::listSpec, ::spec, reachable = { reachable }, username = "tester", scope = scope)
        server.elsewhere("p1", "a b c d e")
    }

    @After
    fun stop() = scope.cancel()

    /** The phone opens the playlist: what it keeps, as the server sent it. */
    private suspend fun opened(id: String = "p1"): Playlist = pages.fresh(spec(id))

    private suspend fun kept(id: String = "p1") = ids(pages.kept(spec(id)))

    /** Makes a change and waits until the server has settled it: whether it was done. */
    private suspend fun made(change: ((Boolean) -> Unit) -> Unavailable?): Boolean {
        val done = CompletableDeferred<Boolean>()
        assertNull(change { done.complete(it) })
        return withTimeout(10_000) { done.await() }
    }

    /** Listens for what is said of a change that failed, from now on. */
    private fun CoroutineScope.listen() = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { playlists.refused.first() } }

    /** Every request that changes a playlist, so far. */
    private fun writes() = server.asked.count { !it.startsWith("get ") }

    // ---- A1: on the playlist exactly as it was drawn, or not at all ---------------------------

    @Test
    fun onThePlaylistAsDrawnEachChangeIsMadeAndThePageIsWhatWasSent() = runBlocking<Unit> {
        var page = opened()
        assertTrue(made { playlists.remove(page, listOf(1), it) })
        assertEquals("a c d e", server.now("p1"))
        // A3: kept at once as sent; nothing read afterwards to learn it.
        assertEquals("a c d e", kept())
        assertEquals(2, server.count("get"))
        page = pages.kept(spec("p1"))!!
        assertTrue(made { playlists.move(page, 3, 0, it) })
        assertEquals("e a c d", server.now("p1"))
        assertEquals("e a c d", kept())
        page = pages.kept(spec("p1"))!!
        assertTrue(made { playlists.rename(page, "Road trip", it) })
        assertEquals("Road trip", server.names["p1"])
        assertEquals("Road trip", pages.kept(spec("p1"))!!.name)
        page = pages.kept(spec("p1"))!!
        assertTrue(made { playlists.delete(page, it) })
        assertNull(server.now("p1"))
        assertNull(pages.kept(spec("p1")))
    }

    @Test
    fun changedElsewhereBeforeThePhonesReadNothingIsSentAndThePageShowsItSaidOnce() = runBlocking<Unit> {
        for (case in listOf("remove", "move", "rename", "delete")) {
            server.elsewhere("p1", "a b c d e")
            val page = opened()
            // The other device reorders it after the page was drawn.
            server.elsewhere("p1", "e d c b a")
            val said = listen()
            val before = writes()
            assertFalse(case, made { done ->
                when (case) {
                    "remove" -> playlists.remove(page, listOf(1), done)
                    "move" -> playlists.move(page, 0, 2, done)
                    "rename" -> playlists.rename(page, "Road trip", done)
                    else -> playlists.delete(page, done)
                }
            })
            assertEquals(case, Outcome.CHANGED, said.await().outcome)
            assertEquals(case, before, writes())
            assertEquals(case, "e d c b a", server.now("p1"))
            assertEquals(case, "e d c b a", kept())
        }
    }

    @Test
    fun aRenameMadeElsewhereIsAChangeToo() = runBlocking<Unit> {
        val page = opened()
        server.names["p1"] = "Renamed elsewhere"
        assertFalse(made { playlists.remove(page, listOf(0), it) })
        assertEquals(0, server.count("remove"))
        assertEquals("Renamed elsewhere", pages.kept(spec("p1"))!!.name)
    }

    @Test
    fun songsAddedAtTheEndGoWhateverThePlaylistHolds() = runBlocking<Unit> {
        val page = opened()
        server.elsewhere("p1", "e d c b a")
        assertTrue(made { playlists.add(page, songs("z"), it) })
        assertEquals("e d c b a z", server.now("p1"))
        assertEquals("e d c b a z", kept())
    }

    @Test
    fun aCardsMenuSawOnlyTheNameAndCountSoOnlyThoseMustBeTheSame() = runBlocking<Unit> {
        // From the list of playlists: a name and a count, no songs.
        val card = Playlist(id = "p1", name = "Mix", owner = "tester", songCount = 5)
        server.elsewhere("p1", "e d c b a")
        assertTrue(made { playlists.rename(card, "Road trip", it) })
        assertEquals("Road trip", server.names["p1"])
    }

    @Test
    fun aCardWhoseCountHasChangedSendsNothing() = runBlocking<Unit> {
        for (case in listOf("rename", "delete")) {
            server.elsewhere("p1", "a b c d e")
            server.names["p1"] = "Mix"
            val card = pages.fresh(listSpec()).first { it.id == "p1" }
            assertEquals(5, card.songCount)
            // Another device adds a song: the card still says five.
            server.elsewhere("p1", "a b c d e f")
            val said = listen()
            val before = writes()
            assertFalse(case, made { if (case == "rename") playlists.rename(card, "Road trip", it) else playlists.delete(card, it) })
            assertEquals(case, Outcome.CHANGED, said.await().outcome)
            assertEquals(case, before, writes())
            assertEquals(case, "Mix", server.names["p1"])
            assertEquals(case, "a b c d e f", server.now("p1"))
            // The card is brought up to what was read: now its change goes.
            val now = pages.kept(listSpec())!!.first { it.id == "p1" }
            assertEquals(case, 6, now.songCount)
            assertTrue(case, made { if (case == "rename") playlists.rename(now, "Road trip", it) else playlists.delete(now, it) })
        }
    }

    @Test
    fun theCardsCountFollowsThePhonesOwnChange() = runBlocking<Unit> {
        pages.fresh(listSpec())
        val page = opened()
        assertTrue(made { playlists.remove(page, listOf(0), it) })
        val card = pages.kept(listSpec())!!.first { it.id == "p1" }
        assertEquals(4, card.songCount)
        assertTrue(made { playlists.rename(card, "Road trip", it) })
    }

    @Test
    fun aBulkSheetLeftOpenOverAReplacedListIsRefused() = runBlocking<Unit> {
        // The selection's sheet holds the playlist as drawn when it opened; the list is then
        // replaced (another device, and a refresh) while it is still open.
        val drawn = opened()
        server.elsewhere("p1", "a x b c d e")
        opened()
        assertFalse(made { playlists.remove(drawn, listOf(1, 2), it) })
        assertEquals(0, server.count("remove"))
        assertEquals("a x b c d e", server.now("p1"))
    }

    // ---- Songs missing from the library (M3b fixes 3, part 1) ------------------------------

    @Test
    fun aPlaylistHoldingSongsMissingFromTheLibraryIsNotChangedByPosition() = runBlocking<Unit> {
        // Stored as "x a b c"; "x" is gone from the library, so the phone is shown "a b c". On
        // the real server, removing row 1 ("b") would take out "a", and a move would throw "x"
        // away for good.
        server.elsewhere("p1", "x a b c")
        server.missing += "x"
        val page = opened()
        assertEquals("a b c", ids(page))
        assertTrue(page.hidesSongs)
        assertEquals(Unavailable.MISSING_SONGS, playlists.remove(page, listOf(1)))
        assertEquals(Unavailable.MISSING_SONGS, playlists.move(page, 2, 0))
        assertEquals(0, writes())
        // Adding at the end leaves the hidden entry be, so it goes.
        assertTrue(made { playlists.add(page, songs("z"), it) })
        assertEquals("x a b c z", server.now("p1"))
    }

    @Test
    fun aSongThatGoesMissingAfterThePageWasDrawnIsFoundOnTheFreshCopy() = runBlocking<Unit> {
        for (case in listOf("remove", "move", "undo")) {
            server.elsewhere("p1", "x a b c")
            server.missing.clear()
            var page = opened()
            if (case == "undo") {
                assertTrue(made { playlists.remove(page, listOf(2), it) })
                page = pages.kept(spec("p1"))!!
            }
            // The disk holding "x" goes offline and the server scans.
            server.missing += "x"
            val held = server.now("p1")
            val said = listen()
            val before = writes()
            assertFalse(case, made { done ->
                when (case) {
                    "remove" -> playlists.remove(page, listOf(1), done)
                    "move" -> playlists.move(page, 3, 0, done)
                    else -> playlists.undoLast("p1", done)
                }
            })
            assertEquals(case, Outcome.MISSING_SONGS, said.await().outcome)
            assertEquals(case, before, writes())
            assertEquals(case, held, server.now("p1"))
            // The page is the fresh copy, which says why its handles are dimmed.
            assertTrue(case, pages.kept(spec("p1"))!!.hidesSongs)
        }
    }

    // ---- A2: one change at a time ------------------------------------------------------------

    @Test
    fun whileAChangeIsOnItsWayEveryOtherChangeToThatPlaylistDoesNothing() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        val first = CompletableDeferred<Boolean>()
        assertNull(playlists.remove(page, listOf(0)) { first.complete(it) })
        reached.await()
        assertEquals(setOf("p1"), playlists.busy.value)
        // Shown as made while it is on its way.
        assertEquals("b c d e", ids(playlists.shown(page)))
        val before = writes()
        assertEquals(Unavailable.BUSY, playlists.remove(page, listOf(1)))
        assertEquals(Unavailable.BUSY, playlists.move(page, 0, 2))
        assertEquals(Unavailable.BUSY, playlists.add(page, songs("z")))
        assertEquals(Unavailable.BUSY, playlists.rename(page, "Road trip"))
        assertEquals(Unavailable.BUSY, playlists.delete(page))
        assertEquals(before, writes())
        open.complete(Unit)
        assertTrue(withTimeout(10_000) { first.await() })
        assertEquals(emptySet<String>(), playlists.busy.value)
        assertEquals("b c d e", server.now("p1"))
        assertEquals(1, writes())
    }

    @Test
    fun aFailureLeavesNothingBehindItAndThePageAsTheServerHasIt() = runBlocking<Unit> {
        val page = opened()
        server.trouble["remove"] = Trouble.REFUSE
        val said = listen()
        assertFalse(made { playlists.remove(page, listOf(0), it) })
        assertEquals(Outcome.REFUSED, said.await().outcome)
        assertEquals("a b c d e", ids(playlists.shown(pages.kept(spec("p1"))!!)))
        assertEquals(Unavailable.NOTHING_TO_UNDO, playlists.undoLast("p1"))
    }

    // ---- A4: Undo ----------------------------------------------------------------------------

    @Test
    fun undoIsTheReverseOfWhatTheServerSaidYesTo() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        val done = CompletableDeferred<Boolean>()
        playlists.remove(page, listOf(1, 3)) { done.complete(it) }
        reached.await()
        // Not offered before the server has said yes.
        assertEquals(Unavailable.NOTHING_TO_UNDO, playlists.undoLast("p1"))
        open.complete(Unit)
        assertTrue(withTimeout(10_000) { done.await() })
        assertTrue(made { playlists.undoLast("p1", it) })
        assertEquals("a b c d e", server.now("p1"))
        assertEquals("a b c d e", kept())
        // Used up.
        assertEquals(Unavailable.NOTHING_TO_UNDO, playlists.undoLast("p1"))
    }

    @Test
    fun undoOfAPlaylistChangedSinceSendsNothingAndSaysSo() = runBlocking<Unit> {
        val page = opened()
        assertTrue(made { playlists.remove(page, listOf(1), it) })
        // Another device has since taken out "d", which Undo would otherwise put back.
        server.elsewhere("p1", "a c e")
        val said = listen()
        val before = writes()
        assertFalse(made { playlists.undoLast("p1", it) })
        assertEquals(Outcome.UNDO_CHANGED, said.await().outcome)
        assertEquals(before, writes())
        assertEquals("a c e", server.now("p1"))
        assertEquals("a c e", kept())
    }

    @Test
    fun undoWithTheServerOutOfReachSaysSoAndIsKept() = runBlocking<Unit> {
        val page = opened()
        assertTrue(made { playlists.move(page, 0, 4, it) })
        reachable = false
        assertEquals(Unavailable.OFFLINE, playlists.undoLast("p1"))
        reachable = true
        assertTrue(made { playlists.undoLast("p1", it) })
        assertEquals("a b c d e", server.now("p1"))
    }

    // ---- How a change ends -------------------------------------------------------------------

    @Test
    fun aLostAnswerIsUnknownAndThePlaylistIsReadAgain() = runBlocking<Unit> {
        val page = opened()
        server.trouble["remove"] = Trouble.LOSE_ANSWER
        val said = listen()
        assertFalse(made { playlists.remove(page, listOf(0), it) })
        assertEquals(Outcome.UNCONFIRMED, said.await().outcome)
        withTimeout(10_000) { while (kept() != "b c d e") kotlinx.coroutines.delay(5) }
        assertEquals(1, server.count("remove"))
    }

    @Test
    fun aReadThatFailsBeforeTheWriteMeansNothingWasSent() = runBlocking<Unit> {
        val page = opened()
        server.trouble["get"] = Trouble.LOSE_ANSWER
        val said = listen()
        assertFalse(made { playlists.remove(page, listOf(0), it) })
        assertEquals(Outcome.UNSENT, said.await().outcome)
        assertEquals(0, writes())
    }

    @Test
    fun withTheServerNotThereNothingCanBeChanged() = runBlocking<Unit> {
        val page = opened()
        reachable = false
        val asked = server.asked.size
        assertEquals(Unavailable.OFFLINE, playlists.remove(page, listOf(0)))
        assertEquals(Unavailable.OFFLINE, playlists.move(page, 0, 2))
        assertEquals(Unavailable.OFFLINE, playlists.add(page, songs("z")))
        assertEquals(Unavailable.OFFLINE, playlists.create("Road trip", songs("a")))
        assertEquals(Unavailable.OFFLINE, playlists.rename(page, "x"))
        assertEquals(Unavailable.OFFLINE, playlists.delete(page))
        assertEquals(asked, server.asked.size)
    }

    @Test
    fun aNewPlaylistWhoseAnswerNeverCameHasTheListAskedForAgain() = runBlocking<Unit> {
        pages.fresh(listSpec())
        server.trouble["create"] = Trouble.LOSE_ANSWER
        assertFalse(made { playlists.create("Road trip", songs("a b"), it) })
        withTimeout(10_000) { while (pages.kept(listSpec())?.size != 2) kotlinx.coroutines.delay(5) }
        // And one that is answered: made once.
        assertTrue(made { playlists.create("Another", songs("c"), it) })
        assertEquals(1, server.asked.count { it == "create Another" })
    }

    // ---- A6: whose it is ----------------------------------------------------------------------

    @Test
    fun onlyAPlaylistTheServerSaysIsTheAccountsCanBeChanged() = runBlocking<Unit> {
        assertTrue(playlists.canEdit(Playlist(id = "p", owner = "Tester")))
        assertFalse("no owner given", playlists.canEdit(Playlist(id = "p", owner = "")))
        assertFalse(playlists.canEdit(Playlist(id = "p", owner = "guest")))
        assertFalse(playlists.canEdit(Playlist(id = "p", owner = "tester", readonly = true)))
        assertEquals(Unavailable.NOT_YOURS, playlists.remove(Playlist(id = "p1", songs = songs("a b")), listOf(0)))
        // Given away on the server since: refused on the fresh copy, nothing written.
        val page = opened()
        server.owners["p1"] = "guest"
        val said = listen()
        assertFalse(made { playlists.remove(page, listOf(0), it) })
        assertEquals(Outcome.REFUSED, said.await().outcome)
        assertEquals(0, writes())
    }

    // ---- A7: gone, wherever it is learnt -----------------------------------------------------

    @Test
    fun aPlaylistTheServerNoLongerHasGoesWhereverThatIsLearnt() = runBlocking<Unit> {
        for (case in listOf("refresh", "before a write")) {
            server.elsewhere("p1", "a b c d e")
            playlists = Playlists(server, pages, ::listSpec, ::spec, reachable = { reachable }, username = "tester", scope = scope)
            pages.fresh(listSpec())
            val page = opened()
            server.lists.remove("p1")
            val said = listen()
            if (case == "refresh") {
                runCatching { pages.fresh(spec("p1")) }
            } else {
                assertFalse(made { playlists.remove(page, listOf(0), it) })
            }
            assertEquals(case, Outcome.GONE, said.await().outcome)
            assertTrue(case, "p1" in playlists.gone.value)
            assertNull(case, pages.kept(spec("p1")))
            assertFalse(case, pages.kept(listSpec())!!.any { it.id == "p1" })
        }
    }

    // ---- A8: the window that remains --------------------------------------------------------

    /**
     * **The known window** (docs/DESIGN.md). Subsonic can't make a write conditional on the
     * read before it. The phone reads the playlist, finds it as drawn, and writes; a change
     * another device makes between the two is overwritten, or the write lands on the wrong
     * song. Here the phone means to remove "b" (row 1); the other device reverses the playlist
     * after the phone's read; the write removes row 1, which is now "d". Accepted: nothing
     * comes between the read and the write but comparing them.
     */
    @Test
    fun aChangeElsewhereBetweenThePhonesReadAndItsWriteIsTheKnownWindow() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        val done = CompletableDeferred<Boolean>()
        playlists.remove(page, listOf(1)) { done.complete(it) }
        reached.await()
        server.elsewhere("p1", "e d c b a")
        open.complete(Unit)
        assertTrue(withTimeout(10_000) { done.await() })
        assertEquals("the wrong song went: the window", "e c b a", server.now("p1"))
        assertEquals(1, server.count("remove"))
    }
}
