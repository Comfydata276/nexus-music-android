package org.medianexus.music.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Found
import org.medianexus.music.net.Song
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

class LikesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    private val a = Song(id = "a", title = "A")
    private val b = Song(id = "b", title = "B")

    private fun setUp(server: Found, send: suspend (String, Boolean) -> Unit): Pair<Likes, PageCache> {
        val pages = PageCache(folder.root, scope, Dispatchers.IO)
        val spec = PageSpec("liked", Found.serializer()) { server }
        return Likes(pages, spec, send, scope) to pages
    }

    private suspend fun Likes.waitFor(set: Set<String>) = withTimeout(2_000) { liked.first { it == set } }

    @Test
    fun followsWhatTheServerSays() = runBlocking {
        val (likes, _) = setUp(Found(songs = listOf(a))) { _, _ -> }
        likes.load()
        likes.waitFor(setOf("a"))
        assertTrue(likes.isLiked("a"))
    }

    @Test
    fun aLikeShowsAtOnceAndReachesKeptLikedSongs() = runBlocking {
        val sent = CompletableDeferred<Pair<String, Boolean>>()
        val answer = CompletableDeferred<Unit>()
        val (likes, pages) = setUp(Found(songs = listOf(a))) { id, liked ->
            sent.complete(id to liked)
            answer.await()
        }
        likes.heard(Found(songs = listOf(a)))
        pages.fresh(PageSpec("liked", Found.serializer()) { Found(songs = listOf(a)) })
        likes.set(b, true)
        // On screen before the server has answered.
        assertTrue(likes.isLiked("b"))
        assertEquals("b" to true, withTimeout(2_000) { sent.await() })
        answer.complete(Unit)
        // Kept Liked songs gains it, newest first.
        withTimeout(2_000) {
            while (pages.kept(PageSpec("liked", Found.serializer()) { Found() })?.songs?.map { it.id } != listOf("b", "a")) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun aRefusedLikeIsPutBackAndSaidSo() = runBlocking {
        val (likes, _) = setUp(Found()) { _, _ -> throw ApiError.Server(50, "not allowed") }
        val refusal = scope.async { likes.refused.first() }
        kotlinx.coroutines.delay(50)
        likes.toggle(a)
        assertTrue(likes.isLiked("a"))
        likes.waitFor(emptySet())
        assertFalse(likes.isLiked("a"))
        assertEquals(LikeRefused::class, withTimeout(2_000) { refusal.await() }::class)
    }

    @Test
    fun aFetchThatCrossesAChangeDoesNotUndoItOnScreen() = runBlocking {
        val answer = CompletableDeferred<Unit>()
        val (likes, _) = setUp(Found()) { _, _ -> answer.await() }
        likes.set(a, true)
        // The server's list, fetched before it heard about the like.
        likes.heard(Found())
        assertTrue(likes.isLiked("a"))
        answer.complete(Unit)
        likes.waitFor(setOf("a"))
        Unit
    }
}
