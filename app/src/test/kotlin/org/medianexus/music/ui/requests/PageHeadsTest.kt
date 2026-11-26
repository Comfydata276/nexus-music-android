package org.medianexus.music.ui.requests

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** What heads the Requests page ([heads]): the warnings hold whether or not a list was read. */
class PageHeadsTest {
    @get:Rule val limit: Timeout = Timeout.seconds(10)

    private val putBack = "An artist's setting couldn't be put back on Lidarr."

    @Test
    fun withNoKeyTheWarningThatASettingMayNeedPuttingBackIsShown() {
        assertEquals(
            listOf(Head.Why("Not connected yet."), Head.AskedUnreadable, Head.WayBack(putBack)),
            heads(hasList = false, why = "Not connected yet.", askedUnreadable = true, wayBack = putBack),
        )
    }

    @Test
    fun whileTheListIsFirstAskedForTheWarningsAreShownAboveTheWait() {
        assertEquals(listOf(Head.WayBack(putBack), Head.Loading), heads(hasList = false, why = null, askedUnreadable = false, wayBack = putBack))
    }

    @Test
    fun withAListTheWarningsComeUnderWhatFailed() {
        assertEquals(
            listOf(Head.Failed("Couldn't reach Lidarr."), Head.AskedUnreadable, Head.WayBack(putBack)),
            heads(hasList = true, why = "Couldn't reach Lidarr.", askedUnreadable = true, wayBack = putBack),
        )
        assertEquals(emptyList<Head>(), heads(hasList = true, why = null, askedUnreadable = false, wayBack = ""))
    }
}
