package org.medianexus.music.requests

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SecretBox

/**
 * **Requests are in this build ([RequestsBuild]) and off until the person turns them on.**
 * In the build with the toggle off, an account has a seat that says "off" and nothing else
 * of requests: nothing is made, read, kept or asked, at the sign-in or at the sign-out.
 * Turned on, they come into being (`RequestsTest` and `SameAccountAgainTest` have what
 * happens then). And a build made without them has not even the seat.
 */
class SwitchedOffTest {
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()

    /** How many times what owns requests was asked for. */
    private val asked = AtomicInteger()

    /** A box that seals nothing: enough to see whether anything is ever put in it. */
    private object Plain : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = plain
        override fun open(sealed: ByteArray): ByteArray = sealed
    }

    @After
    fun stop() {
        RequestsBuild.forTests = null
        app.cancel()
        server.close()
    }

    private val app = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var registry: RequestsOwners? = null

    private fun owners(secrets: File): RequestsOwners = registry ?: RequestsOwners(
        app,
        make = { account, sources, here, switched ->
            val requests = Requests(account, app, RequestsSecrets(secrets, Plain, account.server, account.username), afterwards = {}, switched = switched)
            requests to RequestsPages.of(requests, app, account.dirs.data, sources, here, note = {}, afterwards = {})
        },
        // (As the app has it: a look for the account's two sealed files.)
        holds = { account -> RequestsSecrets(secrets, Plain, account.server, account.username).exist() },
    ).also { registry = it }

    private fun account(secrets: File): AccountGraph {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
        }
        server.start()
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        val wiring = AccountWiring(
            settings = MutableStateFlow(org.medianexus.music.store.Settings()), reach = MutableStateFlow(Reach()), suspect = {},
            // (What owns each account's requests, as the app has it: asked for only in a
            // build that has requests.)
            requests = {
                asked.incrementAndGet()
                owners(secrets)
            },
        )
        return AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, wiring)
    }

    /** Everything of requests that an account's folder could hold. */
    private fun ofRequests(account: AccountGraph) = account.account.dirs.data.walkTopDown().filter { it.isFile }.map { it.name }
        .filter { it.startsWith("requests") || it.startsWith("ask") || it.startsWith("arrivals") || it.startsWith("suggestions") }.toList()

    @Test
    fun `in the build and the toggle never touched, an account has a seat that says off and nothing else of requests`() = runBlocking {
        assertTrue("the build's own switch is off: this test is of requests being in the build", RequestsBuild.EXISTS)
        // (Nothing is forced: the build as it is.)
        val secrets = File(folder.root, "sealed")
        val account = account(secrets)
        var closed = false
        try {
            delay(300)
            // What owns requests was asked for a seat, once, and that is all there is: no
            // owner, no connection, no pages, no file, nothing sealed.
            assertEquals(1, asked.get())
            assertEquals(RequestsNow.Off, account.requestsNow.value)
            assertNull(account.requests)
            assertNull(account.requestsPages)
            assertEquals(0, registry!!.made)
            assertEquals(emptyList<String>(), ofRequests(account))
            assertFalse(secrets.exists())

            // Signed out again, never having turned them on: still nothing is made.
            closed = true
            account.close()
            delay(300)
            assertEquals(0, registry!!.made)
            assertEquals(emptyList<String>(), ofRequests(account))
            assertFalse(secrets.exists())
        } finally {
            if (!closed) account.close()
        }
    }

    @Test
    fun `turned on by the person, they come into being with nothing set up and nothing kept`() = runBlocking {
        val secrets = File(folder.root, "sealed")
        val account = account(secrets)
        try {
            delay(300)
            assertEquals(0, registry!!.made)
            account.turnRequestsOn()
            val requests = account.requests!!
            assertEquals(1, registry!!.made)
            assertEquals(RequestsState.NotConnected, kotlinx.coroutines.withTimeout(10_000) { requests.state.first { it != RequestsState.Off && it != RequestsState.Connecting } })
            assertFalse(File(secrets, "lidarr-key.sealed").exists())
        } finally {
            account.close()
        }
    }

    @Test
    fun `a build made without them has nothing of requests at all, not even a seat`() = runBlocking {
        RequestsBuild.forTests = false
        val secrets = File(folder.root, "sealed")
        val account = account(secrets)
        try {
            delay(300)
            assertEquals("what owns requests was asked for though the build has none", 0, asked.get())
            assertNull(account.requests)
            assertEquals(RequestsNow.Off, account.requestsNow.value)
            // Turning them on is nothing, there being nothing to turn on.
            account.turnRequestsOn()
            assertNull(account.requests)
            assertEquals(0, asked.get())
            assertEquals(emptyList<String>(), ofRequests(account))
            assertFalse(secrets.exists())
        } finally {
            account.close()
        }
    }
}
