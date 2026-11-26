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
 * Signing out of the music server and straight back in to **the same account**: there is
 * one owner of that account's requests at a time ([Accounts]). The account's objects are
 * made as the app makes them, with the session played by the test.
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
    private val dirs by lazy { AccountDirs(folder.newFolder("data"), folder.newFolder("cache")) }

    /** Somebody signed in to the music server: the same account each time, a session of its
     *  own each time (as signing in again makes one). */
    private fun signedIn(): SessionState.SignedIn {
        val api = Subsonic(music.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        return SessionState.SignedIn(Account(api.server, api.username, accountKey(api.server, api.username), dirs), api)
    }

    private fun accounts(session: MutableStateFlow<SessionState>, leaveMillis: Long? = null): Accounts {
        RequestsBuild.forTests = true
        val afterwards: (suspend () -> Unit) -> Unit = { work -> app.launch { work() } }
        val make: (SessionState.SignedIn) -> AccountGraph = { now ->
            val wiring = AccountWiring(
                settings = MutableStateFlow(org.medianexus.music.store.Settings()), reach = MutableStateFlow(Reach()), suspect = {},
                requests = { account -> Requests(account.account, account.scope, RequestsSecrets(secrets, Plain, account.account.server, account.account.username), afterwards = afterwards) },
                afterwards = afterwards,
            )
            AccountGraph(now.account, now.api, Dispatchers.IO, wiring)
        }
        return if (leaveMillis == null) Accounts(session, { session.value }, app, make) else Accounts(session, { session.value }, app, make, leaveMillis)
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
        val requests = graph.requests!!
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        signInWith(requests.signIn()!!)
        withTimeout(20_000) { requests.state.first { it is RequestsState.Connected } }
    }

    private fun tokenKept(): String? = File(secrets, "lidarr-token.sealed").takeIf { it.exists() }?.readText()?.split('\n', limit = 3)?.get(2)

    @Test
    fun `signed out and straight back in to the same account, its requests are not made again until the old ones have finished leaving`() = runBlocking {
        val behind = SignInStandIn(closing, KEY)
        val session = MutableStateFlow<SessionState>(signedIn())
        val accounts = accounts(session)
        val first = withTimeout(10_000) { accounts.state.first { it != null } }!!
        connectedAndSignedIn(first, behind)
        val token = tokenKept()!!

        // Signed out of the music server; the sign-in service is slow to take the token
        // back; and the person signs straight back in to the same account.
        val goodbye = CountDownLatch(1)
        behind.held["/application/o/revoke/"] = goodbye
        session.value = SessionState.SignedOut
        session.value = signedIn()
        assertTrue(behind.service.awaitAsked(10_000) { it.target == "/application/o/revoke/" })
        delay(700)
        // The account's objects are not made again yet: the old ones are still leaving, and
        // there is one owner of what is kept for this account at a time.
        assertNull("the account was made again while its old self was still leaving", accounts.state.value)

        // The old one's goodbye is done: now they are.
        goodbye.countDown()
        val second = withTimeout(10_000) { accounts.state.first { it != null && it !== first } }!!
        assertEquals(listOf(token), behind.revoked.toList())
        // It starts with nothing of the old one's: the key went with the password, and the
        // token was handed back.
        assertTrue(secrets.listFiles().orEmpty().none { it.name.endsWith(".sealed") })
        val requests = second.requests!!
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
    fun `an old self that takes too long to leave is cut off from what is kept, and the account is made again`() = runBlocking {
        val behind = SignInStandIn(closing, KEY)
        val session = MutableStateFlow<SessionState>(signedIn())
        val accounts = accounts(session, leaveMillis = 1_000)
        val first = withTimeout(10_000) { accounts.state.first { it != null } }!!
        connectedAndSignedIn(first, behind)
        val token = tokenKept()!!
        val goodbye = CountDownLatch(1)
        behind.held["/application/o/revoke/"] = goodbye
        val began = System.nanoTime()
        session.value = SessionState.SignedOut
        session.value = signedIn()
        // Made again when the wait is up, the old one still not done...
        val second = withTimeout(10_000) { accounts.state.first { it != null && it !== first } }!!
        val waited = (System.nanoTime() - began) / 1_000_000
        assertTrue("made again after $waited ms", waited in 900..4_000)
        // ...with nothing of the old one's kept, and the old one cut off from the files.
        assertTrue(secrets.listFiles().orEmpty().none { it.name.endsWith(".sealed") })
        connectedAndSignedIn(second, behind)
        val mine = tokenKept()!!
        // The old goodbye finishes at last: the service is told of the old token, and what
        // the account keeps now is untouched.
        goodbye.countDown()
        withTimeout(10_000) { while (behind.revoked.isEmpty()) delay(20) }
        delay(800)
        assertEquals(listOf(token), behind.revoked.toList())
        assertEquals(mine, tokenKept())
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), second.requests!!.check())
    }
}
