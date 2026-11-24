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
 * Changing a playlist from two devices (M3b fixes, A). The phone is the app's own playlists
 * and kept pages; the other device is the test, changing the stand-in server directly at the
 * moments it chooses. Each of the phone's requests can be held at a gate after the server has
 * it and before it is carried out, made to fail, or have its answer lost after it was carried
 * out; every request is counted. Nothing here waits on time.
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

        override suspend fun playlist(id: String) = at("get $id") {
            synchronized(lists) { Playlist(id = id, name = names[id] ?: "Mix", owner = owners[id] ?: "tester", songs = of(id).toList()) }
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

    private fun listSpec() = PageSpec("playlists", ListSerializer(Playlist.serializer())) { server.lists.keys.map { Playlist(id = it, name = server.names[it] ?: "Mix") } }
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

    /** Waits until nothing is waiting to be sent. */
    private suspend fun settled() = withTimeout(10_000) { playlists.waiting.first { it.isEmpty() } }

    /** Listens for what is said of a change that failed, from now on. */
    private fun CoroutineScope.listen() = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { playlists.refused.first() } }

    // ---- Two devices -------------------------------------------------------------------------

    @Test
    fun aRemovalFromAPageThatIsOutOfDateRemovesTheSongMeant() = runBlocking<Unit> {
        val page = opened()
        server.elsewhere("p1", "e d c b a x")
        assertNull(playlists.remove(page, listOf(1)))
        settled()
        assertEquals("e d c a x", server.now("p1"))
        assertEquals(1, server.count("remove"))
    }

    @Test
    fun theSameSongTwiceIsToldApart() = runBlocking<Unit> {
        server.elsewhere("p1", "a b a c a")
        val page = opened()
        server.elsewhere("p1", "b a c a")
        playlists.remove(page, listOf(2))
        settled()
        assertEquals("b a c", server.now("p1"))
    }

    @Test
    fun aMoveFollowsItsNeighbourAndSongsAddedElsewhereAreKept() = runBlocking<Unit> {
        val page = opened()
        server.elsewhere("p1", "x a b c d e y")
        playlists.move(page, 3, 1)
        settled()
        assertEquals("x a d b c e y", server.now("p1"))
    }

    /**
     * **The known window** (docs/DESIGN.md, A5). Subsonic can't make a write conditional on
     * the read before it, so a change another device makes between the phone's read of the
     * playlist and its write is overwritten, or the write lands on the wrong song. Here the
     * phone means to remove "b"; it reads "a b c d e" (b at 1); the other device reverses the
     * playlist before the write; the write removes position 1, which is now "d". Accepted:
     * Windows has the same window, and the read and the write are kept as close together as
     * they can be (nothing between them but working out the positions).
     */
    @Test
    fun aChangeElsewhereBetweenThePhonesReadAndItsWriteIsTheKnownWindow() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        playlists.remove(page, listOf(1))
        reached.await()
        // The phone has read the playlist and worked out the position; the write is on its way.
        server.elsewhere("p1", "e d c b a")
        open.complete(Unit)
        settled()
        assertEquals("the wrong song went: the window", "e c b a", server.now("p1"))
        assertEquals(1, server.count("remove"))
    }

    @Test
    fun twoQuickEditsGoOneAtATimeInTheOrderMade() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        playlists.remove(page, listOf(0))
        reached.await()
        // Made from the page as it now shows: "b c d e", "e" to the top.
        val shown = playlists.shown(pages.kept(spec("p1"))!!)
        assertEquals("b c d e", ids(shown))
        playlists.move(shown, 3, 0)
        assertEquals(0, server.count("replace"))
        open.complete(Unit)
        settled()
        assertEquals("e b c d", server.now("p1"))
    }

    // ---- Only with the server there, never kept ---------------------------------------------

    @Test
    fun withTheServerNotThereNothingCanBeChangedAndNothingIsKept() = runBlocking<Unit> {
        val page = opened()
        reachable = false
        val asked = server.asked.size
        assertEquals(Unavailable.OFFLINE, playlists.remove(page, listOf(0)))
        assertEquals(Unavailable.OFFLINE, playlists.move(page, 0, 2))
        assertEquals(Unavailable.OFFLINE, playlists.add(page, songs("z")))
        assertEquals(Unavailable.OFFLINE, playlists.create("Road trip", songs("a")))
        assertEquals(Unavailable.OFFLINE, playlists.rename(page, "New"))
        assertEquals(Unavailable.OFFLINE, playlists.delete(page))
        assertFalse(playlists.undoLast(page))
        assertTrue(playlists.waiting.value.isEmpty())
        // Back: nothing was kept to be sent.
        reachable = true
        assertEquals(asked, server.asked.size)
        assertEquals("a b c d e", server.now("p1"))
    }

    @Test
    fun theKeptPageOnlyEverHoldsWhatTheServerSaid() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        playlists.remove(page, listOf(1))
        reached.await()
        // Waiting: shown at once, but the kept page is the server's.
        assertEquals("a b c d e", ids(pages.kept(spec("p1"))))
        assertEquals("a c d e", ids(playlists.shown(pages.kept(spec("p1"))!!)))
        open.complete(Unit)
        settled()
        assertEquals("a c d e", ids(pages.kept(spec("p1"))))
        assertEquals("a c d e", ids(playlists.shown(pages.kept(spec("p1"))!!)))
    }

    /** What the page draws while a move is still on its way, which is what a tap on a row
     *  hands the player (D2): the playlist as shown, with the move. */
    @Test
    fun aMoveStillOnItsWayIsInWhatThePageShows() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("replace")
        playlists.move(page, 4, 0)
        reached.await()
        assertEquals("e a b c d", ids(playlists.shown(pages.kept(spec("p1"))!!)))
        open.complete(Unit)
        settled()
        assertEquals("e a b c d", ids(playlists.shown(pages.kept(spec("p1"))!!)))
    }

    @Test
    fun aRefreshThatCrossesAnAddShowsTheSongOnce() = runBlocking<Unit> {
        val page = opened()
        // The add is on its way: the server has read the playlist for it and is about to add.
        val (reached, open) = server.hold("add")
        playlists.add(page, songs("z"))
        reached.await()
        // A refresh begins now, and waits for the change's turn.
        val refresh = async(Dispatchers.IO) { opened() }
        open.complete(Unit)
        val fresh = refresh.await()
        settled()
        assertEquals("a b c d e z", ids(fresh))
        assertEquals("a b c d e z", ids(playlists.shown(pages.kept(spec("p1"))!!)))
    }

    // ---- One attempt, and what follows a failure ----------------------------------------------

    @Test
    fun anUnansweredChangeIsNeverSentAgainAndThePersonIsAskedToCheck() = runBlocking<Unit> {
        server.elsewhere("p1", "a b a")
        val page = opened()
        server.trouble["remove"] = Trouble.LOSE_ANSWER
        val said = listen()
        val readAgain = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { pages.changes.first { (key, value) -> key == "playlist:p1" && ids(value as Playlist) == "b a" } } }
        playlists.remove(page, listOf(0))
        assertEquals(Outcome.UNCONFIRMED, said.await().outcome)
        settled()
        // Done once, and only once: the other copy of "a" is still there.
        assertEquals(1, server.count("remove"))
        assertEquals("b a", server.now("p1"))
        // The playlist was read again: what is shown is what the server has.
        readAgain.await()
    }

    @Test
    fun aRefusedChangeTakesTheOnesBehindItWithIt() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        server.trouble["remove"] = Trouble.REFUSE
        val said = listen()
        playlists.remove(page, listOf(0))
        reached.await()
        // Two more made while the first is on its way, from the page as it shows.
        val shown = playlists.shown(pages.kept(spec("p1"))!!)
        playlists.move(shown, 0, 2)
        playlists.add(shown, songs("z"))
        open.complete(Unit)
        val refusal = said.await()
        assertEquals(Outcome.REFUSED, refusal.outcome)
        settled()
        // Neither of the others was sent.
        assertEquals(0, server.count("replace"))
        assertEquals(0, server.count("add"))
        assertEquals("a b c d e", server.now("p1"))
    }

    @Test
    fun undoOfAChangeThatDidNotHappenSendsNothing() = runBlocking<Unit> {
        val page = opened()
        server.trouble["remove"] = Trouble.REFUSE
        val said = listen()
        playlists.remove(page, listOf(1))
        said.await()
        settled()
        val asked = server.asked.size
        assertFalse(playlists.undoLast(page))
        assertEquals(asked, server.asked.size)
    }

    @Test
    fun undoWaitsBehindWhatItUndoesAndGoesWithItIfThatFails() = runBlocking<Unit> {
        val page = opened()
        val (reached, open) = server.hold("remove")
        server.trouble["remove"] = Trouble.LOSE_ANSWER
        val said = listen()
        playlists.remove(page, listOf(1, 2))
        reached.await()
        // Undo pressed while the removal is still on its way: it waits behind it.
        assertTrue(playlists.undoLast(page))
        open.complete(Unit)
        assertEquals(Outcome.UNCONFIRMED, said.await().outcome)
        settled()
        assertEquals("the undo was never sent", 0, server.count("replace"))
    }

    @Test
    fun undoPutsRemovedSongsBackBesideTheirNeighboursAsThePlaylistNowIs() = runBlocking<Unit> {
        val page = opened()
        playlists.remove(page, listOf(1, 2))
        settled()
        assertEquals("a d e", server.now("p1"))
        server.elsewhere("p1", "a x d e")
        assertTrue(playlists.undoLast(page))
        settled()
        assertEquals("a b c x d e", server.now("p1"))
    }

    @Test
    fun aPlaylistGoneFromTheServerGoesFromThePhone() = runBlocking<Unit> {
        pages.fresh(listSpec())
        val page = opened()
        server.lists.remove("p1")
        val said = listen()
        playlists.remove(page, listOf(0))
        assertEquals(70L, (said.await().error as ApiError.Server).code)
        settled()
        withTimeout(5_000) { playlists.gone.first { "p1" in it } }
        assertNull(pages.kept(spec("p1")))
        assertTrue(pages.kept(listSpec())!!.none { it.id == "p1" })
    }

    // ---- Whose it is -------------------------------------------------------------------------

    @Test
    fun onlyAPlaylistKnownToBeTheAccountsOwnIsChangedCheckedAgainAsItIsSent() = runBlocking<Unit> {
        assertTrue(playlists.canEdit(Playlist(id = "p1", owner = "tester")))
        assertFalse(playlists.canEdit(Playlist(id = "p9", owner = "guest")))
        assertFalse(playlists.canEdit(Playlist(id = "p1", owner = "tester", readonly = true)))
        assertEquals(Unavailable.NOT_YOURS, playlists.remove(Playlist(id = "p9", owner = "guest", songs = songs("a")), listOf(0)))
        // Made while it was the account's; given to someone else on the server before it was
        // sent: refused as it is read, nothing written.
        val page = opened()
        server.owners["p1"] = "guest"
        val said = listen()
        playlists.remove(page, listOf(0))
        assertEquals(Outcome.REFUSED, said.await().outcome)
        settled()
        assertEquals(0, server.count("remove"))
    }

    @Test
    fun theWarningBeforeAddingNamesTheSongsAlreadyThereAsTheServerHasThemNow() = runBlocking<Unit> {
        opened()
        server.elsewhere("p1", "a b c d e f")
        assertEquals(setOf("f"), playlists.alreadyIn("p1", songs("f g")))
        assertEquals(null, playlists.alreadyIn("p9", songs("g")))
    }

    @Test
    fun aNewPlaylistAndARenameAndADeleteGoOnce() = runBlocking<Unit> {
        val made = CompletableDeferred<Boolean>()
        assertNull(playlists.create("Road trip", songs("a b")) { made.complete(it) })
        assertTrue(made.await())
        assertEquals("Road trip", server.names["p2"])
        val page = opened()
        playlists.rename(page, "Morning")
        settled()
        assertEquals("Morning", server.names["p1"])
        playlists.delete(page)
        settled()
        assertFalse("p1" in server.lists)
        assertEquals(1, server.count("create"))
        assertEquals(1, server.count("delete"))
    }
}
