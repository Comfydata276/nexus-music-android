package org.medianexus.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.ApiError

/**
 * Noticing that the server has gone and that it is back (Windows' `set_unreachable`,
 * `check_connection`, `check_server`). The clock is the test's own: nothing waits in real
 * time, and every ping answers as the test has set it to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerWatchTest {
    private var answer: () -> Unit = {}
    private var pings = 0
    private var online = 0
    private var signedOut = 0
    private var mayAsk = true

    private fun TestScope.watch() = ServerWatch(
        backgroundScope,
        ping = { pings++; answer() },
        cameOnline = { online++ },
        signInGone = { signedOut++ },
        mayAsk = { mayAsk },
    )

    @Test
    fun aFailedRequestIsAReasonToAskNotProof() = runTest {
        val watch = watch()
        // One request fails, but the server answers the ping: nothing changes.
        watch.reached(false)
        runCurrent()
        assertEquals(1, pings)
        assertFalse(watch.state.value.unreachable)
        // It fails, and so does the ping: out of reach.
        answer = { throw ApiError.Network("gone") }
        watch.reached(false)
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        assertTrue(watch.offline)
    }

    @Test
    fun askedEveryFifteenSecondsUntilItIsBack() = runTest {
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        val asked = pings
        advanceTimeBy(ServerWatch.AGAIN_MILLIS + 1)
        assertEquals(asked + 1, pings)
        advanceTimeBy(ServerWatch.AGAIN_MILLIS)
        assertEquals(asked + 2, pings)
        // Back: it says so once, and the asking stops.
        answer = {}
        advanceTimeBy(ServerWatch.AGAIN_MILLIS)
        assertFalse(watch.state.value.unreachable)
        assertEquals(1, online)
        val after = pings
        advanceTimeBy(ServerWatch.AGAIN_MILLIS * 4)
        assertEquals(after, pings)
    }

    @Test
    fun anyAnswerFromTheServerSettlesIt() = runTest {
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        // A page loaded meanwhile: the server is there.
        watch.reached(true)
        assertFalse(watch.state.value.unreachable)
        assertEquals(1, online)
    }

    @Test
    fun aServerThatAnswersWithAnErrorIsThereAndNobodyIsSignedOut() = runTest {
        val watch = watch()
        answer = { throw ApiError.Server(0, "something broke") }
        watch.check()
        runCurrent()
        assertFalse(watch.state.value.unreachable)
        assertEquals(0, signedOut)
        // A proxy's error page in the server's place: not the server, so out of reach.
        answer = { throw ApiError.NotSubsonic("502") }
        watch.check()
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        assertEquals(0, signedOut)
    }

    @Test
    fun onlyARefusedSignInSignsOut() = runTest {
        val watch = watch()
        answer = { throw ApiError.Server(40, "Wrong username or password") }
        watch.check()
        runCurrent()
        assertEquals(1, signedOut)
    }

    @Test
    fun offlineByHandAsksNothingAndSwitchedOffChecks() = runTest {
        val watch = watch()
        watch.manual(true)
        assertTrue(watch.offline)
        watch.reached(false)
        watch.check()
        runCurrent()
        assertEquals(0, pings)
        // Switched off: the server is asked whether it is really there before it is assumed.
        answer = { throw ApiError.Network("gone") }
        watch.manual(false)
        runCurrent()
        assertEquals(1, pings)
        assertTrue(watch.state.value.unreachable)
    }

    @Test
    fun notAskedWhileAskingCanDoNoGood() = runTest {
        // Android 17, a server on the home network and no permission to reach it.
        mayAsk = false
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        advanceTimeBy(ServerWatch.AGAIN_MILLIS * 3)
        assertEquals(0, pings)
        // Allowed: asked at once.
        mayAsk = true
        answer = {}
        watch.networkBack()
        runCurrent()
        assertFalse(watch.state.value.unreachable)
    }

    @Test
    fun anotherAccountStartsWithTheServerThere() = runTest {
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        watch.reset()
        assertFalse(watch.state.value.unreachable)
        val asked = pings
        advanceTimeBy(ServerWatch.AGAIN_MILLIS * 3)
        assertEquals(asked, pings)
    }
}
