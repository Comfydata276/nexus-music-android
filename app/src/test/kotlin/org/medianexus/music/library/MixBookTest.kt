package org.medianexus.music.library

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.net.Song
import org.medianexus.music.store.Played
import org.medianexus.music.store.SongTable

/** The mixes as the account keeps them (M5b fixes, item 8): what is liked, before Liked songs
 *  has been heard, is what each song itself says (Windows' `is_liked`). */
class MixBookTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private val now = 1_795_000_000L

    @Test
    fun beforeLikesAreKnownASongsOwnStarCounts() = runBlocking {
        // Ten songs played once, three months ago, liked: worth rediscovering, if liked.
        val songs = (1..10).map {
            Song(id = "s$it", title = "Song $it", playCount = 1, played = Instant.ofEpochSecond(now - 90 * Mixes.DAY).toString(), starred = "2026-01-01T00:00:00Z")
        }
        val table = SongTable.Builder().apply { songs.forEach { add(it) } }.build()
        val copy = MutableStateFlow<LibrarySnapshot?>(LibrarySnapshot(table, emptyList(), emptyList(), CopyStamp(10, "", now), complete = true))
        val liked = MutableStateFlow(emptySet<String>())
        val known = MutableStateFlow(false)
        val rules = MutableStateFlow(listOf(MixRules(id = 1, name = "Liked", liked = true)))
        val book = MixBook(scope, copy, liked, known, MutableStateFlow(emptyList<Played>()), rules, now = { now })
        // Offline with no kept Liked songs page: nothing heard of likes yet.
        val first = withTimeout(5_000) { book.state.first { it != null } }!!
        assertEquals(10, first.find("rediscover")?.size)
        assertEquals(10, first.find("rule:1")?.size)
        // Liked songs heard, and it lists none of them (unliked elsewhere): that is the word now.
        known.value = true
        val after = withTimeout(5_000) { book.state.first { it != null && it !== first } }!!
        assertNull(after.find("rediscover"))
        assertEquals(0, after.find("rule:1")?.size)
    }
}
