package org.medianexus.music.requests

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.AccountGraph
import org.medianexus.music.AccountWiring
import org.medianexus.music.Accounts
import org.medianexus.music.Reach
import org.medianexus.music.SessionState
import org.medianexus.music.core.Core
import org.medianexus.music.core.StandIn
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.accountKey

/**
 * Signing out of the music server and back in, to **the same account** and by way of
 * another: **what owns an account's requests is made once and kept for the life of the
 * process** (`RequestsOwners`), and the account's own objects, for its music, never wait
 * for it. The account's objects are made as the app makes them, with the session played
 * by the test.
 */
class SameAccountAgainTest {
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)
    @get:Rule val folder = TemporaryFolder()

    private val music = MockWebServer()
    private val closing = mutableListOf<StandIn>()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val KEY = "standinkeystandinkeystandinkey12"

    private object Plain : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = plain
        override fun open(sealed: ByteArray): ByteArray = sealed
    }

    @org.junit.Before
    fun aMusicServer() {
        music.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
        }
        music.start()
    }

    @After
    fun stop() {
        RequestsBuild.forTests = null
        app.cancel()
        closing.forEach { it.close() }
        music.close()
    }

    private val secrets by lazy { File(folder.root, "sealed") }

    /** Somebody signed in to the music server: the same account each time for one name, a
     *  session of its own each time (as signing in again makes one). */
    private fun signedIn(name: String = "tester"): SessionState.SignedIn {
        val api = Subsonic(music.url("/").toString(), name, "pass", OkHttpClient(), OfflineSwitch())
        val folders = dirsOf.getOrPut(name) { AccountDirs(folder.newFolder("data-$name"), folder.newFolder("cache-$name")) }
        return SessionState.SignedIn(Account(api.server, api.username, accountKey(api.server, api.username), folders), api)
    }

    private val dirsOf = HashMap<String, AccountDirs>()

    private val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val afterwards: (suspend () -> Unit) -> Unit = { work -> app.launch { work() } }

    /** Where an account's secrets are kept (the first account's in the folder itself). */
    private fun secretsOf(account: Account) = if (account.username == "tester") secrets else File(secrets, account.username)

    /** What owns each account's requests, as the app makes it: one for the process. */
    private fun owners(): RequestsOwners = RequestsOwners(
        app,
        make = { account, sources, here, switched ->
            val requests = Requests(account, app, RequestsSecrets(secretsOf(account), Plain, account.server, account.username), afterwards = afterwards, note = { notes += it }, switched = switched)
            requests to RequestsPages.of(requests, app, account.dirs.data, sources, here, note = { notes += it }, afterwards = afterwards)
        },
        note = { notes += it },
    )

    private fun accounts(session: MutableStateFlow<SessionState>, owners: RequestsOwners = owners()): Accounts {
        RequestsBuild.forTests = true
        val make: (SessionState.SignedIn) -> AccountGraph = { now ->
            val wiring = AccountWiring(settings = MutableStateFlow(org.medianexus.music.store.Settings()), reach = MutableStateFlow(Reach()), suspect = {}, requests = { owners })
            AccountGraph(now.account, now.api, Dispatchers.IO, wiring)
        }
        return Accounts(session, { session.value }, app, make)
    }

    private fun param(link: String, name: String): String = Core.gateParam(link.substringAfter('?'), name)!!

    /** The browser, signing in on the page at `link` and coming back to the app. */
    private fun signInWith(link: String) {
        val page = URL(link).openConnection() as HttpURLConnection
        page.instanceFollowRedirects = false
        assertEquals(302, page.responseCode)
        val back = URL(page.getHeaderField("Location")).openConnection() as HttpURLConnection
        back.connectTimeout = 3_000
        back.readTimeout = 10_000
        assertEquals(200, back.responseCode)
        back.inputStream.readBytes()
    }

    private suspend fun connectedAndSignedIn(graph: AccountGraph, behind: SignInStandIn) {
        // (The person's toggle, turned on: what brings the account's requests into being.)
        if (graph.requests == null) graph.turnRequestsOn()
        val requests = graph.requests!!
        // (As a person does: the address is typed once the card has said where things
        // stand, not while the account's own first look at Lidarr is still on its way.)
        withTimeout(10_000) { requests.state.first { it != RequestsState.Off && it != RequestsState.Connecting } }
        requests.connect(behind.lidarr.address, KEY)
        signInWith(requests.signIn()!!)
        withTimeout(20_000) { requests.state.first { it is RequestsState.Connected } }
    }

    private fun tokenKept(): String? = File(secrets, "lidarr-token.sealed").takeIf { it.exists() }?.readText()?.split('\n', limit = 3)?.get(2)

    /** The account's requests, once they are there (they are not while their owner is still
     *  finishing what the last sign-in left). */
    private suspend fun requestsOf(graph: AccountGraph): Requests = withTimeout(20_000) {
        while (graph.requests == null) delay(20)
        graph.requests!!
    }

    @Test
    fun `signed out and straight back in to the same account, the account is there at once and its requests are the same owner's, once that has finished`() = runBlocking {
        val behind = SignInStandIn(closing, KEY)
        val session = MutableStateFlow<SessionState>(signedIn())
        val accounts = accounts(session)
        val first = withTimeout(10_000) { accounts.state.first { it != null } }!!
        connectedAndSignedIn(first, behind)
        val owner = first.requests!!
        val token = tokenKept()!!

        // Signed out of the music server; the sign-in service is slow to take the token
        // back; and the person signs straight back in to the same account.
        val goodbye = CountDownLatch(1)
        behind.held["/application/o/revoke/"] = goodbye
        session.value = SessionState.SignedOut
        session.value = signedIn()
        // **The account is there at once**: its music waits for nothing of requests. (The
        // player asks for it as it starts cold, and gives up after five seconds.)
        val second = withTimeout(2_000) { accounts.state.first { it != null && it !== first } }!!
        assertNotNull(withTimeout(2_000) { accounts.restored() })
        // Its requests are not yet: their owner is finishing what the last sign-in left.
        assertTrue(behind.service.awaitAsked(10_000) { it.target == "/application/o/revoke/" })
        delay(500)
        assertNull("requests were handed out while their owner was still leaving", second.requests)

        // That done, they are there: the same owner as before, never a second.
        goodbye.countDown()
        val requests = requestsOf(second)
        assertTrue("another owner was made for the account", requests === owner)
        assertEquals(listOf(token), behind.revoked.toList())
        // It starts with nothing of the last sign-in's: the key went with the password, and
        // the token was handed back.
        assertTrue(secrets.listFiles().orEmpty().none { it.name.endsWith(".sealed") })
        assertEquals(RequestsState.NotConnected, withTimeout(10_000) { requests.state.first { it != RequestsState.Off && it != RequestsState.Connecting } })
        // And what it then keeps is its own, with nobody left to take it away.
        connectedAndSignedIn(second, behind)
        val mine = tokenKept()
        assertNotNull(mine)
        delay(800)
        assertEquals(mine, tokenKept())
        assertEquals(1, behind.revoked.size)
    }

    @Test
    fun `out of one account, in and out of another, and back in to the first while it is still leaving, the first has one owner still`() = runBlocking {
        val behind = SignInStandIn(closing, KEY)
        val session = MutableStateFlow<SessionState>(signedIn())
        val accounts = accounts(session)
        val first = withTimeout(10_000) { accounts.state.first { it != null } }!!
        connectedAndSignedIn(first, behind)
        val owner = first.requests!!
        val token = tokenKept()!!
        val goodbye = CountDownLatch(1)
        behind.held["/application/o/revoke/"] = goodbye

        // Out of the first account; in to another, and out of it; back in to the first.
        session.value = SessionState.SignedOut
        session.value = signedIn("someone-else")
        val other = withTimeout(2_000) { accounts.state.first { it != null && it !== first } }!!
        assertEquals("someone-else", other.account.username)
        session.value = SessionState.SignedOut
        session.value = signedIn()
        val third = withTimeout(2_000) { accounts.state.first { it != null && it !== first && it !== other } }!!
        assertTrue(behind.service.awaitAsked(10_000) { it.target == "/application/o/revoke/" })
        delay(500)
        // While the first sign-in's goodbye is unfinished nobody else has the account's
        // requests: not a second owner beside the first.
        assertNull("requests were handed out while their owner was still leaving", third.requests)

        goodbye.countDown()
        val requests = requestsOf(third)
        assertTrue("another owner was made for the account", requests === owner)
        assertEquals(listOf(token), behind.revoked.toList())
        connectedAndSignedIn(third, behind)
        val mine = tokenKept()!!
        delay(800)
        assertEquals(mine, tokenKept())
        assertEquals(1, behind.revoked.size)
    }

    // ---- with the person's toggle off there is nothing ----------------------------------------------

    private fun requestsFiles(of: AccountGraph) = of.account.dirs.data.listFiles().orEmpty().map { it.name }.filter { it.startsWith("requests") || it.startsWith("ask") || it.startsWith("arrivals") || it.startsWith("suggestions") }.sorted()

    @Test
    fun `with the toggle never touched nothing of requests is made, turned on they come into being, and turned off they stop`() = runBlocking {
        val owners = owners()
        val session = MutableStateFlow<SessionState>(signedIn())
        val accounts = accounts(session, owners)
        val first = withTimeout(10_000) { accounts.state.first { it != null } }!!
        delay(500)
        // Signed in, the build has requests, and the person has not turned them on: no
        // owner, no object, nothing written, nothing read.
        assertEquals(RequestsNow.Off, first.requestsNow.value)
        assertNull(first.requests)
        assertNull(first.requestsPages)
        assertEquals(0, owners.made)
        assertEquals(emptyList<String>(), requestsFiles(first))
        assertFalse(secrets.exists())
        assertEquals(emptyList<String>(), notes.toList())

        // Turned on: there they are, and that they are on is a file that is there.
        first.turnRequestsOn()
        val on = first.requestsNow.value as RequestsNow.On
        assertEquals(1, owners.made)
        assertTrue(File(first.account.dirs.data, "requests.on").let { it.isFile && it.length() == 0L })
        assertTrue(on.requests.settings.value.on)

        // Turned off again: nothing is handed out, and the file is gone.
        on.requests.switch(false)
        withTimeout(10_000) { first.requestsNow.first { it == RequestsNow.Off } }
        assertNull(first.requests)
        withTimeout(10_000) { while (File(first.account.dirs.data, "requests.on").exists()) delay(20) }

        // The app started again (another process: nothing is remembered but the files),
        // with what was set up still in the account's folder and the toggle off: nothing
        // of it is made, and none of it read (it is not even looked at: a file that could
        // not be read is not noticed).
        val settings = File(first.account.dirs.data, "requests.json")
        assertTrue(settings.isFile)
        assertTrue(settings.delete())
        File(settings, "in the way").apply { parentFile.mkdirs() }.writeText("x")
        notes.clear()
        val later = owners()
        val again = MutableStateFlow<SessionState>(signedIn())
        val second = withTimeout(10_000) { accounts(again, later).state.first { it != null } }!!
        delay(500)
        assertEquals(RequestsNow.Off, second.requestsNow.value)
        assertEquals(0, later.made)
        assertEquals(emptyList<String>(), notes.toList())
    }
}
