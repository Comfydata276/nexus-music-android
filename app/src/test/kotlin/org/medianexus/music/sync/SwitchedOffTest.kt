package org.medianexus.music.sync

import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.AccountGraph
import org.medianexus.music.AccountWiring
import org.medianexus.music.Reach
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SettingsStore

/**
 * Keeping in step is switched off in this build ([InStepBuild]): with it off, nothing of it
 * runs on a phone, whatever would set it off. The pins and list choices go on working as the
 * phone's own settings.
 */
class SwitchedOffTest {
    /** No test here may wait for ever (see InStepTest). */
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    /** Every request the stand-in server was sent: its method, as `getPlaylists`. */
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun stop() {
        InStepBuild.forTests = false
        scope.cancel()
        server.close()
    }

    /** What keeping in step asks of the server, and nothing else does in these tests. */
    private val itsRequests = setOf("getPlaylists", "getPlaylist", "updatePlaylist", "createPlaylist", "savePlayQueue", "getPlayQueue")

    private fun startServer() {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                asked += request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
            }
        }
        server.start()
    }

    /** The account as the app makes it, with everything keeping in step is given there. */
    private fun account(settings: SettingsStore, reach: MutableStateFlow<Reach>, front: MutableStateFlow<Boolean>, dirs: AccountDirs): AccountGraph {
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        Belongs.takeOn(settings, "key", { dirs.data })
        val wiring = AccountWiring(
            settings = settings.state, reach = reach, suspect = {}, settingsStore = settings, front = front,
            // (Every wait is over at once: the settling, the minute.)
            pause = { delay(1) },
        )
        return AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, wiring)
    }

    /** Everything that would start an exchange or a save of the queue, one after another. */
    private suspend fun everyTrigger(account: AccountGraph, settings: SettingsStore, reach: MutableStateFlow<Reach>, front: MutableStateFlow<Boolean>) {
        // Start-up has had its moment; the screens come to the front.
        delay(300)
        front.value = true
        delay(300)
        // Switched off and on (by whatever means: the setting isn't shown).
        settings.update { it.copy(sync = false) }
        delay(100)
        settings.update { it.copy(sync = true) }
        delay(300)
        // The server gone and back.
        reach.value = Reach(unreachable = true)
        delay(100)
        reach.value = Reach()
        delay(300)
        // A pin, a list's shuffle and sort, the theme; a song started; a play that counted.
        settings.commit { Chosen.made(it, Chosen.Did.Pin("p1", true), "key", 1_000) }
        settings.commit { Chosen.made(it, Chosen.Did.Shuffle("liked", true), "key", 1_000) }
        settings.commit { Chosen.made(it, Chosen.Did.Sort("liked", 1, false), "key", 1_000) }
        settings.commit { Chosen.made(it, Chosen.Did.Theme("Ocean"), "key", 1_000) }
        account.recentlyPlayed.played(Song(id = "s1", title = "One"))
        account.played("s1", 1_000_000_000L, "", report = false)
        // And it is asked outright.
        account.look()
        delay(1_000)
    }

    @Test
    fun switchedOffNothingOfItRunsAndNothingIsAskedOfTheServer() = runBlocking {
        assertTrue("keeping in step exists in this build", !InStepBuild.EXISTS)
        startServer()
        val settings = SettingsStore.open(File(folder.root, "settings.json"), scope, Dispatchers.IO)
        val reach = MutableStateFlow(Reach())
        val front = MutableStateFlow(false)
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        val account = account(settings, reach, front, dirs)
        try {
            // None of it is made: nothing for the player to save the queue through, and no
            // "Continue here" to offer.
            assertNull(account.step)
            assertNull(account.queueStep)
            everyTrigger(account, settings, reach, front)
            assertEquals("asked of the server: $asked", emptyList<String>(), asked.filter { it in itsRequests })
        } finally {
            account.close()
        }
    }

    /** The same, switched on for the test: what the test looks for is what would be asked. */
    @Test
    fun switchedOnForATestTheSameTriggersAskTheServer() = runBlocking {
        InStepBuild.forTests = true
        startServer()
        val settings = SettingsStore.open(File(folder.root, "settings.json"), scope, Dispatchers.IO)
        val reach = MutableStateFlow(Reach())
        val front = MutableStateFlow(false)
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        val account = account(settings, reach, front, dirs)
        try {
            everyTrigger(account, settings, reach, front)
            assertTrue("asked of the server: $asked", "getPlaylists" in asked)
            assertTrue("asked of the server: $asked", "getPlayQueue" in asked)
        } finally {
            account.close()
        }
    }

    /**
     * With it off, a pin shows at once and is there after a restart, as are a list's choices
     * and the theme: what is shown is the settings' own. The changes waiting to be shared are
     * never shared and so never forgotten, but they don't grow without end: one for each
     * playlist ever pinned or unpinned, one for each list's shuffle and one for its sort, and
     * one for the theme, the last word on each.
     */
    @Test
    fun switchedOffPinsAndListChoicesAreThePhonesOwnAndKeptAcrossARestart() = runBlocking {
        startServer()
        val file = File(folder.root, "settings.json")
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var settings = SettingsStore.open(file, run, Dispatchers.IO)
        // (As the app does when an account is signed in, whether or not keeping in step
        // exists in the build: the shared choices in the settings are that account's.)
        Belongs.takeOn(settings, "acct", { dirs.data })
        var account = account(settings, MutableStateFlow(Reach()), MutableStateFlow(true), dirs)
        settings.commit { Chosen.made(it, Chosen.Did.Pin("p1", true), "key", 1_000) }
        // Shown at once.
        assertEquals(listOf("p1"), settings.value.pinnedPlaylists)
        settings.commit { Chosen.made(it, Chosen.Did.Shuffle("liked", true), "key", 1_000) }
        settings.commit { Chosen.made(it, Chosen.Did.Sort("liked", 2, true), "key", 1_000) }
        settings.commit { Chosen.made(it, Chosen.Did.Theme("Ocean"), "key", 1_000) }
        // Pinned and unpinned over and over: the last word, once.
        repeat(50) { settings.commit { s -> Chosen.made(s, Chosen.Did.Pin("p2", it % 2 == 0), "key", 1_000) } }
        settings.commit { Chosen.made(it, Chosen.Did.Pin("p3", true), "key", 1_000) }
        assertEquals(listOf("p1", "p3"), settings.value.pinnedPlaylists)
        assertTrue(settings.flush())
        account.close()
        run.cancel()

        // The app starts again.
        val again = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            settings = SettingsStore.open(file, again, Dispatchers.IO)
            account = account(settings, MutableStateFlow(Reach()), MutableStateFlow(true), dirs)
            delay(500)
            assertEquals(listOf("p1", "p3"), settings.value.pinnedPlaylists)
            assertEquals(true, settings.value.lists["liked"]?.shuffle)
            assertEquals(2, settings.value.lists["liked"]?.sort)
            assertEquals(true, settings.value.lists["liked"]?.descending)
            assertEquals("Ocean", settings.value.theme)
            // And each is kept as the change, by the shared core, for the day it is shared:
            // p1 and p3, the list's shuffle and its sort, the theme. (p2, pinned and unpinned
            // again before anything was sent, is no change at all.)
            val kept = settings.value.choices.toString()
            for (word in listOf("p1", "p3", "liked", "Ocean")) assertTrue(word, word in kept)
            assertFalse("p2" in kept)
            account.close()
        } finally {
            again.cancel()
        }
        assertEquals(emptyList<String>(), asked.filter { it in itsRequests })
    }
}
