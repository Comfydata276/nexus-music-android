package org.medianexus.music.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a note's button acts on: the screen alive at the press ([Live]), not the one that
 *  made the note. */
class LiveTest {
    private class Screen(val name: String) {
        val opened = mutableListOf<String>()
    }

    @Test
    fun aButtonPressedAfterTheActivityIsMadeAgainActsOnTheLiveOne() {
        val live = Live<Screen>()
        val first = Screen("first")
        live.take(first)
        // The note is made while the first screen is up; its button looks up the screen
        // only when it is pressed.
        val press = { live.act { it.opened += "Requests" } }
        // The activity is made again (turned, a change of font size): the new shell takes
        // over before the old one has let go, and then the old one lets go.
        val second = Screen("second")
        live.take(second)
        live.let(first)
        press()
        assertEquals(emptyList<String>(), first.opened)
        assertEquals(listOf("Requests"), second.opened)
    }

    @Test
    fun withNoScreenUpAPressDoesNothing() {
        val live = Live<Screen>()
        val only = Screen("only")
        live.take(only)
        live.let(only)
        var acted = false
        live.act { acted = true }
        assertEquals(false, acted)
    }
}
