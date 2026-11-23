package org.medianexus.music.ui.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

@OptIn(ExperimentalCoroutinesApi::class)
class SearchModelTest {
    private fun found(word: String) = Found(songs = listOf(Song(id = word, title = word)))

    @Test
    fun waitsForTheTypingToPauseThenSearchesOnce() = runTest {
        val asked = mutableListOf<String>()
        val scope = backgroundScope
        val model = SearchModel(scope, search = { asked += it; found(it) })
        runCurrent()
        for (word in listOf("l", "la", "lan", "lant")) {
            model.type(word)
            advanceTimeBy(100)
        }
        assertTrue(asked.isEmpty())
        advanceTimeBy(SearchModel.WAIT_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("lant"), asked)
        assertEquals(SearchState("lant", found("lant")), model.state.value)
    }

    @Test
    fun theLatestWordsWinOverASlowerEarlierSearch() = runTest {
        val slow = CompletableDeferred<Found>()
        val model = SearchModel(backgroundScope, search = { if (it == "old") slow.await() else found(it) })
        runCurrent()
        model.type("old")
        advanceTimeBy(SearchModel.WAIT_MILLIS + 1)
        runCurrent()
        assertTrue(model.state.value.searching)
        model.type("new")
        advanceTimeBy(SearchModel.WAIT_MILLIS + 1)
        runCurrent()
        // The old answer arriving late changes nothing.
        slow.complete(found("old"))
        advanceUntilIdle()
        assertEquals(SearchState("new", found("new")), model.state.value)
    }

    @Test
    fun clearingTheBoxIsShownAtOnceAndAFailureKeepsWhatWasFound() = runTest {
        var fail = false
        val model = SearchModel(backgroundScope, search = { if (fail) throw ApiError.Network("Gone.") else found(it) })
        runCurrent()
        model.type("lantern")
        advanceTimeBy(SearchModel.WAIT_MILLIS + 1)
        runCurrent()
        fail = true
        model.type("lanterns")
        advanceTimeBy(SearchModel.WAIT_MILLIS + 1)
        runCurrent()
        assertEquals(found("lantern"), model.state.value.found)
        assertFalse(model.state.value.searching)
        assertTrue(model.state.value.error is ApiError.Network)
        model.type("")
        runCurrent()
        assertEquals(SearchState(), model.state.value)
        assertNull(model.state.value.found)
        backgroundScope.cancel()
    }

    @Test
    fun anArtistWhoMatchesBetterThanAnySongGoesFirst() {
        val found = Found(
            artists = listOf(Artist(id = "a", name = "Paper Lanterns")),
            albums = listOf(Album(id = "b", name = "First Light")),
            songs = (1..6).map { Song(id = "s$it", title = "Lantern Season $it") },
        )
        val all = SearchView.of("paper lanterns", found, listOf(Playlist(id = "p", name = "Paper trail")), SearchKind.ALL)
        assertTrue(all.artistsFirst)
        assertEquals(SearchView.TOP_SONGS, all.songs.size)
        assertTrue(all.moreSongs)
        val songsFirst = SearchView.of("lantern season", found, emptyList(), SearchKind.ALL)
        assertFalse(songsFirst.artistsFirst)
        // A chip shows that kind in full and nothing else.
        val songs = SearchView.of("lantern", found, emptyList(), SearchKind.SONGS)
        assertEquals(6, songs.songs.size)
        assertTrue(songs.artists.isEmpty() && songs.albums.isEmpty())
        assertEquals(listOf("p"), SearchView.of("paper trail", found, listOf(Playlist(id = "p", name = "Paper trail")), SearchKind.PLAYLISTS).playlists.map { it.id })
    }
}
