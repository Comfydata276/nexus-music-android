package org.medianexus.music.ui.lists

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist's song dragged across its rows (M3b fixes, E). */
class PlaylistDragTest {
    private fun rows(drag: PlaylistDrag, count: Int) = IntArray(count) { drag.at(IntArray(count) { it }, it) }

    @Test
    fun theRowsAreThePlaylistWithTheOneSongMoved() {
        val drag = PlaylistDrag()
        drag.start(1)
        // Down over the songs at places 2, 3 and 4, then back up over 4.
        for (place in listOf(2, 3, 4)) assertTrue(drag.over(place))
        assertArrayEquals(intArrayOf(0, 2, 3, 4, 1, 5), rows(drag, 6))
        assertTrue(drag.over(4))
        assertArrayEquals(intArrayOf(0, 2, 3, 1, 4, 5), rows(drag, 6))
        // Over itself, or the row it is already in: nothing.
        assertEquals(false, drag.over(1))
        // Then up past where it started, to the top.
        assertTrue(drag.over(2))
        assertTrue(drag.over(0))
        assertArrayEquals(intArrayOf(1, 0, 2, 3, 4, 5), rows(drag, 6))
        // Dropped: from place 1 to row 0, and the rows stay so until the playlist has it.
        assertEquals(1 to 0, drag.drop())
        assertArrayEquals(intArrayOf(1, 0, 2, 3, 4, 5), rows(drag, 6))
        drag.reset()
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), rows(drag, 6))
    }

    @Test
    fun aRowCrossedCostsTheSameHoweverLongThePlaylist() {
        // 100,000 rows crossed of a playlist of 200,000: a copy or a search of the order for
        // each would be tens of thousands of millions of steps. (The clock is only a backstop: seconds
        // for what takes milliseconds.)
        val count = 200_000
        val drag = PlaylistDrag()
        val started = System.nanoTime()
        drag.start(0)
        for (place in 1..100_000) drag.over(place)
        val took = (System.nanoTime() - started) / 1_000_000
        assertEquals(0 to 100_000, drag.drop())
        assertEquals(0, drag.at(IntArray(count) { it }, 100_000))
        assertEquals(100_001, drag.at(IntArray(count) { it }, 100_001))
        assertTrue("took $took ms", took < 2_000)
    }
}
