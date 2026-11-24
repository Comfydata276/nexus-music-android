package org.medianexus.music.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Asking Android for the unmetered network "Download on Wi-Fi only" is bound to (offline
 * hardening 3, item 3), with a stand-in for Android's side that refuses, or reports the
 * request unavailable, when the test says. The clock is the main looper's, which moves only
 * when the test moves it.
 */
@RunWith(RobolectricTestRunner::class)
class UnmeteredNetworkTest {
    /** Android, as the test has it behave. */
    private class System : UnmeteredNetwork.Asking {
        var refuses = false
        var requests = 0
        var watches = 0
        var released = 0
        var callback: ConnectivityManager.NetworkCallback? = null

        override fun request(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, on: Handler) {
            requests++
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            if (refuses) throw IllegalStateException("Too many NetworkRequests filed")
            this.callback = callback
        }

        override fun watch(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, on: Handler) {
            watches++
            if (refuses) throw SecurityException("not allowed")
            this.callback = callback
        }

        override fun release(callback: ConnectivityManager.NetworkCallback) {
            released++
            this.callback = null
        }
    }

    private val system = System()
    private val notes = mutableListOf<String>()
    private val unmetered = UnmeteredNetwork(OkHttpClient(), system, note = { notes += it }, bind = { http, _ -> http.newBuilder().build() })

    private fun after(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    private fun capabilities(unmetered: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (unmetered) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private val wifi: Network = ShadowNetwork.newInstance(7)

    @Test
    fun refusedItIsAskedAgainAfterAPauseThatGrowsForAsLongAsItIsWanted() {
        system.refuses = true
        unmetered.want(true)
        // Android would take neither the request nor a watch: no route ("Waiting for Wi-Fi"),
        // said once.
        assertEquals(1, system.requests)
        assertEquals(1, system.watches)
        assertNull(unmetered.now.value)
        assertEquals(1, notes.size)
        // Asked again after five seconds, then ten, then twenty: not before, and not for ever
        // at the same rate. (It used to be asked again only when something next happened to
        // the downloads, and while they only wait nothing does.)
        after(UnmeteredNetwork.FIRST_PAUSE_MILLIS - 1)
        assertEquals(1, system.requests)
        after(1)
        assertEquals(2, system.requests)
        after(2 * UnmeteredNetwork.FIRST_PAUSE_MILLIS - 1)
        assertEquals(2, system.requests)
        after(1)
        assertEquals(3, system.requests)
        after(4 * UnmeteredNetwork.FIRST_PAUSE_MILLIS)
        assertEquals(4, system.requests)
        // Never further apart than five minutes.
        after(60 * 60_000L)
        val asked = system.requests
        after(UnmeteredNetwork.MOST_PAUSE_MILLIS)
        assertEquals(asked + 1, system.requests)
        assertEquals(1, notes.size)
        // Android takes it at last, and gives the Wi-Fi.
        system.refuses = false
        after(UnmeteredNetwork.MOST_PAUSE_MILLIS)
        assertNotNull(system.callback)
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        assertSame(wifi, unmetered.now.value!!.network)
        // And is not asked again while it holds the request.
        val held = system.requests
        after(60 * 60_000L)
        assertEquals(held, system.requests)
    }

    @Test
    fun reportedUnavailableItIsAskedAgainThoughAndroidHasLetTheRequestGo() {
        unmetered.want(true)
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        assertNotNull(unmetered.now.value)
        // Android says the request can't be met, which is also Android dropping it: nothing
        // more would ever be heard on it. (It was left at that, and Wi-Fi coming back later
        // was never heard of.)
        system.callback!!.onUnavailable()
        assertNull(unmetered.now.value)
        assertEquals(1, system.requests)
        after(UnmeteredNetwork.FIRST_PAUSE_MILLIS)
        assertEquals(2, system.requests)
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        assertNotNull(unmetered.now.value)
    }

    @Test
    fun givenUpWhenNothingIsWaitingSoNoNetworkIsHeldForNothing() {
        unmetered.want(true)
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        unmetered.want(false)
        assertEquals(1, system.released)
        assertNull(unmetered.now.value)
        // Nor while it was only being asked for: the asking stops with the wanting.
        system.refuses = true
        unmetered.want(true)
        val asked = system.requests
        unmetered.want(false)
        after(60 * 60_000L)
        assertEquals(asked, system.requests)
        assertEquals(1, system.released)
    }

    @Test
    fun theSameNetworkTurnedMeteredAndBackIsAnotherRoute() {
        unmetered.want(true)
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        val first = unmetered.now.value!!
        // Its capabilities change without its turning metered (signal, link speed): the
        // same route.
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        assertSame(first, unmetered.now.value)
        // Metered: no route, at once. Unmetered again: a route, and not the one before.
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = false))
        assertNull(unmetered.now.value)
        system.callback!!.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        assertNotSame(first, unmetered.now.value)
        assertSame(wifi, unmetered.now.value!!.network)
        // Lost: none.
        system.callback!!.onLost(wifi)
        assertNull(unmetered.now.value)
    }
}
