package org.medianexus.music.ui.lists

import org.junit.Assert.assertEquals
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist

/** An artist page's Play plays what the page drew, in the order drawn (M3b fixes 2, C). */
class ArtistHeaderTest {
    @Test
    fun theHeaderPlaysTheAlbumsAsThePageDrawsThemAlbumsThenSingles() {
        val artist = Artist(
            id = "ar1", name = "Juniper Vale",
            albums = listOf(
                Album(id = "single", name = "New Single", year = 2024, releaseTypes = listOf("Single")),
                Album(id = "old", name = "Slow Rivers", year = 2018),
                Album(id = "new", name = "Harbour", year = 2022),
                Album(id = "ep", name = "An EP", year = 2020, releaseTypes = listOf("EP")),
            ),
        )
        // Drawn: Albums (newest first), then Singles and EPs (newest first). It used to play
        // them all by year, the single first.
        assertEquals(listOf("new", "old", "single", "ep"), playedByHeader(artist).map { it.id })
    }
}
