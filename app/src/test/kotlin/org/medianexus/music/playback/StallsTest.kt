package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StallsTest {
    private var now = 0L
    private val stalls = Stalls(clock = { now })

    @Test
    fun failuresCloseTogetherWaitLongerEachTimeAndThenItIsGivenUp() {
        assertEquals(1_000L, stalls.next())
        now += 11_000
        assertEquals(2_000L, stalls.next())
        now += 12_000
        assertEquals(4_000L, stalls.next())
        now += 14_000
        assertEquals(8_000L, stalls.next())
        now += 18_000
        // Whatever arrived between them: five in under a minute is a link that isn't working.
        assertNull(stalls.next())
    }

    @Test
    fun aFailureNowAndThenIsAlwaysTheFirst() {
        repeat(20) {
            assertEquals(1_000L, stalls.next())
            now += Stalls.WINDOW_MILLIS
        }
    }

    @Test
    fun aRoundWithNothingArrivingAtAllFitsInsideWhatIsCounted() {
        // Each read is given up on after ten seconds with nothing; then the wait.
        var waited = 0L
        for (wait in Stalls.WAITS) {
            assertEquals(wait, stalls.next())
            now += wait + 10_000
            waited += wait + 10_000
        }
        assertNull("the first failure was forgotten before the round ended, after $waited ms", stalls.next())
    }
}
