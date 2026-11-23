package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchersTest {
    @Test
    fun eachBrowserThatSearchedIsToldNotOnlyTheLast() {
        val searchers = Searchers<String>()
        searchers.made("car", "lantern")
        searchers.made("system", "lantern")
        searchers.made("car", "harbour")
        // Before, the second search of the same words took the first one's place, and the
        // car that tapped "Tap to try again" for it was never told.
        assertEquals(setOf("car", "system"), searchers.of("lantern").toSet())
        assertEquals(listOf("car"), searchers.of("harbour"))
    }

    @Test
    fun itKeepsTheLastFewOnly() {
        val searchers = Searchers<String>(most = 3)
        for (n in 1..100) searchers.made("car", "words $n")
        assertEquals(3, searchers.size())
        assertEquals(listOf("car"), searchers.of("words 100"))
        assertEquals(emptyList<String>(), searchers.of("words 1"))
    }
}
