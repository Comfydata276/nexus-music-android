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
            account.close()
            scope.cancel()
            server.close()
        }
    }
}
