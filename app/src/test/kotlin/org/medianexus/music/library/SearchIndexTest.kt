package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Song

/** The tests of src/search.rs's `Index`, ported with it: the same library, the same words,
 *  the same answers. */
class SearchIndexTest {
    private fun song(id: String, title: String, artist: String, album: String) = Song(id = id, title = title, artist = artist, album = album)

    private val library = ListIndex(
        songs = listOf(
            song("1", "Psycho", "Maisie Peters", "You Signed Up for This"),
            song("2", "Sweet but Psycho", "Ava Max", "Heaven & Hell"),
            song("3", "Psychosocial", "Slipknot", "All Hope Is Gone"),
            song("4", "Psycho", "Taylor Acorn", "Psycho"),
            song("5", "Lost the Breakup", "Maisie Peters", "The Good Witch"),
            song("6", "Déjà Vu", "Beyoncé", "B'Day"),
            song("7", "Don't Stop Me Now", "Queen", "Jazz"),
            song("8", "Masterpiece", "Madonna", "MDNA"),
        ),
        albums = listOf(
            Album(id = "The Good Witch", name = "The Good Witch", artist = "Maisie Peters"),
            Album(id = "Psycho", name = "Psycho", artist = "Taylor Acorn"),
            Album(id = "Jazz", name = "Jazz", artist = "Queen"),
        ),
        artists = listOf("Maisie Peters", "Ava Max", "Beyoncé", "Queen").map { Artist(id = it, name = it) },
    )

    private val all = SearchLimits(artists = 5, albums = 5, songs = 10)

    private fun search(query: String, liked: (String) -> Boolean = { false }, limits: SearchLimits = all): Found = library.find(query, limits, liked)!!

    private fun titles(songs: List<Song>) = songs.map { "${it.title} / ${it.artist}" }

    @Test
    fun shortWordsOnlyMatchWordsThatStartTheSameWay() {
        // And through the whole search: "tazz" isn't taken for "Jazz", "masi" still finds Maisie.
        val tazz = search("tazz", limits = SearchLimits(5, 5, 5))
        assertTrue(titles(tazz.songs).toString(), tazz.songs.isEmpty() && tazz.albums.isEmpty())
        assertEquals("Maisie Peters", search("masi", limits = SearchLimits(5, 5, 5)).artists.firstOrNull()?.name)
    }

    @Test
    fun forgivesATypoAndPutsTheArtistFirst() {
        // The misspelling from the bug report.
        val found = search("masie")
        assertEquals("Maisie Peters", found.artists[0].name)
        assertEquals(true, found.artistFirst)
        // Her songs come before one whose title merely starts something like it.
        assertEquals(listOf("Psycho / Maisie Peters", "Lost the Breakup / Maisie Peters", "Masterpiece / Madonna"), titles(found.songs))
        assertEquals("The Good Witch", found.albums[0].name)
        // Still being typed, and already wrong.
        assertEquals("Maisie Peters", search("masi").artists[0].name)
        // Accents and apostrophes needn't be typed.
        assertEquals("Beyoncé", search("beyonce").artists[0].name)
        assertEquals("Don't Stop Me Now", search("dont stop").songs[0].title)
        // Short words have to be right: "mas" is the start of "Masterpiece", not a typo for "Max".
        assertEquals(listOf("Masterpiece / Madonna"), titles(search("mas").songs))
        assertTrue(search("zzzz").songs.isEmpty())
    }

    @Test
    fun ranksCloserTitlesAndLikedSongsFirst() {
        val found = search("psycho")
        // A song called exactly that beats ones that merely contain or start with it.
        assertEquals(
            listOf("Psycho / Maisie Peters", "Psycho / Taylor Acorn", "Psychosocial / Slipknot", "Sweet but Psycho / Ava Max"),
            titles(found.songs),
        )
        // No artist is called anything like it, so songs lead.
        assertEquals(false, found.artistFirst)
        // Of equal matches, the liked one comes first.
        assertEquals("Taylor Acorn", search("psycho", liked = { it == "4" }).songs[0].artist)
        // Words can come from the title and the artist together.
        assertEquals(listOf("Psycho / Maisie Peters"), titles(search("psycho maisie").songs))
    }

    // ---- Beyond Windows' tests: what the phone asks of the same index ------------------------

    @Test
    fun keepsOnlyAsManyAsAskedForBestFirst() {
        assertEquals(listOf("Psycho / Maisie Peters", "Psycho / Taylor Acorn"), titles(search("psycho", limits = SearchLimits(1, 1, 2)).songs))
        assertTrue(search("psycho", limits = SearchLimits(0, 0, 0)).songs.isEmpty())
        // Nothing typed, nothing found.
        assertTrue(search("  ").songs.isEmpty())
        assertTrue(search("—!").artists.isEmpty())
    }

    @Test
    fun aSearchReplacedPartWayGivesNoAnswer() {
        var asked = 0
        assertNull(library.find("psycho", all, cancelled = { ++asked > 1 }))
    }

    @Test
    fun leavesOutWhatIsNotAllowed() {
        val index = SearchIndex.Builder().apply {
            song("Psycho", "Maisie Peters", "You Signed Up for This")
            song("Psycho", "Taylor Acorn", "Psycho")
            artist("Maisie Peters")
            album("Psycho", "Taylor Acorn")
        }.build()
        val hits = index.search("psycho", { false }, 5, 5, 5, songAllowed = { it == 1 }, albumAllowed = { false })!!
        assertEquals(listOf(1), hits.songs.toList())
        assertTrue(hits.albums.isEmpty())
        assertFalse(hits.artistFirst)
    }

    @Test
    fun equalMatchesKeepTheLibrarysOrder() {
        val songs = (1..30).map { song("$it", "Same name", "Someone", "Somewhere") }
        val found = ListIndex(songs, emptyList(), emptyList()).find("same name", SearchLimits(0, 0, 12))!!
        assertEquals((1..12).map { "$it" }, found.songs.map { it.id })
    }

    @Test
    fun aNameSharedByManySongsIsOneName() {
        // One artist on 2,000 songs, each with a title of its own: found by the artist's name
        // in any of them, as by their own.
        val songs = (1..2000).map { song("$it", "Track number $it", "Paper Lanterns", "First Light") }
        val index = ListIndex(songs, emptyList(), emptyList())
        val found = index.find("lanterns", SearchLimits(0, 0, 3))!!
        assertEquals(listOf("1", "2", "3"), found.songs.map { it.id })
        assertEquals(listOf("1234"), index.find("track number 1234", SearchLimits(0, 0, 1))!!.songs.map { it.id })
    }
}
