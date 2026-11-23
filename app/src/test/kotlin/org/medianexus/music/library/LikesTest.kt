package org.medianexus.music.library

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Album
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Found
import org.medianexus.music.net.Song
import org.medianexus.music.net.StarTarget
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/**
 * Every test here decides when the server answers (each send waits on a channel or gate the
 * test opens), so none depends on how quickly anything runs.
 */
class LikesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_000L

    @After
    fun stop() = scope.cancel()

    private val a = Song(id = "a", title = "A")
    private val b = Song(id = "b", title = "B")

    /** The server's Liked songs, as the test sets it. */
    @Volatile private var server = Found()

    private val likedSpec get() = PageSpec("liked", Found.serializer()) { server }

    private fun setUp(send: suspend (StarTarget, String, Boolean) -> Unit): Pair<Likes, PageCache> {
        val pages = PageCache(folder.root, scope, Dispatchers.IO, clock = { now })
        return Likes(pages, likedSpec, send, scope, clock = { now }) to pages
    }

    private suspend fun Likes.waitFor(set: Set<String>) = withTimeout(5_000) { liked.first { it == set } }

    @Test
    fun followsWhatTheServerSays() = runBlocking {
        server = Found(songs = listOf(a))
        val (likes, _) = setUp { _, _, _ -> }
        likes.load()
        likes.waitFor(setOf("a"))
        assertTrue(likes.isLiked("a"))
    }

    @Test
    fun aLikeShowsAtOnceEverywhereAndReachesKeptLikedSongs() = runBlocking {
        server = Found(songs = listOf(a))
        val answer = CompletableDeferred<Unit>()
        val sent = CompletableDeferred<Triple<StarTarget, String, Boolean>>()
        val (likes, pages) = setUp { target, id, liked ->
            sent.complete(Triple(target, id, liked))
            answer.await()
        }
        pages.fresh(likedSpec)
        likes.heard(Found(songs = listOf(a)))
        likes.set(b, true)
        // Before the server has answered: the heart, and Liked songs, newest first.
        assertTrue(likes.isLiked("b"))
        assertEquals(listOf("b", "a"), likes.songsOf(pages.kept(likedSpec))!!.map { it.id })
        assertEquals(Triple(StarTarget.SONG, "b", true), withTimeout(5_000) { sent.await() })
        answer.complete(Unit)
        // Kept Liked songs gains it once the server has it.
        withTimeout(5_000) {
            while (pages.kept(likedSpec)?.songs?.map { it.id } != listOf("b", "a")) kotlinx.coroutines.delay(10)
        }
        assertEquals(listOf("b", "a"), likes.songsOf(pages.kept(likedSpec))!!.map { it.id })
    }

    @Test
    fun aRefusedLikeIsPutBackAndSaidSo() = runBlocking {
        val answer = CompletableDeferred<Unit>()
        val (likes, _) = setUp { _, _, _ -> answer.await(); throw ApiError.Server(50, "not allowed") }
        val refusal = scope.async(start = CoroutineStart.UNDISPATCHED) { likes.refused.first() }
        likes.toggle(a)
        // Shown at once, while the server thinks about it.
        assertTrue(likes.isLiked("a"))
        answer.complete(Unit)
        val refused = withTimeout(5_000) { refusal.await() }
        assertEquals("a", refused.id)
        assertTrue(refused.liked)
        assertFalse(likes.isLiked("a"))
        assertEquals(emptyList<Song>(), likes.songsOf(Found()))
    }

    @Test
    fun quickTapsGoOneAtATimeInOrderAndSettleOnTheLast() = runBlocking {
        // Each answer is let through by the test, the first one last of all.
        val answers = Channel<Unit>(Channel.UNLIMITED)
        val sent = Collections.synchronizedList(mutableListOf<Boolean>())
        val (likes, _) = setUp { _, _, liked -> sent += liked; answers.receive() }
        likes.set(a, true)
        likes.set(a, false)
        likes.set(a, true)
        assertTrue(likes.isLiked("a"))
        // Only the first is on its way: the next waits for its answer.
        withTimeout(5_000) { while (sent.size < 1) kotlinx.coroutines.delay(5) }
        kotlinx.coroutines.delay(50)
        assertEquals(listOf(true), sent.toList())
        repeat(3) { answers.send(Unit) }
        likes.waitFor(setOf("a"))
        withTimeout(5_000) { while (sent.size < 3) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(true, false, true), sent.toList())
        assertTrue(likes.isLiked("a"))
    }

    @Test
    fun aSlowRefreshThatCrossesAnUnlikeDoesNotBringItBack() = runBlocking {
        server = Found(songs = listOf(a, b))
        val asked = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val (likes, pages) = setUp { _, _, _ -> }
        likes.load()
        likes.waitFor(setOf("a", "b"))
        // (The first fetch is over: what follows is a request of its own, not one joined.)
        pages.fresh(likedSpec)
        // Liked songs is refreshed, slowly: its answer still has "b".
        val slow = PageSpec("liked", Found.serializer()) { asked.complete(Unit); gate.await(); Found(songs = listOf(a, b)) }
        now += 1_000
        val refresh = scope.async { pages.fresh(slow) }
        withTimeout(5_000) { asked.await() }
        // Meanwhile "b" is unliked, and the server takes it.
        now += 1_000
        likes.set(b, false)
        likes.waitFor(setOf("a"))
        withTimeout(5_000) { while (pages.kept(likedSpec)?.songs?.map { it.id } != listOf("a")) kotlinx.coroutines.delay(10) }
        now += 1_000
        gate.complete(Unit)
        refresh.await()
        kotlinx.coroutines.delay(100)
        // The answer was asked for before the unlike: "b" stays unliked, everywhere.
        assertFalse(likes.isLiked("b"))
        assertEquals(listOf("a"), likes.songsOf(pages.kept(likedSpec))!!.map { it.id })
    }

    @Test
    fun aLikeMadeOnAnotherDeviceShowsEverywhereAfterARefresh() = runBlocking {
        server = Found(songs = listOf(a))
        val (likes, pages) = setUp { _, _, _ -> }
        likes.load()
        likes.waitFor(setOf("a"))
        // Liked on the computer: an album page fetched afresh says so...
        now += 1_000
        pages.fresh(PageSpec("album:1", Album.serializer()) { Album(id = "1", songs = listOf(b.copy(starred = "2026-01-01T00:00:00Z"))) })
        likes.waitFor(setOf("a", "b"))
        // ...and so does Liked songs, when it's fetched again.
        server = Found(songs = listOf(b, a), albums = listOf(Album(id = "9")))
        now += 1_000
        pages.fresh(likedSpec)
        withTimeout(5_000) { likes.state.first { "9" in it.albums } }
        assertEquals(listOf("b", "a"), likes.songsOf(pages.kept(likedSpec))!!.map { it.id })
        // Unliked there: a fresh Liked songs takes it away here.
        server = Found(songs = listOf(a))
        now += 1_000
        pages.fresh(likedSpec)
        likes.waitFor(setOf("a"))
        assertFalse(likes.isAlbumLiked("9"))
    }

    @Test
    fun albumLikesGoThroughTheSameOwner() = runBlocking {
        val answer = CompletableDeferred<Unit>()
        val sent = CompletableDeferred<Triple<StarTarget, String, Boolean>>()
        val (likes, _) = setUp { target, id, liked -> sent.complete(Triple(target, id, liked)); answer.await() }
        val album = Album(id = "al-1", name = "First Light")
        likes.toggleAlbum(album)
        assertTrue(likes.isAlbumLiked("al-1"))
        assertEquals(Triple(StarTarget.ALBUM, "al-1", true), withTimeout(5_000) { sent.await() })
        answer.complete(Unit)
        // An album page from before, saying it isn't, doesn't undo it.
        likes.heard(Found(), askedAt = 0)
        assertTrue(likes.isAlbumLiked("al-1"))
    }
    // ---- Taps that overtake one another ----------------------------------------------------

    @Test
    fun aRefusalOfAnEarlierTapLeavesTheLaterOnesAlone() = runBlocking {
        server = Found()
        // Each answer is the test's to give: the first refused, the rest taken.
        val answers = Channel<Boolean>(Channel.UNLIMITED)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val (likes, pages) = setUp { _, _, _ -> calls.incrementAndGet(); if (!answers.receive()) throw ApiError.Server(50, "not allowed") }
        pages.fresh(likedSpec)
        val refusals = Collections.synchronizedList(mutableListOf<LikeRefused>())
        val listening = scope.async(start = CoroutineStart.UNDISPATCHED) { likes.refused.collect { refusals += it } }
        likes.set(a, true)
        likes.set(a, false)
        likes.set(a, true)
        // The first is refused while the other two are still to go.
        answers.send(false)
        // The last tap's like still shows, at the head of Liked songs, and nothing is said
        // about a tap that has been overtaken.
        // (The second tap is sent only once the first has been dealt with.)
        withTimeout(5_000) { while (calls.get() < 2) kotlinx.coroutines.delay(5) }
        assertTrue(likes.isLiked("a"))
        assertEquals(listOf("a"), likes.songsOf(pages.kept(likedSpec))!!.map { it.id })
        answers.send(true)
        answers.send(true)
        withTimeout(5_000) { while (pages.kept(likedSpec)?.songs?.map { it.id } != listOf("a")) kotlinx.coroutines.delay(5) }
        assertTrue(likes.isLiked("a"))
        assertTrue(refusals.isEmpty())
        listening.cancel()
    }

    @Test
    fun keptLikedSongsFollowsWhatTheServerConfirmedInOrder() = runBlocking {
        server = Found()
        val answers = Channel<Boolean>(Channel.UNLIMITED)
        val (likes, pages) = setUp { _, _, _ -> if (!answers.receive()) throw ApiError.Server(50, "not allowed") }
        pages.fresh(likedSpec)
        likes.set(a, true)
        likes.set(a, false)
        // The like is taken, the unlike refused: the server has it liked, and so does the
        // kept Liked songs.
        answers.send(true)
        answers.send(false)
        likes.waitFor(setOf("a"))
        withTimeout(5_000) { while (pages.kept(likedSpec)?.songs?.map { it.id } != listOf("a")) kotlinx.coroutines.delay(5) }
        assertEquals(listOf("a"), likes.songsOf(pages.kept(likedSpec))!!.map { it.id })
    }

    @Test
    fun aSongFoundLikedOnAnotherPageJoinsLikedSongsAtOnce() = runBlocking {
        server = Found(songs = listOf(a))
        val (likes, pages) = setUp { _, _, _ -> }
        likes.load()
        likes.waitFor(setOf("a"))
        now += 1_000
        val liked = b.copy(starred = "2026-01-01T00:00:00Z", album = "Second Wind")
        pages.fresh(PageSpec("album:1", Album.serializer()) { Album(id = "1", songs = listOf(liked)) })
        likes.waitFor(setOf("a", "b"))
        // In the list, with what the album page knew of it, before Liked songs is asked again.
        val shown = likes.songsOf(pages.kept(likedSpec))!!
        assertEquals(setOf("a", "b"), shown.map { it.id }.toSet())
        assertEquals("Second Wind", shown.first { it.id == "b" }.album)
    }

    @Test
    fun aLongPlaylistIsTakenInOneGo() = runBlocking {
        val many = (1..20_000).map { Song(id = "s$it", starred = "2026-01-01T00:00:00Z") }
        server = Found()
        val (likes, pages) = setUp { _, _, _ -> }
        likes.load()
        pages.fresh(likedSpec)
        now += 1_000
        val started = System.nanoTime()
        pages.fresh(PageSpec("playlist:long", org.medianexus.music.net.Playlist.serializer()) { org.medianexus.music.net.Playlist(id = "long", songs = many) })
        withTimeout(10_000) { while (likes.state.value.songs.size != many.size) kotlinx.coroutines.delay(5) }
        // One pass over the songs, not a copy of the set for each (20,000 copies of 20,000).
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 1_000_000_000L)
    }

    @Test
    fun likedSongsStartedByNameIsTheListAsShown() = runBlocking {
        server = Found(songs = listOf(a))
        val answer = CompletableDeferred<Unit>()
        val (likes, pages) = setUp { _, _, _ -> answer.await() }
        val library = Library(org.medianexus.music.net.Subsonic("http://127.0.0.1:1", "u", "p", okhttp3.OkHttpClient(), org.medianexus.music.net.OfflineSwitch()), pages)
        library.likedShown = likes::songsOf
        pages.fresh(likedSpec)
        likes.heard(Found(songs = listOf(a)))
        // "b" liked, still on its way to the server; "a" unliked, the same.
        likes.set(b, true)
        likes.set(a, false)
        // What the player reads, starting Liked songs by name, is what the page shows.
        assertEquals(listOf("b"), library.keptSongs("liked")!!.map { it.id })
        assertEquals(likes.songsOf(pages.kept(likedSpec)), library.keptSongs("liked"))
        answer.complete(Unit)
        Unit
    }

    @Test
    fun theAlbumHeartActsOnTheLikesAsTheyStand() = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<Boolean>())
        val (likes, _) = setUp { _, _, liked -> sent += liked }
        val album = Album(id = "al-1", name = "First Light")
        // Two taps before anything is drawn again: two changes.
        assertTrue(likes.toggleAlbum(album))
        assertFalse(likes.toggleAlbum(album))
        withTimeout(5_000) { while (sent.size < 2) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(true, false), sent.toList())
    }
}
