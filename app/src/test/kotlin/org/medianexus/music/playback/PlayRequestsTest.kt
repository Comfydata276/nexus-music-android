package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRequestsTest {
    private val requests = PlayRequests<String>()

    @Test
    fun aRequestIsBeingWorkedOnFromArrivalUntilItIsHandedOverOrFails() {
        assertEquals(0, requests.waiting)
        val chosen = requests.begin("car", start = 1)
        val carryOn = requests.begin("phone", start = null)
        assertEquals(1L, chosen.start)
        assertEquals(null, carryOn.start)
        assertEquals(2, requests.waiting)
        requests.end(chosen)
        assertEquals(1, requests.waiting)
        // Ended twice (handed over, and then seen to have failed) is ended.
        requests.end(chosen)
        requests.end(carryOn)
        assertEquals(0, requests.waiting)
    }

    @Test
    fun whatFollowsARequestThatCameToNothingDoesNothingUntilItsSenderIsDone() {
        requests.end(requests.begin("car", start = 1))
        assertFalse(requests.isDropped("car"))
        // Overtaken: the "make ready" and "play" Media3 sends after it are its own.
        requests.drop("car")
        assertTrue(requests.isDropped("car"))
        // Another sender's Play is that sender's.
        assertFalse(requests.isDropped("phone"))
        assertFalse(requests.isDropped(null))
        // Media3 says the car's run of commands is over: its next Play is a Play.
        requests.finished("car")
        assertFalse(requests.isDropped("car"))
    }

    @Test
    fun aNewRequestFromTheSameSenderIsNotHeldToTheOldOnesFate() {
        requests.drop("car")
        requests.begin("car", start = 7)
        assertFalse(requests.isDropped("car"))
        // And a sender that has gone leaves nothing behind.
        requests.drop("car")
        requests.gone("car")
        assertFalse(requests.isDropped("car"))
        assertEquals(0, requests.waiting)
    }
}
