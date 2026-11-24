package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.net.Song

/** Songs suggested for a playlist, as Windows' `playlist_extras` picks them. */
class RecommendTest {
    private fun song(id: String, artist: String = "A$id", title: String = "T$id", genre: String = "") = Song(id = id, title = title, artist = artist, artistId = artist, genre = genre)

    @Test
    fun aSongFromEachSeedsListInTurnNeverOneAlreadyThereNorMoreThanTwoByOneArtist() {
        val playlist = listOf(song("p1", title = "Lantern Season", artist = "Paper Lanterns"), song("p2"))
        val similar = listOf(
            listOf(song("x1", artist = "Same"), song("x2", artist = "Same"), song("x3", artist = "Same"), song("p2")),
            listOf(song("y1"), song("lantern", title = "lantern season", artist = "paper lanterns")),
        )
        val picks = Recommend.pick(playlist, similar, emptyList())!!
        // In turn: x1, y1, x2, then x3 (a third by "Same") and p2 (already there) and the same
        // song from another album are passed over.
        assertEquals(listOf("x1", "y1", "x2"), picks.songs.map { it.id })
        assertEquals(Recommend.Basis.SIMILAR, picks.basis)
    }

    @Test
    fun withNoSimilarSongsTheKindOfMusicTheListIsMostlyMadeOf() {
        val playlist = listOf(song("p1", genre = "Folk"), song("p2", genre = "Folk"), song("p3", genre = "Jazz"))
        assertEquals("Folk", Recommend.genreOf(playlist))
        val picks = Recommend.pick(playlist, listOf(emptyList()), List(20) { song("g$it", genre = "Folk") })!!
        assertEquals(Recommend.SUGGESTED, picks.songs.size)
        assertEquals(Recommend.Basis.GENRE, picks.basis)
        assertNull(Recommend.pick(playlist, listOf(emptyList()), emptyList()))
    }
}
