package org.medianexus.music.library

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song
import org.medianexus.music.store.ListPrefs

class ListViewTest {
    private val songs = listOf(
        Song(id = "a", title = "beta", artist = "Zoë", album = "Second", discNumber = 1, track = 2, duration = 300),
        Song(id = "b", title = "Alpha", artist = "adam", album = "second", discNumber = 1, track = 1, duration = 100),
        Song(id = "c", title = "Gamma", artist = "Adam", album = "First", discNumber = 2, track = 1, duration = 200),
        Song(id = "d", title = "delta", artist = "Bea", displayArtist = "Bea & Cai", album = "First", discNumber = 1, track = 5, duration = 200),
    )

    private fun shown(view: ListView, added: (Int) -> Long = { 0 }) = Arrange.arrange(songs.columns(), view, added).map { songs[it].id }

    @Test
    fun sortsAsWindowsDoes() {
        assertEquals(listOf("a", "b", "c", "d"), shown(ListView()))
        // Whatever the case.
        assertEquals(listOf("b", "a", "d", "c"), shown(ListView(ListView.TITLE)))
        assertEquals(listOf("c", "d", "a", "b"), shown(ListView(ListView.TITLE, descending = true)))
        // The artist as shown ("Bea & Cai"); two of the same name keep the list's order.
        assertEquals(listOf("b", "c", "d", "a"), shown(ListView(ListView.ARTIST)))
        // An album, then its discs and tracks.
        assertEquals(listOf("d", "c", "b", "a"), shown(ListView(ListView.ALBUM)))
        // A tie keeps the list's order; reversed, the whole of it is, ties too.
        assertEquals(listOf("b", "c", "d", "a"), shown(ListView(ListView.DURATION)))
        assertEquals(listOf("a", "d", "c", "b"), shown(ListView(ListView.DURATION, descending = true)))
        val added = longArrayOf(30, 10, 0, 20)
        assertEquals(listOf("a", "d", "b", "c"), shown(ListView(ListView.ADDED, descending = true)) { added[it] })
    }

    @Test
    fun findsWhatWasTypedInTheTitleArtistOrAlbum() {
        assertEquals(listOf("b", "c"), shown(ListView(filter = "ADAM")))
        assertEquals(listOf("c", "d"), shown(ListView(filter = " first ")))
        assertEquals(listOf("d"), shown(ListView(filter = "& cai")))
        assertEquals(listOf("a"), shown(ListView(filter = "zoë")))
        assertTrue(shown(ListView(filter = "nothing like it")).isEmpty())
        // Found and sorted at once.
        assertEquals(listOf("d", "c"), shown(ListView(ListView.TITLE, descending = false, filter = "first")))
    }

    @Test
    fun choosingTheSameOrderAgainTurnsItRound() {
        val title = ListView().sortedBy(ListView.TITLE)
        assertEquals(ListView(ListView.TITLE, descending = false), title)
        assertEquals(ListView(ListView.TITLE, descending = true), title.sortedBy(ListView.TITLE))
        // Dates start newest first.
        assertEquals(ListView(ListView.ADDED, descending = true), title.sortedBy(ListView.ADDED))
        // The list's own order has no other way round.
        assertEquals(ListView(), ListView(ListView.ADDED, true).sortedBy(ListView.OWN).sortedBy(ListView.OWN))
        // What was typed stays.
        assertEquals("x", ListView(filter = "x").sortedBy(ListView.ALBUM).filter)
    }

    @Test
    fun aListOpensInTheOrderItWasLeftIn() {
        // A playlist nobody has sorted: the songs added most recently first.
        assertEquals(ListView(ListView.ADDED, true), ListView.opening("playlist:p", null))
        assertEquals(ListView(ListView.ADDED, true), ListView.opening("liked", ListPrefs(shuffle = true)))
        // The Songs page: its own order, newest first already.
        assertEquals(ListView(), ListView.opening("songs", null))
        // Chosen, kept: even the list's own order, once chosen.
        val chosen = ListView.remember(null, ListView(ListView.OWN), at = 5)
        assertEquals(ListView(), ListView.opening("playlist:p", chosen))
        assertEquals(5, chosen.at)
        assertTrue(chosen.sortChosen)
        assertEquals(ListView(ListView.ARTIST, true), ListView.opening("liked", ListView.remember(ListPrefs(shuffle = true), ListView(ListView.ARTIST, true), 6)))
        // A number from a later version, or a direction without an order, is read as it can be.
        assertEquals(ListView(ListView.ADDED, false), ListView.opening("songs", ListPrefs(sort = 99, sortChosen = true)))
        assertEquals(ListView(), ListView.opening("songs", ListPrefs(descending = true, sortChosen = true)))
        assertTrue(ListView.findable("songs") && ListView.findable("liked") && ListView.findable("playlist:x"))
        assertFalse(ListView.findable("album:x"))
    }

    @Test
    fun readsServerTimesAndDays() {
        assertEquals(86_400L, Arrange.parseTime("1970-01-02T00:00:00Z"))
        assertEquals(86_400L, Arrange.parseTime("1970-01-02T00:00:00.123456789Z"))
        assertEquals(0L, Arrange.parseTime(""))
        assertEquals(0L, Arrange.parseTime(null))
        assertEquals(0L, Arrange.parseTime("not a time"))
        val utc = ZoneId.of("UTC")
        assertEquals(86_400L, Arrange.dayStart(86_400L + 3_600, utc))
        assertEquals(0L, Arrange.dayStart(0, utc))
        // In Sydney the day began ten hours before midnight in Greenwich.
        assertEquals(86_400L - 36_000, Arrange.dayStart(86_400L, ZoneId.of("Australia/Sydney")))
    }
}
