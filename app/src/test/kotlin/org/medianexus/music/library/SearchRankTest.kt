package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/** Ported with the Windows app's tests of src/search.rs. */
class SearchRankTest {
    private val songs = listOf(
        Song(id = "1", title = "Psycho", artist = "Maisie Peters", album = "You Signed Up for This"),
        Song(id = "2", title = "Sweet but Psycho", artist = "Ava Max", album = "Heaven & Hell"),
        Song(id = "3", title = "Psychosocial", artist = "Slipknot", album = "All Hope Is Gone"),
        Song(id = "4", title = "Psycho", artist = "Taylor Acorn", album = "Psycho"),
        Song(id = "5", title = "Lost the Breakup", artist = "Maisie Peters", album = "The Good Witch"),
        Song(id = "6", title = "Déjà Vu", artist = "Beyoncé", album = "B'Day"),
        Song(id = "7", title = "Don't Stop Me Now", artist = "Queen", album = "Jazz"),
        Song(id = "8", title = "Masterpiece", artist = "Madonna", album = "MDNA"),
    )
    private val artists = listOf("Maisie Peters", "Ava Max", "Beyoncé", "Queen").map { Artist(id = it, name = it) }

    /** What a search finds: here, everything that matches at all, as the server would. */
    private fun found(query: String): Pair<List<Song>, List<Artist>> {
        val typed = SearchRank.words(query)
        val s = songs.filter { SearchRank.rate(typed, SearchRank.words(it.title), listOf(SearchRank.words(it.artist) to 0.9f, SearchRank.words(it.album) to 0.8f)) != null }
        val a = artists.filter { SearchRank.rate(typed, SearchRank.words(it.name)) != null }
        return s to a
    }

    @Test
    fun foldsAccentsAndPunctuation() {
        assertEquals("beyonce   deja vu ", SearchRank.fold("Beyoncé — Déjà Vu!"))
        assertEquals("dont stop", SearchRank.fold("Don’t Stop"))
    }

    @Test
    fun countsSlips() {
        assertEquals(1, SearchRank.slips("masie", "maisie", 2))
        assertEquals(1, SearchRank.slips("pyscho", "psycho", 2))
        assertEquals(0, SearchRank.slips("queen", "queen", 1))
        assertEquals(2, SearchRank.slips("abc", "xyz", 1))
    }

    @Test
    fun shortWordsOnlyMatchWordsThatStartTheSameWay() {
        assertEquals(0f, SearchRank.likeness("home", "someday"))
        assertEquals(0f, SearchRank.likeness("home", "come"))
        assertEquals(0f, SearchRank.likeness("lost", "most"))
        assertTrue(SearchRank.likeness("masi", "maisie") > 0f)
        assertTrue(SearchRank.likeness("quen", "queen") > 0f)
        assertTrue(SearchRank.likeness("jaz", "jazz") > 0.8f)
        assertTrue(SearchRank.likeness("saisie", "maisie") > 0f)
    }

    @Test
    fun theArtistGoesFirstOnlyWhenTheBetterMatch() {
        // The misspelling from the Windows bug report: the artist leads.
        val (s1, a1) = found("masie")
        assertTrue(SearchRank.artistFirst("masie", s1, a1))
        // A song called exactly that: songs lead.
        val (s2, a2) = found("psycho")
        assertFalse(SearchRank.artistFirst("psycho", s2, a2))
        assertTrue(SearchRank.artistFirst("queen", found("queen").first, found("queen").second))
        // No artist found at all.
        assertFalse(SearchRank.artistFirst("masterpiece", found("masterpiece").first, emptyList()))
    }

    @Test
    fun playlistsMatchEveryWordByItsStart() {
        val lists = listOf("Morning mix", "Late night drive", "Everything (long list)", "Café Sessions").map { Playlist(id = it, name = it) }
        assertEquals(listOf("Morning mix"), SearchRank.playlistsMatching("morn", lists).map { it.name })
        assertEquals(listOf("Late night drive"), SearchRank.playlistsMatching("night late", lists).map { it.name })
        assertEquals(listOf("Café Sessions"), SearchRank.playlistsMatching("cafe sess", lists).map { it.name })
        // Every word must be there; the middle of a word isn't a start.
        assertEquals(emptyList<String>(), SearchRank.playlistsMatching("morning night", lists).map { it.name })
        assertEquals(emptyList<String>(), SearchRank.playlistsMatching("rning", lists).map { it.name })
        assertEquals(emptyList<String>(), SearchRank.playlistsMatching("  ", lists).map { it.name })
    }
}
