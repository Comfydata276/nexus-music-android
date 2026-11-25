package org.medianexus.music.sync

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.AccountGraph
import org.medianexus.music.AccountWiring
import org.medianexus.music.Reach
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SettingsStore

/**
 * When the phone looks at the server for what the person's other devices have changed: at
 * start, when the screens come to the front, and each minute while they stay there. With the
 * screens gone the minute's timer is not running at all.
 */
class InFrontTest {
    /** No test here may wait for ever (see InStepTest). */
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule val folder = TemporaryFolder()

    private suspend fun until(what: String, check: () -> Boolean) {
        repeat(400) {
            if (check()) return
            delay(25)
        }
        error("never: $what")
    }

    @Test
    fun theMinutesTimerRunsOnlyWhileTheScreensAreInFront() = runBlocking {
        // Keeping in step is switched off in this build; this is a test of it.
        InStepBuild.forTests = true
        val looks = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                // (Each look begins by asking for the list of playlists.)
                if ("getPlaylists" in request.url.encodedPath) looks.incrementAndGet()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        val settings = SettingsStore.open(File(folder.root, "settings.json"), scope, Dispatchers.IO)
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        Belongs.takeOn(settings, "key", { dirs.data })
        val front = MutableStateFlow(false)
        // The minute, by hand: each wait for it is counted, and ends when the test says.
        val waits = AtomicInteger()
        val minute = Channel<Unit>(Channel.UNLIMITED)
        val wiring = AccountWiring(
            settings = settings.state, reach = MutableStateFlow(Reach()), suspect = {}, settingsStore = settings, front = front,
            pause = { millis -> if (millis == InStep.LOOK_MILLIS) { waits.incrementAndGet(); minute.receive() } else delay(millis) },
        )
        val account = AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, wiring)
        try {
            // At start, once the account's own has been read: one look. And no timer.
            until("the look at start") { looks.get() == 1 }
            delay(300)
            assertEquals("no timer is waiting while the screens are away", 0, waits.get())
            // The screens come to the front (a moment after that look: not looked at again
            // at once): the minute starts, and each minute there is a look.
            front.value = true
            until("the timer to start") { waits.get() == 1 }
            assertEquals(1, looks.get())
            minute.send(Unit)
            until("the minute's look") { looks.get() == 2 }
            until("the next minute to start") { waits.get() == 2 }
            // The screens go: the timer goes with them. A minute that then passes is nobody's.
            front.value = false
            delay(300)
            minute.send(Unit)
            delay(300)
            assertEquals(2, looks.get())
            assertEquals(2, waits.get())
            // Back in front: it starts again (the minute left over from before is the first).
            front.value = true
            until("a look once back") { looks.get() == 3 }
        } finally {
            InStepBuild.forTests = null
            account.close()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun aLookDoesNotPassOnAChangeThatIsStillSettling() = runBlocking {
        InStepBuild.forTests = true
        val looks = AtomicInteger()
        val requests = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                requests.incrementAndGet()
                // (Each exchange begins by asking for the list of playlists.)
                if ("getPlaylists" in request.url.encodedPath) looks.incrementAndGet()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        val settings = SettingsStore.open(File(folder.root, "settings.json"), scope, Dispatchers.IO)
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        Belongs.takeOn(settings, "key", { dirs.data })
        val front = MutableStateFlow(false)
        // Both waits by hand: the quarter minute between looks, and the few seconds a
        // change made here is given to settle.
        val waits = AtomicInteger()
        val tick = Channel<Unit>(Channel.UNLIMITED)
        val settles = AtomicInteger()
        val settled = Channel<Unit>(Channel.UNLIMITED)
        val wiring = AccountWiring(
            settings = settings.state, reach = MutableStateFlow(Reach()), suspect = {}, settingsStore = settings, front = front,
            pause = { millis ->
                when (millis) {
                    InStep.LOOK_MILLIS -> { waits.incrementAndGet(); tick.receive() }
                    InStep.SETTLE_MILLIS -> { settles.incrementAndGet(); settled.receive() }
                    else -> delay(millis)
                }
            },
        )
        val account = AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, wiring)
        try {
            until("the look at start") { looks.get() == 1 }
            front.value = true
            until("the timer to start") { waits.get() == 1 }
            // Something shared is changed here. It is passed on in a few seconds, so that a
            // burst of changes goes as one...
            account.step!!.shareSoon()
            until("the change to be settling") { settles.get() == 1 }
            // ...and a look that comes round meanwhile does not pass it on early: the
            // exchange the change is waiting for is about to be made, and reads as a look does.
            tick.send(Unit)
            until("the timer to start again") { waits.get() == 2 }
            assertEquals("a look made an exchange of its own while a change made here was settling", 1, looks.get())
            settled.send(Unit)
            until("the change's own exchange") { looks.get() == 2 }
            // With nothing settling, the next look makes its exchange as ever. (Once that
            // exchange has ended: the server has been asked nothing more for a moment. A
            // look that found it still out would be put off, through a settling of its
            // own, which is let through here so that even then this ends.)
            var asked: Int
            do {
                asked = requests.get()
                delay(300)
            } while (requests.get() != asked)
            settled.send(Unit)
            tick.send(Unit)
            until("the next look") { looks.get() >= 3 }
        } finally {
            InStepBuild.forTests = null
            account.close()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun theSavedQueueIsLookedAtWithEveryLookOnlyWhereThatCostsNothing() = runBlocking {
        // The rule: with every look on a network that isn't metered and with nothing playing
        // here; otherwise once a minute, as it was.
        assertEquals(true, org.medianexus.music.queueDue(15_000, unmetered = true, playing = false))
        assertEquals(false, org.medianexus.music.queueDue(15_000, unmetered = false, playing = false))
        assertEquals(false, org.medianexus.music.queueDue(45_000, unmetered = true, playing = true))
        assertEquals(true, org.medianexus.music.queueDue(60_000, unmetered = false, playing = false))
        assertEquals(true, org.medianexus.music.queueDue(60_000, unmetered = true, playing = true))

        // And the timer follows it, on mobile data: the record every time, the queue one
        // time in four.
        InStepBuild.forTests = true
        val records = AtomicInteger()
        val queues = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if ("getPlaylists" in request.url.encodedPath) records.incrementAndGet()
                if ("getPlayQueue" in request.url.encodedPath) queues.incrementAndGet()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        val settings = SettingsStore.open(File(folder.root, "settings.json"), scope, Dispatchers.IO)
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        Belongs.takeOn(settings, "key", { dirs.data })
        val front = MutableStateFlow(false)
        val network = MutableStateFlow(org.medianexus.music.net.NetworkNow(connected = true, unmetered = false))
        val waits = AtomicInteger()
        val quarter = Channel<Unit>(Channel.UNLIMITED)
        val wiring = AccountWiring(
            settings = settings.state, reach = MutableStateFlow(Reach()), suspect = {}, settingsStore = settings, front = front, network = network,
            pause = { millis -> if (millis == InStep.LOOK_MILLIS) { waits.incrementAndGet(); quarter.receive() } else delay(millis) },
        )
        val account = AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, wiring)
        try {
            until("the look at start") { records.get() == 1 && queues.get() == 1 }
            front.value = true
            until("the timer to start") { waits.get() == 1 }
            // Three quarters of a minute on mobile data: three looks at the record, none at the queue.
            repeat(3) { n ->
                quarter.send(Unit)
                until("look ${n + 1}") { records.get() == n + 2 && waits.get() == n + 2 }
            }
            delay(200)
            assertEquals(1, queues.get())
            // The minute is up: this look reads the queue too.
            quarter.send(Unit)
            until("the minute's look") { records.get() == 5 && queues.get() == 2 }
            until("the next wait") { waits.get() == 5 }
            // On Wi-Fi, with nothing playing: the queue with every look.
            network.value = org.medianexus.music.net.NetworkNow(connected = true, unmetered = true)
            quarter.send(Unit)
            until("a look on Wi-Fi") { records.get() == 6 && queues.get() == 3 }
        } finally {
            InStepBuild.forTests = null
            account.close()
            scope.cancel()
            server.close()
        }
    }
}
