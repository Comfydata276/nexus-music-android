package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import org.medianexus.music.net.ApiError

class CarTroubleTest {
    @Test
    fun theKindOfFailureComesFromTheErrorNeverFromItsWords() {
        assertEquals(CarTrouble.UNREACHABLE, CarTrouble.of(ApiError.Network("The connection timed out."), blocked = false))
        assertEquals(CarTrouble.LOCAL_NETWORK, CarTrouble.of(ApiError.Network("The connection timed out."), blocked = true))
        assertEquals(CarTrouble.NOT_UNDERSTOOD, CarTrouble.of(ApiError.NotSubsonic("Unexpected response from getAlbum (at $.song)."), blocked = false))
        assertEquals(CarTrouble.SIGN_IN, CarTrouble.of(ApiError.Server(40, "Wrong username or password"), blocked = false))
        assertEquals(CarTrouble.SIGN_IN, CarTrouble.of(ApiError.Server(41, "Token authentication not supported"), blocked = false))
        assertEquals(CarTrouble.NOT_ALLOWED, CarTrouble.of(ApiError.Server(50, "User is not authorized"), blocked = false))
        assertEquals(CarTrouble.NOT_FOUND, CarTrouble.of(ApiError.Server(70, "data not found"), blocked = false))
        // Whatever it said, in whatever words.
        for (words in listOf("", "open /srv/music/x.flac: permission denied", "Something like: Can't reach the server"))
            assertEquals(CarTrouble.REFUSED, CarTrouble.of(ApiError.Server(0, words), blocked = false))
        // A server that answered is not "out of reach", allowed to reach the home network or not.
        assertEquals(CarTrouble.REFUSED, CarTrouble.of(ApiError.Server(10, "Required parameter is missing"), blocked = true))
    }
}
