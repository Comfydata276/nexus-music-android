package org.medianexus.music.ui.lists

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.R
import org.medianexus.music.library.Arrange
import org.medianexus.music.library.ListView
import org.medianexus.music.ui.parts.initialOf

/** What the Songs page and long lists say of their order, their dates and their handle. */
class SongsPageTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = java.time.ZonedDateTime.of(2026, 11, 24, 15, 0, 0, 0, zone).toEpochSecond()

    @Test
    fun theHeaderSaysTheOrderShown() {
        // It said "newest first" whatever the order was.
        assertEquals(R.string.order_newest_first, orderWords(ListView(), R.string.order_newest_first))
        assertEquals(R.string.order_by_title, orderWords(ListView(ListView.TITLE), R.string.order_newest_first))
        assertEquals(R.string.order_by_title_reversed, orderWords(ListView(ListView.TITLE, descending = true), R.string.order_newest_first))
        assertEquals(R.string.order_by_artist, orderWords(ListView(ListView.ARTIST), R.string.order_newest_first))
        assertEquals(R.string.order_by_album_reversed, orderWords(ListView(ListView.ALBUM, descending = true), R.string.order_newest_first))
        assertEquals(R.string.order_shortest_first, orderWords(ListView(ListView.DURATION), R.string.order_newest_first))
        assertEquals(R.string.order_longest_first, orderWords(ListView(ListView.DURATION, descending = true), R.string.order_newest_first))
        assertEquals(R.string.order_oldest_first, orderWords(ListView(ListView.ADDED, descending = false), R.string.order_newest_first))
    }

    @Test
    fun aSongSortedByDateAddedShowsItsDateAsWindowsWritesIt() {
        fun label(daysAgo: Long) = Arrange.dateLabel(now - daysAgo * 86_400, "Today", "Yesterday", now, zone, Locale.UK)
        assertEquals("Today", label(0))
        assertEquals("Yesterday", label(1))
        assertEquals("3 Mar 2024", Arrange.dateLabel(java.time.ZonedDateTime.of(2024, 3, 3, 12, 0, 0, 0, zone).toEpochSecond(), "Today", "Yesterday", now, zone, Locale.UK))
        assertEquals("", Arrange.dateLabel(0, "Today", "Yesterday", now, zone, Locale.UK))
    }

    @Test
    fun theHandlesBubbleSaysWhereInTheList() {
        assertEquals("Mar 2024", Arrange.monthOf(java.time.ZonedDateTime.of(2024, 3, 3, 12, 0, 0, 0, zone).toEpochSecond(), zone, Locale.UK))
        assertNull(Arrange.monthOf(0, zone, Locale.UK))
        assertEquals("E", initialOf("Écoute"))
        assertEquals("T", initialOf("The Quiet Harbour"))
        assertEquals("Ж", initialOf("жёлтый"))
        assertEquals("夜", initialOf("夜の散歩"))
        assertEquals("#", initialOf("2 Minutes"))
        assertEquals("#", initialOf("  "))
        assertEquals("𠮷", initialOf("𠮷野家"))
    }
}
