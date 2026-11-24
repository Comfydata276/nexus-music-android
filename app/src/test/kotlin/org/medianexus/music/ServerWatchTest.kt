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
    private var caughtUp = 0
    private var signedOut = 0
    private var mayAsk = true

    private fun TestScope.watch() = ServerWatch(
        backgroundScope,
        ping = { pings++; answer() },
        cameOnline = { online++ },
        signInGone = { signedOut++ },
        mayAsk = { mayAsk },
        caughtUp = { caughtUp++ },
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

    // ---- Knowing the server is back (offline review 8) ----------------------------------------

    @Test
    fun anAnswerOfSomeKindIsAReasonToAskNotTheServerBack() = runTest {
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        // A request gets an answer: a proxy's error page, with the server behind it still
        // down. The server is asked, at once, and it is the proxy that answers again.
        answer = { throw ApiError.NotSubsonic("502") }
        val asked = pings
        watch.reached(true)
        runCurrent()
        assertEquals(asked + 1, pings)
        // Nothing was announced, nothing started: it never was back.
        assertTrue(watch.state.value.unreachable)
        assertEquals(0, online)
        // The server really answers: now it is back, once.
        answer = {}
        watch.reached(true)
        runCurrent()
        assertFalse(watch.state.value.unreachable)
        assertEquals(1, online)
    }

    @Test
    fun anErrorFromTheCheckIsNotTheServerBackAndSignsNobodyOut() = runTest {
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        // The check comes back as an error of the server's own: as Windows has it, any
        // error but a refused sign-in leaves it out of reach.
        answer = { throw ApiError.Server(0, "something broke") }
        advanceTimeBy(ServerWatch.AGAIN_MILLIS + 1)
        assertTrue(watch.state.value.unreachable)
        assertEquals(0, online)
        assertEquals(0, signedOut)
        // A proxy's error page in the server's place: the same.
        answer = { throw ApiError.NotSubsonic("502") }
        advanceTimeBy(ServerWatch.AGAIN_MILLIS + 1)
        assertTrue(watch.state.value.unreachable)
        assertEquals(0, signedOut)
        // And a server found erroring when it was thought to be there is out of reach too.
        val other = watch()
        answer = { throw ApiError.Server(0, "something broke") }
        other.check()
        runCurrent()
        assertTrue(other.state.value.unreachable)
    }

    @Test
    fun askedLessOftenTheLongerItHasBeenGone() = runTest {
        val watch = ServerWatch(
            backgroundScope, ping = { pings++; answer() }, cameOnline = { online++ }, signInGone = { signedOut++ },
            clock = { testScheduler.currentTime },
        )
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        fun asksIn(millis: Long): Int {
            val before = pings
            advanceTimeBy(millis)
            return pings - before
        }
        // The first two minutes: every fifteen seconds.
        assertEquals(8, asksIn(2 * 60_000L + 1))
        // Up to ten minutes: every half minute.
        assertEquals(16, asksIn(8 * 60_000L))
        // Up to an hour: every minute.
        assertEquals(50, asksIn(50 * 60_000L))
        // After that: every five minutes.
        assertEquals(12, asksIn(60 * 60_000L))
        assertEquals(15_000L, ServerWatch.againAfter(0))
        assertEquals(300_000L, ServerWatch.againAfter(3 * 3_600_000L))
    }

    @Test
    fun notAskedAfterWhileNothingWantsToKnow() = runTest {
        var wanted = true
        val watch = ServerWatch(
            backgroundScope, ping = { pings++; answer() }, cameOnline = { online++ }, signInGone = { signedOut++ },
            wanted = { wanted },
        )
        answer = { throw ApiError.Network("gone") }
        watch.check()
        runCurrent()
        // The app goes out of sight with nothing playing and nothing waiting.
        wanted = false
        val asked = pings
        advanceTimeBy(ServerWatch.AGAIN_MILLIS * 20 + 1)
        assertEquals(asked, pings)
        // It comes to the front again: asked at once, not at the next turn.
        wanted = true
        answer = {}
        watch.nudge()
        runCurrent()
        assertEquals(asked + 1, pings)
        assertFalse(watch.state.value.unreachable)
        assertEquals(1, online)
    }

    @Test
    fun aLateAnswerForAnAccountThatHasGoneChangesNothing() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val watch = ServerWatch(
            backgroundScope,
            // A check that is on its way and can't be called back.
            ping = {
                pings++
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                throw ApiError.Network("gone")
            },
            cameOnline = { online++ }, signInGone = { signedOut++ },
        )
        watch.check()
        runCurrent()
        assertEquals(1, pings)
        // That account signs out, and another signs in, while the check is out.
        watch.reset()
        // Its answer arrives: the first account's server is gone. That is nothing to do
        // with the account signed in now.
        gate.complete(Unit)
        runCurrent()
        assertFalse(watch.state.value.unreachable)
        assertTrue(watch.state.value.sure)
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

    @Test
    fun offlineModeSwitchedOffCatchesUpWithTheServerWithoutSayingItIsBack() = runTest {
        val watch = watch()
        watch.manual(true)
        runCurrent()
        assertEquals(0, pings)
        // Switched off by hand: the server is asked whether it is there, and it is. What was
        // held while offline goes to it now (Windows' check_connection catches up after any
        // check that finds the server), where it used to wait for the next start of the app.
        // Nothing is said: the switch was in the person's own hand.
        watch.manual(false)
        runCurrent()
        assertEquals(1, pings)
        assertEquals(1, caughtUp)
        assertEquals(0, online)
        // A later ordinary check that finds it there is not a catching up.
        watch.check()
        runCurrent()
        assertEquals(1, caughtUp)
    }

    @Test
    fun offlineModeSwitchedOffWithTheServerGoneCatchesUpWhenItIsBack() = runTest {
        val watch = watch()
        watch.manual(true)
        answer = { throw ApiError.Network("gone") }
        watch.manual(false)
        runCurrent()
        assertTrue(watch.state.value.unreachable)
        assertEquals(0, caughtUp)
        answer = {}
        advanceTimeBy(ServerWatch.AGAIN_MILLIS + 1)
        // Back from being out of reach: that is the usual coming back, said as such.
        assertEquals(1, online)
        assertEquals(0, caughtUp)
    }

    // ---- From the first instant (offline review 7) --------------------------------------------

    @Test
    fun withNoNetworkAtStartUpItIsOfflineFromTheFirstInstantAndSaysNothingWhenItAnswers() = runTest {
        val watch = watch()
        // The phone has no network: out of reach, now, with nothing asked and waited for.
        answer = { throw ApiError.Network("no network") }
        watch.begin(networkThere = false)
        assertTrue(watch.state.value.unreachable)
        assertTrue(watch.state.value.sure)
        runCurrent()
        assertTrue(watch.offline)
        // A server that answers all the same (on the phone itself, say): caught up with, and
        // not announced as a return.
        answer = {}
        advanceTimeBy(ServerWatch.AGAIN_MILLIS + 1)
        runCurrent()
        assertFalse(watch.state.value.unreachable)
        assertEquals(0, online)
        assertEquals(1, caughtUp)
    }

    @Test
    fun withANetworkItIsNotSureUntilTheServerHasAnsweredOrFailedTo() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var first = 0
        val watch = ServerWatch(
            backgroundScope,
            ping = { pings++; answer() },
            cameOnline = { online++ }, signInGone = { signedOut++ }, mayAsk = { mayAsk }, caughtUp = { caughtUp++ },
            // The first ask: held here until the test lets it fail.
            firstPing = { first++; gate.await(); throw ApiError.Network("too slow") },
        )
        watch.begin(networkThere = true)
        runCurrent()
        // Asked, and not answered: neither online nor offline is known.
        assertEquals(1, first)
        assertFalse(watch.state.value.sure)
        assertFalse(watch.offline)
        // It fails: out of reach, and that is known.
        gate.complete(Unit)
        runCurrent()
        assertTrue(watch.state.value.sure)
        assertTrue(watch.state.value.unreachable)
        // A later account starts unsure again.
        watch.reset()
        assertTrue(watch.state.value.sure)
        assertFalse(watch.state.value.unreachable)
    }

    @Test
    fun losingTheNetworkIsOutOfReachAtOnce() = runTest {
        val watch = watch()
        answer = { throw ApiError.Network("gone") }
        watch.noNetwork()
        assertTrue(watch.state.value.unreachable)
        // And its return is the usual one.
        answer = {}
        watch.networkBack()
        runCurrent()
        assertFalse(watch.state.value.unreachable)
        assertEquals(1, online)
    }
}
