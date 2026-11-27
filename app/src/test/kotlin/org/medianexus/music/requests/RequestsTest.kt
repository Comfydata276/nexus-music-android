package org.medianexus.music.requests

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.core.Core
import org.medianexus.music.core.Outcome
import org.medianexus.music.core.StandIn
import org.medianexus.music.net.LocalNetwork.Nearness
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.accountKey

/**
 * An account's connection to Lidarr and the sign-in that can sit in front of it
 * ([Requests]), through the real library and the real client, against a stand-in Lidarr and
 * a stand-in sign-in service on this machine, with the test playing the browser.
 */
class RequestsTest {
    @get:Rule val folder = TemporaryFolder()

    private val closing = mutableListOf<StandIn>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val behind = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tidy() {
        scopes.forEach { it.cancel() }
        behind.cancel()
        closing.forEach { it.close() }
    }

    /** Seals nothing away, visibly: what is sealed is the plain bytes turned round, so a
     *  test can see that a secret on the disk is not lying there as it was typed. */
    private object Turned : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun open(sealed: ByteArray): ByteArray = sealed.reversedArray()
    }

    private val notes = CopyOnWriteArrayList<String>()
    private val cameBack = AtomicInteger(0)

    /** How many sign-ins are keeping the app running now (`SignInService.hold`), and how
     *  many have ever begun to. */
    private val awake = AtomicInteger(0)
    private val woken = AtomicInteger(0)

    /** The diagnostics line about the last attempt to connect or sign in, each time it was written. */
    private val traces = CopyOnWriteArrayList<String>()

    /** Whether each line written was of a failure (kept beside the last attempt). */
    private val failures = CopyOnWriteArrayList<Boolean>()
    private val KEY = "standinkeystandinkeystandinkey12"

    private fun account(name: String = "tester") =
        Account("http://music.example", name, accountKey("http://music.example", name), AccountDirs(folder.root.resolve("data-$name"), folder.root.resolve("cache-$name")))

    private fun secretsDir(account: Account) = folder.root.resolve("no-backup/requests/${account.key}")

    /** `comesForward`: whether Android lets the app bring its own screen back in front of
     *  the browser (then the screen says it has come back, as a real one does). */
    /**
     * `front`: whether the app's own screens are in front, which the test says (a real
     * screen is behind the browser while that is open; most tests here leave it in front
     * throughout, which changes nothing they look at). `comesForward`: whether Android lets
     * the app bring its own screen back in front of the browser when it asks.
     */
    private fun requests(
        account: Account = account(), waitMillis: Long = 60_000, comesForward: Boolean = true,
        front: MutableStateFlow<Boolean> = MutableStateFlow(true),
        /** How the app runs what must be finished behind whoever asked (its own work). */
        afterwards: (suspend () -> Unit) -> Unit = { work -> behind.launch { work() } },
        local: LocalGate = LocalGate.NONE,
    ): Requests {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        lateinit var made: Requests
        made = Requests(
            account, scope, RequestsSecrets(secretsDir(account), Turned, account.server, account.username),
            afterwards = afterwards,
            comeBack = {
                cameBack.incrementAndGet()
                if (comesForward) {
                    front.value = true
                    made.cameBack()
                }
            },
            awake = {
                awake.incrementAndGet()
                woken.incrementAndGet()
                val once = java.util.concurrent.atomic.AtomicBoolean(false)
                Requests.Hold({ !once.get() }) { if (once.compareAndSet(false, true)) awake.decrementAndGet() }
            },
            trace = { line, failed ->
                traces += line
                failures += failed
            },
            local = local,
            note = { notes += it }, waitMillis = waitMillis, front = front,
        )
        return made
    }

    /** A stand-in Lidarr that answers to [KEY], behind nothing. */
    private fun lidarr() = StandIn { asked, reply ->
        when {
            asked.header("X-Api-Key") != KEY -> reply.send(401)
            asked.target == "/api/v1/system/status" -> reply.json("""{"version":"2.9.6.4552"}""")
            else -> reply.send(404)
        }
    }.also { closing += it }

    /** A stand-in sign-in service with a stand-in Lidarr behind it (see [SignInStandIn]). */
    private fun Behind(): SignInStandIn = SignInStandIn(closing, KEY)

    /** What the browser does: asks for an address, and says what page it was shown. */
    private fun browse(address: String): Pair<Int, String> {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        val status = connection.responseCode
        val page = (if (status < 400) connection.inputStream else connection.errorStream)?.readBytes()?.decodeToString().orEmpty()
        return status to page
    }

    private fun param(link: String, name: String): String = Core.gateParam(link.substringAfter('?'), name)!!

    /** Where the sign-in service sends the browser once someone has signed in on the page
     *  at `link`: back to the app, with a code. */
    private fun signedInAt(link: String): String {
        val connection = URL(link).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        assertEquals(302, connection.responseCode)
        return connection.getHeaderField("Location")
    }

    /** The browser, signing in: the page at `link`, then wherever the service sends it. It
     *  says what page the app showed it at the end. */
    private fun signInWith(link: String): Pair<Int, String> = browse(signedInAt(link))

    private suspend fun Requests.until(what: (RequestsState) -> Boolean): RequestsState = withTimeout(20_000) { state.first(what) }

    private fun everythingKept(account: Account): String =
        (account.dirs.data.walkTopDown() + account.dirs.cache.walkTopDown()).filter { it.isFile }.joinToString("\n") { it.readBytes().decodeToString() }

    // ---- off, and a key ----------------------------------------------------------------------------

    @Test
    fun `requests are off until switched on, and off nothing is asked of anyone or kept`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        val requests = requests(account)
        requests.start()
        assertEquals(RequestsState.Off, requests.until { true })
        assertNull(requests.lidarr())
        assertEquals(RequestsState.Off, requests.check())
        assertTrue(lidarr.asked.isEmpty())
        assertFalse(File(account.dirs.data, "requests.json").exists())

        // Switched on with nothing set up: still nothing to ask.
        assertEquals(RequestsState.NotConnected, requests.switch(true))
        assertTrue(lidarr.asked.isEmpty())
    }

    @Test
    fun `an address and a key connect, and the key is sealed away from everything else`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), requests.connect("${lidarr.address}/ ", " $KEY\n"))
        assertEquals("GET /api/v1/system/status", lidarr.asked.single().line)
        assertEquals(KEY, lidarr.asked.single().header("X-Api-Key"))
        assertEquals(KEY.takeLast(4), requests.keyHint().takeLast(4))
        assertFalse(KEY in requests.keyHint())

        // Where the key is, and where it isn't.
        assertFalse(KEY in everythingKept(account))
        assertFalse(notes.any { KEY in it })
        val sealed = File(secretsDir(account), "lidarr-key.sealed").readBytes()
        assertFalse(KEY in sealed.decodeToString())
        assertTrue(KEY in sealed.reversedArray().decodeToString())
        assertTrue(lidarr.address in File(account.dirs.data, "requests.json").readText())

        // The app started again: connected from what is kept, with nothing typed.
        val again = requests(account)
        again.start()
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), again.until { it is RequestsState.Connected })
        // Another account on this phone can't open it.
        val other = requests(account("someone-else"))
        other.switch(true)
        assertEquals(RequestsState.NotConnected, other.connect(lidarr.address, ""))
    }

    @Test
    fun `what goes wrong is said in the library's own sentences`() = runBlocking {
        val lidarr = lidarr()
        val requests = requests()
        requests.switch(true)
        assertEquals(RequestsState.Unreachable("Lidarr turned the request away. Check the API key."), requests.connect(lidarr.address, "wrong"))
        val page = StandIn { _, reply -> reply.send(200, "<html>a router</html>") }.also { closing += it }
        assertEquals(RequestsState.Unreachable("That address answered, but not as Lidarr. Check the address."), requests.connect(page.address, KEY))
        val gone = StandIn { _, _ -> }.also { it.close() }
        assertEquals(RequestsState.Unreachable("Couldn't reach Lidarr. The connection was refused."), requests.connect(gone.address, KEY))
    }

    @Test
    fun `a key is only ever sent to the host it was typed in for`() = runBlocking {
        val lidarr = lidarr()
        val elsewhere = StandIn { _, reply -> reply.json("""{"version":"9"}""") }.also { closing += it }
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        // The same host, another way of writing the address: the key stays.
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), requests.connect("${lidarr.address}/", ""))
        // Another host, and no key typed: the one that was kept is forgotten, not sent.
        val other = elsewhere.address.replace("127.0.0.1", "localhost")
        assertEquals(RequestsState.KeyForgotten, requests.connect(other, ""))
        assertTrue(elsewhere.asked.isEmpty())
        assertFalse(File(secretsDir(account), "lidarr-key.sealed").exists())
        assertNull(requests.lidarr())
    }

    // ---- a secret and the place it is for ---------------------------------------------------------

    /** Somewhere that is not Lidarr, which answers anything as Lidarr would and so shows what
     *  it was sent. */
    private fun elsewhere() = StandIn { _, reply -> reply.json("""{"version":"9"}""") }.also { closing += it }

    private fun settingsFile(account: Account) = File(account.dirs.data, "requests.json")

    @Test
    fun `the app stopped between keeping a new address and forgetting the old key sends that key nowhere`() = runBlocking {
        val lidarr = lidarr()
        val other = elsewhere()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), requests.connect(lidarr.address, KEY))
        // Stopped just there: the new address is written, and the key that is kept is still
        // the one for the old address.
        scopes.last().cancel()
        settingsFile(account).let { it.writeText(it.readText().replace(lidarr.address, other.address)) }

        val again = requests(account)
        again.start()
        assertEquals(RequestsState.KeyForgotten, again.until { it != RequestsState.Off && it != RequestsState.Connecting })
        assertNull(again.lidarr())
        assertEquals(RequestsState.KeyForgotten, again.check())
        Thread.sleep(300)
        assertTrue("the other address was sent ${other.asked.map { it.line }}", other.asked.isEmpty())
        // And it is gone for good: a key is typed again for the new place.
        assertFalse(File(secretsDir(account), "lidarr-key.sealed").exists())
        assertEquals("", again.keyHint())
    }

    @Test
    fun `the same host on another port, or by another scheme, is another place and the key does not go there`() = runBlocking {
        val lidarr = lidarr()
        val other = elsewhere()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        // (Both stand-ins are 127.0.0.1: only the port differs.)
        assertEquals(RequestsState.KeyForgotten, requests.connect(other.address, ""))
        assertTrue(other.asked.isEmpty())
        assertFalse(File(secretsDir(account), "lidarr-key.sealed").exists())

        requests.connect(lidarr.address, KEY)
        assertEquals(RequestsState.KeyForgotten, requests.connect(lidarr.address.replace("http://", "https://"), ""))
        assertEquals(1, lidarr.asked.count { it.header("X-Api-Key") == KEY } - 1)
    }

    @Test
    fun `an address that reads as one place and is another to the client is refused, in the library's words, and changes nothing`() = runBlocking {
        val lidarr = lidarr()
        val other = elsewhere()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        // To the eye, and to a reader that splits at the "@", this is still the first address.
        // The client would connect to what is before the backslash. The library's rule for
        // an address refuses anything two readers might take differently.
        val port = lidarr.address.substringAfterLast(':')
        val looks = "${other.address}\\@127.0.0.1:$port"
        val unusable = "That isn't an address the app can use. Enter it as, say, https://lidarr.example.org or 192.168.1.5:8686."
        assertEquals(RequestsState.Unreachable(unusable), requests.connect(looks, ""))
        assertTrue(other.asked.isEmpty())
        // Nothing of it was kept, and what was there before stands: the address, and its key.
        assertEquals(lidarr.address, requests.settings.value.address)
        assertTrue(File(secretsDir(account), "lidarr-key.sealed").exists())
        // The other things the library's rule refuses, each asked of nobody.
        for (odd in listOf("http://lidarr .example:8686", "ftp://lidarr.example", "http://lidarr.example:port", "http://lidarr.example:0", "http://lïdarr.example")) {
            assertEquals(odd, RequestsState.Unreachable(unusable), requests.connect(odd, KEY))
        }
        assertEquals(lidarr.address, requests.settings.value.address)
    }

    @Test
    fun `an address with a name and password in it is refused, and nothing of it is kept`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        val named = lidarr.address.replace("http://", "http://admin:hunter2@")
        assertEquals(RequestsState.Unreachable("That address has a name and password in it. Enter the address alone: Lidarr is reached with its API key."), requests.connect(named, KEY))
        assertTrue(lidarr.asked.isEmpty())
        assertFalse("hunter2" in everythingKept(account))
        assertEquals("", requests.settings.value.address)
        assertFalse(File(secretsDir(account), "lidarr-key.sealed").exists())
        assertFalse(notes.any { "hunter2" in it })
    }

    @Test
    fun `a key found kept for another place than the address, however that came about, is forgotten and not sent`() = runBlocking {
        val lidarr = lidarr()
        val other = elsewhere()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        scopes.last().cancel()
        val before = lidarr.asked.size
        // The other way round: a new key was kept, for a new address, and the write of the
        // address is what never happened. The settings still say the old one.
        val secrets = RequestsSecrets(secretsDir(account), Turned, account.server, account.username)
        assertTrue(secrets.keepKey(Core.lidarrPlace(other.address)!!, "a-key-for-the-other-place"))

        val again = requests(account)
        again.start()
        assertEquals(RequestsState.KeyForgotten, again.until { it != RequestsState.Off && it != RequestsState.Connecting })
        Thread.sleep(300)
        assertEquals(before, lidarr.asked.size)
        assertTrue(other.asked.isEmpty())
        assertFalse(File(secretsDir(account), "lidarr-key.sealed").exists())
        // A key typed for where Lidarr is puts it right.
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), again.connect(lidarr.address, KEY))
    }

    @Test
    fun `a key kept without the place it is for is no key`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        scopes.last().cancel()
        val before = lidarr.asked.size
        // (As a build before this rule would have kept it: the account, then the key.)
        File(secretsDir(account), "lidarr-key.sealed").writeBytes(Turned.seal("${account.key}\n$KEY".toByteArray()))

        val again = requests(account)
        again.start()
        assertEquals(RequestsState.NotConnected, again.until { it != RequestsState.Off && it != RequestsState.Connecting })
        assertNull(again.lidarr())
        assertEquals(before, lidarr.asked.size)
    }

    @Test
    fun `a sign-in's token goes to the service it was given by and to no other`() = runBlocking {
        val behind = Behind()
        val other = elsewhere()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        signInWith(link)
        requests.until { it is RequestsState.Connected }
        scopes.last().cancel()
        val sealed = File(secretsDir(account), "lidarr-token.sealed").readBytes().reversedArray().decodeToString().split('\n', limit = 3)
        assertEquals(Core.servicePlace(behind.service.address), sealed[1])
        // What is kept now says the sign-in service is somewhere else (written by whatever
        // means): the token that is kept was not given by that one.
        settingsFile(account).let { it.writeText(it.readText().replace(behind.service.address, other.address)) }

        val again = requests(account)
        again.start()
        // Lidarr still answers that it is behind the real one: nobody is signed in to it.
        assertEquals(RequestsState.NeedsSignIn(behind.host), again.until { it is RequestsState.NeedsSignIn })
        Thread.sleep(300)
        assertTrue("the other service was sent ${other.asked.map { it.line }}", other.asked.isEmpty())
        assertFalse(File(secretsDir(account), "lidarr-token.sealed").exists())
        // And nobody is written down as signed in any more.
        assertEquals("", again.settings.value.signedIn)
        assertFalse("\"walker\"" in settingsFile(account).readText())
    }

    @Test
    fun `a token kept without the service it is for is no token, and nobody is written down as signed in`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        signInWith(requests.signIn()!!)
        requests.until { it is RequestsState.Connected }
        scopes.last().cancel()
        val token = tokenKept(account)!!
        val asked = behind.service.asked.size
        // (As a build before this rule would have kept it: the account, then the token.)
        File(secretsDir(account), "lidarr-token.sealed").writeBytes(Turned.seal("${account.key}\n$token".toByteArray()))

        val again = requests(account)
        again.start()
        assertEquals(RequestsState.NeedsSignIn(behind.host), again.until { it is RequestsState.NeedsSignIn })
        assertEquals("", again.settings.value.signedIn)
        assertFalse("\"walker\"" in settingsFile(account).readText())
        assertEquals("the service was sent ${behind.service.asked.drop(asked).map { it.line }}", asked, behind.service.asked.size)
    }

    @Test
    fun `a sign-in service found at the music server's own address is not kept, not signed in to, and no browser is sent there`() = runBlocking {
        val behind = Behind()
        // (This account's music server is where the "sign-in service" answers.)
        val account = Account(behind.service.address, "tester", accountKey(behind.service.address, "tester"), AccountDirs(folder.root.resolve("data-m"), folder.root.resolve("cache-m")))
        val requests = requests(account)
        requests.switch(true)
        assertEquals(RequestsState.Unreachable("The sign-in in front of Lidarr gave an address the app can't use."), requests.connect(behind.lidarr.address, KEY))
        assertNull(requests.signIn())
        assertEquals("", requests.settings.value.service)
        assertTrue("the music server's address was sent ${behind.service.asked.map { it.line }}", behind.service.asked.isEmpty())
    }

    // ---- a sign-in is believed only on https, or where Lidarr's own address is ----------------------
    //
    // (The library's rule, `gate::Found::trusted`, from the Windows app's 0.40.11. Here the
    // sign-in service is on this machine by another name than Lidarr is: plain http, and
    // to the rule another host.)

    private fun refusedAt(host: String) =
        "The sign-in at $host is refused because it isn't on https, or where Lidarr's own address is, so signing in would cross the network unprotected: " +
            "put the sign-in on https, or, with Lidarr on plain http, on Lidarr's own host."

    @Test
    fun `a sign-in found on plain http away from Lidarr's own host is refused in the library's words, and nothing is sent there`() = runBlocking {
        val behind = Behind()
        behind.named = behind.service.address.replace("127.0.0.1", "localhost")
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        assertEquals(RequestsState.Unreachable(refusedAt(behind.named!!.removePrefix("http://"))), requests.connect(behind.lidarr.address, KEY))
        // Not kept, not signed in to, and no browser is sent there.
        assertEquals("", requests.settings.value.service)
        assertNull(requests.signIn())
        assertTrue("the service was sent ${behind.service.asked.map { it.line }}", behind.service.asked.isEmpty())
    }

    @Test
    fun `a sign-in remembered at an address the rule refuses is not signed in to, sent no token, and handed nothing back`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        signInWith(requests.signIn()!!)
        requests.until { it is RequestsState.Connected }
        scopes.last().cancel()
        // What is kept now names the service by another host (as a build before the rule
        // could have kept it, from a Lidarr that pointed there): the settings, and the
        // token sealed for that place. Lidarr's sign-in points there too.
        val elsewhere = behind.service.address.replace("127.0.0.1", "localhost")
        val token = tokenKept(account)!!
        settingsFile(account).let { it.writeText(it.readText().replace(behind.service.address, elsewhere)) }
        File(secretsDir(account), "lidarr-token.sealed").writeBytes(Turned.seal("${account.key}\n${Core.servicePlace(elsewhere)}\n$token".toByteArray()))
        behind.named = elsewhere
        val asked = behind.service.asked.size
        fun sent() = behind.service.asked.drop(asked).map { it.line }

        val again = requests(account)
        again.start()
        // Said in the library's words; and the token was not shown to the service.
        val refused = RequestsState.Unreachable(refusedAt(elsewhere.removePrefix("http://")))
        assertEquals(refused, again.until { it is RequestsState.Unreachable })
        assertEquals(refused, again.check())
        assertEquals("the service was sent ${sent()}", emptyList<String>(), sent())
        // No browser is sent there, and the state says why.
        assertNull(again.signIn())
        assertEquals(refused, again.state.value)
        // Signed out of: the token is forgotten here, and nothing is handed to that address.
        again.signOut()
        delay(500)
        assertFalse(File(secretsDir(account), "lidarr-token.sealed").exists())
        assertEquals("the service was sent ${sent()}", emptyList<String>(), sent())
    }

    @Test
    fun `the name a sign-in service gives is kept short and printable`() = runBlocking {
        val behind = Behind()
        // (A bell, a new line, a right-to-left override, and far too much of it.)
        behind.username = "The\\u0007 Wal\\nker\\u202e " + "x".repeat(500)
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        signInWith(link)
        val connected = requests.until { it is RequestsState.Connected } as RequestsState.Connected
        val name = connected.signedInAs!!
        assertEquals(80, name.length)
        assertTrue(name, name.startsWith("The Wal ker x"))
        assertEquals(name, requests.settings.value.signedIn)
        assertTrue(settingsFile(account).readText().lines().size == 1)
    }

    // ---- behind a sign-in --------------------------------------------------------------------------

    @Test
    fun `behind a sign-in, signing in with the browser connects and keeps only a token`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.connect(behind.lidarr.address, KEY))

        val link = requests.signIn()!!
        assertEquals(RequestsState.SigningIn(behind.host), requests.state.value)
        assertTrue(link, link.startsWith("${behind.service.address}/application/o/authorize/?response_type=code&client_id=nexus-music&redirect_uri=http%3A%2F%2F127.0.0.1%3A"))
        val back = param(link, "redirect_uri")
        assertTrue(back, Regex("""http://127\.0\.0\.1:\d+/callback""").matches(back))
        assertEquals("S256", param(link, "code_challenge_method"))

        // Somebody else knocks first: a page of the browser's without the state, another
        // app with a guess. Neither gets in, and the wait goes on.
        assertEquals(404, browse("$back?code=stolen").first)
        assertEquals(404, browse("$back?code=stolen&state=a-guess").first)
        assertEquals(404, browse("${back.removeSuffix("/callback")}/?state=${param(link, "state")}").first)
        assertEquals(RequestsState.SigningIn(behind.host), requests.state.value)

        // The browser comes back.
        val home = signedInAt(link)
        val code = param(home, "code")
        assertEquals("$back?code=$code&state=${param(link, "state")}", home)
        val (status, page) = browse(home)
        assertEquals(200, status)
        assertTrue(page, "You're signed in" in page)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected })
        assertEquals(1, cameBack.get())
        assertTrue(requests.settings.value.let { it.signedIn == "walker" && !it.signingIn })

        // What was asked of the service for it: the code, with the secret only this app
        // had, whose check was in the link.
        val trade = behind.service.asked.first { "grant_type=authorization_code" in it.body }
        assertTrue(trade.body, "code=$code" in trade.body && "redirect_uri=${java.net.URLEncoder.encode(back, "UTF-8")}" in trade.body)
        val secret = Core.gateParam(trade.body, "code_verifier")!!
        val check = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
        assertEquals(check, param(link, "code_challenge"))

        // Only a token is kept, sealed, and it is nowhere else.
        val token = File(secretsDir(account), "lidarr-token.sealed").readBytes().reversedArray().decodeToString().split('\n', limit = 3)[2]
        assertTrue(token, token.startsWith("refresh-"))
        val kept = everythingKept(account)
        for (secretThing in listOf(token, secret, code, KEY) + behind.passes) assertFalse(secretThing, secretThing in kept)
        assertFalse(notes.any { note -> listOf(token, secret, code, KEY).any { it in note } })
        // The door is shut.
        assertTrue(runCatching { browse("$back?code=again&state=${param(link, "state")}") }.isFailure)
    }

    @Test
    fun `the token is replaced on the phone each time the service replaces it, and a restart carries on from the newest`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        signInWith(link)
        requests.until { it is RequestsState.Connected }
        fun onDisk() = File(secretsDir(account), "lidarr-token.sealed").readBytes().reversedArray().decodeToString().split('\n', limit = 3)[2]
        val first = onDisk()

        // The app started again: no browser, and the first request renews the token.
        val again = requests(account)
        again.start()
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), again.until { it is RequestsState.Connected })
        val second = onDisk()
        assertTrue("$first then $second", second != first && second.startsWith("refresh-"))
        assertTrue(behind.service.asked.any { it.body == "grant_type=refresh_token&refresh_token=$first&client_id=nexus-music" })
    }

    @Test
    fun `a sign-in the service turns down, one nobody finishes and one whose tab is closed each end plainly`() = runBlocking {
        val behind = Behind()
        val requests = requests(waitMillis = 700)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)

        // Turned down.
        var link = requests.signIn()!!
        val (status, page) = browse("${param(link, "redirect_uri")}?error=access_denied&error_description=The+walker+said+no&state=${param(link, "state")}")
        assertEquals(200, status)
        assertTrue(page, "The sign-in didn't go through" in page)
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "The sign-in service turned the sign-in down: The walker said no"), requests.until { it is RequestsState.NeedsSignIn })

        // Nobody comes back in time.
        link = requests.signIn()!!
        assertEquals(RequestsState.SigningIn(behind.host), requests.state.value)
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "Nobody signed in within five minutes. Try again when you're ready."), requests.until { it is RequestsState.NeedsSignIn })

        // The service won't finish it (the code was no good).
        behind.refusing.set(true)
        link = requests.signIn()!!
        // (The browser is not told it is signed in when it is not.)
        val unfinished = browse("${param(link, "redirect_uri")}?code=bad&state=${param(link, "state")}")
        assertTrue(unfinished.second, "The sign-in didn't go through" in unfinished.second && "You're signed in" !in unfinished.second)
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "The sign-in service wouldn't finish the sign-in. Try again."), requests.until { it is RequestsState.NeedsSignIn })
        behind.refusing.set(false)
        assertFalse(requests.settings.value.signingIn)
    }

    @Test
    fun `the browser is shown its page when the token is in the app's keeping, and not before`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        // The service takes its time over the trade of the code for a token.
        val slow = java.util.concurrent.CountDownLatch(1)
        behind.held["grant_type=authorization_code"] = slow
        val home = signedInAt(link)
        val page = java.util.concurrent.CompletableFuture.supplyAsync { browse(home) }
        assertTrue(behind.service.awaitAsked(10_000) { "grant_type=authorization_code" in it.body })
        Thread.sleep(500)
        assertFalse("the browser was shown a page before the sign-in was finished", page.isDone)
        // (The app has asked to come forward already: in front is where it can trade.)
        assertEquals(1, cameBack.get())
        assertNull(tokenKept(account))
        slow.countDown()
        val (status, shown) = page.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(200, status)
        assertTrue(shown, "You're signed in" in shown)
        // By the time the browser has its page the token is kept.
        assertNotNull(tokenKept(account))
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected })
        assertEquals(1, cameBack.get())
    }

    @Test
    fun `leaving is finished whatever goes wrong with the goodbye, and the key and the token are gone`() = runBlocking {
        for (broken in listOf("the app's own work throws", "the app's own work never runs it")) {
            val service = Behind()
            val account = account("leaver-${broken.length}")
            var breaking = false
            val requests = requests(account, afterwards = { work ->
                when {
                    !breaking -> behind.launch { work() }
                    broken.endsWith("throws") -> throw IllegalStateException("the app's own work is gone")
                    else -> Unit
                }
            })
            requests.switch(true)
            requests.connect(service.lidarr.address, KEY)
            signInWith(requests.signIn()!!)
            requests.until { it is RequestsState.Connected }
            assertEquals(listOf("lidarr-key.sealed", "lidarr-token.sealed"), secretsDir(account).listFiles().orEmpty().map { it.name }.sorted())
            // The account signs out of the music server, and saying goodbye to the sign-in
            // service goes wrong on the app's side. Leaving still ends, and still takes the
            // key and the token off the phone: whoever waits for it is not left waiting.
            breaking = true
            val left = withTimeout(30_000) { runCatching { requests.leave() } }
            assertTrue("$broken: ${left.exceptionOrNull()}", left.isSuccess)
            assertEquals(broken, emptyList<String>(), secretsDir(account).listFiles().orEmpty().map { it.name })
            assertNull(broken, requests.lidarr())
        }
    }

    @Test
    fun `finishing a sign-in has a limit on the whole of it, however many requests it takes`() = runBlocking {
        val behind = Behind()
        val requests = requests()
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        // The service takes a second over each thing asked of it: within the limit for one
        // request (a second and a half, here), every time. Finishing takes two of them.
        org.medianexus.music.core.CoreHttp.signInMillis = 1_500
        org.medianexus.music.core.CoreHttp.signInWholeMillis = 1_600
        try {
            val at = signedInAt(link)
            behind.slowMillis = 1_000
            val began = System.nanoTime()
            val (status, _) = browse(at)
            val took = (System.nanoTime() - began) / 1_000_000
            assertEquals(200, status)
            // The browser has its page within the limit on the whole (and a little), not
            // after as many times the limit on one as there were requests.
            assertTrue("the browser waited $took ms", took in 900..1_950)
        } finally {
            behind.slowMillis = 0
            org.medianexus.music.core.CoreHttp.signInMillis = 8_000
            org.medianexus.music.core.CoreHttp.signInWholeMillis = 12_000
        }
    }

    @Test
    fun `Lidarr typed at the music server's own origin and behind a sign-in is found behind it, and signed in to`() = runBlocking {
        val behind = Behind()
        // (This account's music server is at the very origin Lidarr is typed at: one proxy
        // in front of both, each under a path of its own.)
        val account = Account(behind.lidarr.address, "tester", accountKey(behind.lidarr.address, "tester"), AccountDirs(folder.root.resolve("data-o"), folder.root.resolve("cache-o")))
        val requests = requests(account)
        requests.switch(true)
        // Lidarr's own address answers with the sign-in; finding which one asks that
        // address again, which is still Lidarr's API where the person said it is.
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.connect(behind.lidarr.address, KEY))
        signInWith(requests.signIn()!!)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected })
    }

    @Test
    fun `a sign-in service that never finishes the trade is given a few seconds, and the browser is told it didn't go through`() = runBlocking {
        val behind = Behind()
        val requests = requests()
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        org.medianexus.music.core.CoreHttp.signInMillis = 700
        try {
            behind.held["grant_type=authorization_code"] = java.util.concurrent.CountDownLatch(1)
            val began = System.nanoTime()
            val (status, shown) = signInWith(link)
            val took = (System.nanoTime() - began) / 1_000_000
            assertEquals(200, status)
            assertTrue(shown, "The sign-in didn't go through" in shown)
            assertTrue("the browser waited $took ms", took in 600..4_000)
            val state = requests.until { it is RequestsState.NeedsSignIn && it.why != null } as RequestsState.NeedsSignIn
            assertEquals("Couldn't reach the sign-in service. It didn't answer in time.", state.why)
        } finally {
            org.medianexus.music.core.CoreHttp.signInMillis = 8_000
            behind.held.values.forEach { it.countDown() }
        }
    }

    private fun trades(behind: SignInStandIn) = behind.service.asked.count { "grant_type=authorization_code" in it.body }

    @Test
    fun `the code is traded when the app's own screen is in front, and not while it is behind the browser`() = runBlocking<Unit> {
        val behind = Behind()
        val account = account()
        // (Android will not let this app come forward by itself: the browser has a window
        // of its own. An app out of sight has no network on Android 15 and later, so a
        // trade begun there would go nowhere, or worse, half-way.)
        val front = MutableStateFlow(true)
        val requests = requests(account, comesForward = false, front = front)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        front.value = false
        val page = java.util.concurrent.CompletableFuture.supplyAsync { browse(signedInAt(link)) }
        // The app asks to come forward, and is not brought. The browser is told to send the
        // person back; nothing is offered to the service.
        val (status, shown) = page.get(15, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(200, status)
        // (The library's page for a sign-in the app has still to finish.)
        assertTrue(shown, "Still signing in" in shown && "Go back to Nexus Music to see whether it went through." in shown)
        assertTrue(shown, "You're signed in" !in shown && "didn't go through" !in shown)
        assertEquals(1, cameBack.get())
        delay(700)
        assertEquals(0, trades(behind))
        assertEquals(RequestsState.SigningIn(behind.host), requests.state.value)
        assertNull(tokenKept(account))
        // The person goes back to the app: now it is traded, once.
        front.value = true
        requests.cameBack()
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
        assertEquals(1, trades(behind))
        assertNotNull(tokenKept(account))
    }

    @Test
    fun `a code is offered to the service once, whatever comes of it`() = runBlocking<Unit> {
        val behind = Behind()
        val front = MutableStateFlow(true)
        val requests = requests(comesForward = false, front = front)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        // The service takes the code and then will not finish (it has used the code up).
        behind.refusing.set(true)
        browse(signedInAt(link))
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "The sign-in service wouldn't finish the sign-in. Try again."), requests.until { it is RequestsState.NeedsSignIn && it.why != null })
        assertEquals(1, trades(behind))
        // Leaving the app and coming back to it does not offer the code again.
        behind.refusing.set(false)
        front.value = false
        front.value = true
        requests.cameBack()
        delay(2_500)
        assertEquals(1, trades(behind))
        assertTrue(requests.state.value is RequestsState.NeedsSignIn)
    }

    @Test
    fun `nobody comes back to the app, the sign-in is given up at its end and the code was never offered`() = runBlocking<Unit> {
        val behind = Behind()
        val front = MutableStateFlow(true)
        val requests = requests(waitMillis = 4_000, comesForward = false, front = front)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        front.value = false
        browse(signedInAt(link))
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "Nobody signed in within five minutes. Try again when you're ready."), requests.until { it is RequestsState.NeedsSignIn })
        assertEquals(0, trades(behind))
    }

    @Test
    fun `a sign-in whose token this phone can't keep is no sign-in, and the browser is not told it is`() = runBlocking<Unit> {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        // (Where the token would be kept there is now something that can't be written over.)
        File(secretsDir(account), "lidarr-token.sealed").mkdirs()
        val (_, shown) = browse(signedInAt(link))
        assertTrue(shown, "You're signed in" !in shown)
        val state = requests.until { it is RequestsState.NeedsSignIn && it.why != null } as RequestsState.NeedsSignIn
        assertEquals("The sign-in went through, but this phone couldn't keep it. Try again.", state.why)
        // The token is handed straight back: nobody holds it.
        withTimeout(10_000) { while (behind.revoked.isEmpty()) delay(20) }
        assertNull(requests.lidarr()?.session)
    }

    @Test
    fun `a sign-in that is remembered and not signed in to is never shown as signed in`() = runBlocking<Unit> {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        signInWith(requests.signIn()!!)
        requests.until { it is RequestsState.Connected }
        scopes.last().cancel()
        // The token is gone, and the sign-in in front of Lidarr has been taken away since:
        // Lidarr answers as itself. Connected, and nobody is signed in to anything.
        File(secretsDir(account), "lidarr-token.sealed").delete()
        behind.unguarded.set(true)
        val again = requests(account)
        again.start()
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), again.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
    }

    @Test
    fun `a code from one sign-in brought back to another gets no token`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        // Someone signs in on the page of a first sign-in, which is then given up...
        val first = requests.signIn()!!
        val stale = param(signedInAt(first), "code")
        requests.cancelSignIn()
        // ...and its code turns up at the door of a second (whose state somebody has seen).
        val second = requests.signIn()!!
        browse("${param(second, "redirect_uri")}?code=$stale&state=${param(second, "state")}")
        // The service checks the code against the secret it was asked for with, and the
        // second sign-in's secret is another: no token.
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "The sign-in service wouldn't finish the sign-in. Try again."), requests.until { it is RequestsState.NeedsSignIn && it.why != null })
        assertFalse(File(secretsDir(account), "lidarr-token.sealed").exists())
        assertTrue(behind.passes.isEmpty())
    }

    /**
     * **0.6.0's sign-in that timed out.** The browser comes back to the app's door on the
     * loopback address, which only a running app answers; behind the browser and with
     * nothing in the foreground, Android froze the app, and the browser's return hung until
     * it gave up. The app is kept running for as long as a sign-in waits, from its first
     * moment (the screen is in front then, and Android allows it only then), and let go
     * however the wait ends.
     */
    @Test
    fun `the app is kept running while a sign-in waits for the browser, and let go however it ends`() = runBlocking {
        val behind = Behind()
        val front = MutableStateFlow(true)
        val requests = requests(waitMillis = 1_500, front = front)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        assertEquals(0, woken.get())

        // Signed in: held from the moment the sign-in begins until it has ended.
        val link = requests.signIn()!!
        assertEquals(1, awake.get())
        front.value = false
        signInWith(link)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
        withTimeout(5_000) { while (awake.get() != 0) delay(20) }

        // Nobody comes back in time.
        requests.signOut()
        requests.signIn()!!
        assertEquals(1, awake.get())
        requests.until { it is RequestsState.NeedsSignIn && it.why != null }
        withTimeout(5_000) { while (awake.get() != 0) delay(20) }

        // Given up by hand, and one sign-in begun over another.
        requests.signIn()!!
        requests.signIn()!!
        assertTrue(awake.get() in 1..2)
        requests.cancelSignIn()
        withTimeout(5_000) { while (awake.get() != 0) delay(20) }
        assertEquals(4, woken.get())
    }

    /**
     * The diagnostics line about the last attempt says where it ended, in fixed words, and
     * **carries nothing of the attempt's own**: not Lidarr's address or the sign-in's, not
     * the key, the code, the state, the check of the secret or the token, though this attempt
     * had every one of them.
     */
    @Test
    fun `the line about the last attempt says where it ended and holds no address, key, code, state or token`() = runBlocking {
        val behind = Behind()
        val account = account()
        val front = MutableStateFlow(true)
        val requests = requests(account, front = front)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} finding the sign-in, sign-in found, "))
        assertFalse(failures.last())

        // Signed in, after a moment at the page with the app behind the browser.
        var link = requests.signIn()!!
        front.value = false
        val back = signedInAt(link)
        browse(back)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
        withTimeout(5_000) { while (!traces.last().contains("first look signed in")) delay(20) }
        val signedIn = traces.last()
        assertTrue(signedIn, signedIn.startsWith("${Trace.PREFIX} first look signed in, connected, "))
        assertFalse(failures.last())
        assertTrue(signedIn, "; browser came back after 0 s, app in front: no; kept running: yes; address: an IPv4 number; connected over IPv4" in signedIn)
        val secrets = mutableListOf(KEY, param(link, "state"), param(link, "code_challenge"), Core.gateParam(back.substringAfter('?'), "code")!!, tokenKept(account)!!)

        // The service won't finish the next one: where it ended, and how.
        requests.signOut()
        behind.refusing.set(true)
        link = requests.signIn()!!
        front.value = true
        val refused = signedInAt(link)
        browse(refused)
        requests.until { it is RequestsState.NeedsSignIn && it.why != null }
        withTimeout(5_000) { while (!traces.last().contains("finishing the sign-in")) delay(20) }
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} finishing the sign-in, answered 400, "))
        assertTrue(failures.last())
        secrets += listOf(param(link, "state"), param(link, "code_challenge"), Core.gateParam(refused.substringAfter('?'), "code")!!)

        // Lidarr turned away; nobody at all where it was.
        requests.connect(behind.lidarr.address, "wrongkeywrongkeywrongkeywrongkey")
        val dead = java.net.ServerSocket(0).use { it.localPort }
        requests.connect("http://127.0.0.1:$dead", KEY)
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} checking Lidarr, connection refused, "))
        assertTrue(failures.last())

        val places = listOf(behind.lidarr.address, behind.host, behind.service.address, "127.0.0.1", ":$dead", "wrongkey").map { it.removePrefix("http://") }
        for (line in traces) {
            for (secret in secrets + places) assertFalse("$line holds $secret", secret in line)
        }
    }

    /**
     * **0.6.1 on the owner's phone**: "Couldn't reach Lidarr. It didn't answer in time.", then
     * "Try again", and the export had no line about it, because only Connect wrote one. Every
     * check writes it now, whoever asked (Try again, the app starting), and says how far the
     * last request's connection got.
     */
    @Test
    fun `try again and the app starting write the line too, with how far the connection got`() = runBlocking {
        val dead = java.net.ServerSocket(0).use { it.localPort }
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect("http://127.0.0.1:$dead", KEY)
        val before = traces.size

        // Try again.
        requests.check()
        assertEquals(before + 1, traces.size)
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} checking Lidarr, connection refused, "))
        assertTrue(traces.last(), traces.last().endsWith("; last request: IPv4 failed while connecting"))

        // The app starting again, with requests set up as they were.
        val again = requests(account)
        again.start()
        withTimeout(10_000) { while (traces.size < before + 2) delay(20) }
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} checking Lidarr, connection refused, "))
    }

    /** Android 17's permission as a test says it is: where each address is, whether it is
     *  held, and its being given. */
    private class Gate(var near: (String) -> Nearness?, @Volatile var holds: Boolean = false) : LocalGate {
        val given = MutableStateFlow(0)
        val looked = AtomicInteger(0)
        override suspend fun near(address: String): Nearness? = near.invoke(address).also { looked.incrementAndGet() }
        override val held: Boolean get() = holds
        override val allowed: Flow<Int> = given

        fun give() {
            holds = true
            given.value++
        }
    }

    /**
     * **0.6.0 to 0.6.2 on the owner's Wi-Fi**: Lidarr's name led to the server's address on the
     * home network, the app had never been allowed to reach it (Android 17), and every check
     * timed out. On the home network without the permission nothing is sent: the screen says
     * why and asks, and once it is given the check is made again by itself.
     */
    @Test
    fun `lidarr on the home network without the permission is asked about first, and checked again once it is given`() = runBlocking {
        val behind = Behind()
        val gate = Gate({ Nearness.LOCAL })
        // (Switched on while the app runs: no start.)
        val requests = requests(local = gate)
        requests.switch(true)
        val asked = behind.lidarr.asked.size
        assertEquals(RequestsState.LocalNetwork(certain = true), requests.connect(behind.lidarr.address, KEY))
        assertEquals("nothing is sent", asked, behind.lidarr.asked.size)
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} checking Lidarr, not allowed to reach the home network, 0 ms"))
        assertTrue(traces.last(), traces.last().endsWith("; on the home network: yes; allowed to reach it: no"))
        // "Try again" (and the app starting) say the same, and send nothing either.
        assertEquals(RequestsState.LocalNetwork(certain = true), requests.check())
        assertEquals(asked, behind.lidarr.asked.size)

        gate.give()
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.until { it is RequestsState.NeedsSignIn })
        assertTrue(behind.lidarr.asked.size > asked)
    }

    /** Where it only may be on the home network (a VPN's range, say), it is tried; unanswered
     *  without the permission, the screen says the permission may be why. Answered with an
     *  error, or with the permission held, it is what it always was. */
    @Test
    fun `lidarr that may be on the home network is tried, and unanswered without the permission says why`() = runBlocking {
        val dead = java.net.ServerSocket(0).use { it.localPort }
        val gate = Gate({ Nearness.MAYBE })
        val requests = requests(local = gate)
        requests.switch(true)
        assertEquals(RequestsState.LocalNetwork(certain = false), requests.connect("http://127.0.0.1:$dead", KEY))
        assertTrue(traces.last(), traces.last().contains(", connection refused, ") && traces.last().contains("; on the home network: maybe; allowed to reach it: no; "))
        // A Lidarr that answers, wrongly keyed: said as ever.
        val lidarr = lidarr()
        assertTrue(requests.connect(lidarr.address, "wrongkeywrongkeywrongkeywrongkey") is RequestsState.Unreachable)
        // Held: as ever too.
        gate.holds = true
        assertTrue(requests.connect("http://127.0.0.1:$dead", KEY) is RequestsState.Unreachable)
    }

    /** The sign-in on the home network without the permission: asked about before the
     *  browser is opened, so the trade on the way back is not the first to find out; and a
     *  trade that goes unanswered there says the permission may be why. */
    @Test
    fun `the sign-in on the home network is asked about before the browser opens, and an unanswered trade says why`() = runBlocking {
        val behind = Behind()
        val gate = Gate({ address -> if (address.trimEnd('/') == behind.service.address) Nearness.LOCAL else Nearness.NOT })
        val requests = requests(local = gate)
        requests.switch(true)
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.connect(behind.lidarr.address, KEY))
        assertNull(requests.signIn())
        assertEquals(RequestsState.LocalNetwork(certain = true), requests.state.value)
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} waiting for the browser, not allowed to reach the home network"))

        // Not on the home network as the browser opens (mobile data, say); by the trade it may
        // be (Wi-Fi), and the service goes quiet: judged afresh then, and said so.
        gate.near = { Nearness.NOT }
        requests.check()
        val link = requests.signIn()!!
        val back = signedInAt(link)
        gate.near = { address -> if (address.trimEnd('/') == behind.service.address) Nearness.MAYBE else Nearness.NOT }
        behind.service.close()
        browse(back)
        assertEquals(RequestsState.LocalNetwork(certain = false), requests.until { it is RequestsState.LocalNetwork || it is RequestsState.NeedsSignIn && it.why != null })
        // (The browser was answered before the line was written: waited for.)
        withTimeout(5_000) { while (!traces.last().contains("finishing the sign-in")) delay(20) }
        assertTrue(traces.last(), traces.last().startsWith("${Trace.PREFIX} finishing the sign-in, connection refused, "))
        assertTrue(failures.last())
    }

    /** With the permission held (and always before Android 17), and in offline mode, nothing
     *  is looked up: 0.6.2's way, and offline mode's own sentence. */
    @Test
    fun `nothing is looked up with the permission held, or offline`() = runBlocking {
        val behind = Behind()
        val gate = Gate({ Nearness.LOCAL }, holds = true)
        val requests = requests(local = gate)
        requests.switch(true)
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.connect(behind.lidarr.address, KEY))
        assertEquals(0, gate.looked.get())
        gate.holds = false
        try {
            org.medianexus.music.core.CoreHttp.unsent = { "Offline mode is on." }
            // (A sign-in is remembered here, so it is said beside that; the sentence is offline mode's.)
            assertEquals(RequestsState.NeedsSignIn(behind.host, why = "Couldn't reach Lidarr. Offline mode is on."), requests.check())
        } finally {
            org.medianexus.music.core.CoreHttp.unsent = { null }
        }
        assertEquals(0, gate.looked.get())
    }

    /** Given while the check was looking: checked again at once, not left asking. */
    @Test
    fun `allowed while the address was being judged, it is checked again at once`() = runBlocking {
        val behind = Behind()
        lateinit var gate: Gate
        gate = Gate({
            gate.holds = true
            Nearness.LOCAL
        })
        val requests = requests(local = gate)
        requests.switch(true)
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.connect(behind.lidarr.address, KEY))
    }

    /** A sign-in under way is retired by a Sign in that ends in asking first, as by any new one. */
    @Test
    fun `a sign-in that ends in asking first retires the one under way`() = runBlocking {
        val behind = Behind()
        val gate = Gate({ Nearness.NOT })
        val requests = requests(local = gate)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val first = requests.signIn()!!
        assertEquals(RequestsState.SigningIn(behind.host), requests.state.value)
        gate.near = { Nearness.LOCAL }
        assertNull(requests.signIn())
        assertEquals(RequestsState.LocalNetwork(certain = true), requests.state.value)
        assertFalse(requests.settings.value.signingIn)
        // (Its door is shut: the browser coming back finds nobody.)
        assertTrue(runCatching { browse(signedInAt(first)) }.isFailure)
    }

    /** A connection that opened and then failed (here, a certificate this phone doesn't trust)
     *  keeps its own sentence: it is not put down to the permission. */
    @Test
    fun `a failure after the connection opened is not put down to the permission`() = runBlocking {
        val password = "testing".toCharArray()
        val store = java.security.KeyStore.getInstance("PKCS12").apply { RequestsTest::class.java.getResourceAsStream("/localhost.p12")!!.use { load(it, password) } }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = mockwebserver3.MockWebServer().apply { useHttps(context.socketFactory) }
        server.start()
        try {
            val requests = requests(local = Gate({ Nearness.MAYBE }))
            requests.switch(true)
            val state = requests.connect("https://localhost:${server.port}", KEY)
            assertTrue("$state", state is RequestsState.Unreachable)
            assertTrue(traces.last(), traces.last().contains(", certificate not trusted, "))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a name is judged by where it leads from here`() = runBlocking {
        fun at(vararg addresses: String): (String) -> List<java.net.InetAddress> = { addresses.map { java.net.InetAddress.getByName(it) } }
        assertEquals(Nearness.LOCAL, LocalGate.nearnessOf("lidarr.example.org", lookup = at("192.168.1.20")))
        assertEquals(Nearness.NOT, LocalGate.nearnessOf("lidarr.example.org", lookup = at("104.21.3.4", "2606:4700::1")))
        assertEquals(Nearness.MAYBE, LocalGate.nearnessOf("lidarr.example.org", lookup = at("100.101.102.103")))
        assertEquals(Nearness.LOCAL, LocalGate.nearnessOf("192.168.1.20"))
        assertEquals(Nearness.NOT, LocalGate.nearnessOf("localhost"))
        // Not judged: a look-up that fails.
        assertNull(LocalGate.nearnessOf("nowhere.example", lookup = { throw java.net.UnknownHostException() }))
        // Mixed: one address at home and one not is only maybe; and so is all at home through a VPN.
        assertEquals(Nearness.MAYBE, LocalGate.nearnessOf("lidarr.example.org", lookup = at("192.168.1.20", "2606:4700::1")))
        assertEquals(Nearness.MAYBE, LocalGate.nearnessOf("lidarr.example.org", vpn = true, lookup = at("192.168.1.20")))
        assertEquals(Nearness.MAYBE, LocalGate.nearnessOf("192.168.1.20", vpn = true))
    }

    @Test
    fun `the tab closed without signing in gives the sign-in up, and a browser on its way back still gets through`() = runBlocking {
        val behind = Behind()
        val requests = requests()
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)

        // The app's screen is in front again and nobody came: given up, and the door shut.
        var link = requests.signIn()!!
        var back = param(link, "redirect_uri")
        requests.cameBack()
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.until { it is RequestsState.NeedsSignIn })
        assertTrue(runCatching { browse("$back?code=late&state=${param(link, "state")}") }.isFailure)
        assertTrue(behind.service.asked.none { "authorization_code" in it.body })

        // The screen comes to the front just as the browser comes back: the sign-in stands.
        link = requests.signIn()!!
        back = param(link, "redirect_uri")
        requests.cameBack()
        signInWith(link)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
        Thread.sleep(2_000)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.state.value)

        // Given up by hand.
        requests.signOut()
        link = requests.signIn()!!
        requests.cancelSignIn()
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.state.value)
    }

    @Test
    fun `the app stopped behind the browser finds the sign-in unfinished, and it is simply started again`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        assertTrue(requests.settings.value.signingIn)
        // (Killed: nothing of it runs again. Its door goes with it.)
        scopes.last().cancel()
        requests.cancelSignIn()
        File(account.dirs.data, "requests.json").let { it.writeText(it.readText().replace("\"signingIn\":false", "\"signingIn\":true")) }

        val again = requests(account)
        again.start()
        assertEquals(RequestsState.NeedsSignIn(behind.host, interrupted = true), again.until { it is RequestsState.NeedsSignIn })
        assertFalse(again.settings.value.signingIn)
        assertTrue(runCatching { browse("${param(link, "redirect_uri")}?code=c&state=${param(link, "state")}") }.isFailure)
        // Started again, it works as any other.
        val fresh = again.signIn()!!
        signInWith(fresh)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), again.until { it is RequestsState.Connected })
    }

    @Test
    fun `signing out hands the newest token back to the service and keeps nothing of it`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        signInWith(link)
        requests.until { it is RequestsState.Connected }
        val token = File(secretsDir(account), "lidarr-token.sealed")
        val held = token.readBytes().reversedArray().decodeToString().split('\n', limit = 3)[2]

        assertEquals(RequestsState.NeedsSignIn(behind.host, signedOut = true), requests.signOut())
        withTimeout(10_000) { while (behind.revoked.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(listOf(held), behind.revoked.toList())
        assertFalse(token.exists())
        assertEquals("", requests.settings.value.signedIn)
        // And Lidarr is behind its sign-in again.
        assertEquals(RequestsState.NeedsSignIn(behind.host), requests.check())
    }

    // ---- one sign-in owns the token that is kept ------------------------------------------------

    private fun tokenKept(account: Account): String? =
        File(secretsDir(account), "lidarr-token.sealed").takeIf { it.exists() }?.readBytes()?.reversedArray()?.decodeToString()?.split('\n', limit = 3)?.get(2)

    @Test
    fun `signed out and in again at once, the new sign-in stands however late the old one's goodbye is`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        signInWith(requests.signIn()!!)
        requests.until { it is RequestsState.Connected }
        val first = tokenKept(account)!!

        // Signed out; and the service is slow to take the old token back.
        val goodbye = java.util.concurrent.CountDownLatch(1)
        behind.held["/application/o/revoke/"] = goodbye
        requests.signOut()
        assertTrue(behind.service.awaitAsked(10_000) { it.target == "/application/o/revoke/" })
        // In again at once.
        signInWith(requests.signIn()!!)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected })
        val second = tokenKept(account)!!
        assertTrue(second != first)

        // Only now does the old sign-in's goodbye finish. It was for the old token: the new
        // one is not its to take away.
        goodbye.countDown()
        withTimeout(10_000) { while (behind.revoked.isEmpty()) kotlinx.coroutines.delay(20) }
        kotlinx.coroutines.delay(600)
        assertEquals(listOf(first), behind.revoked.toList())
        assertEquals(second, tokenKept(account))
        // And the app started again is still signed in, with no browser.
        scopes.last().cancel()
        val again = requests(account)
        again.start()
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), again.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
    }

    @Test
    fun `a renewal by a sign-in that has since been signed out of writes nothing over the one that replaced it`() = runBlocking {
        val behind = Behind()
        val account = account()
        val before = requests(account)
        before.switch(true)
        before.connect(behind.lidarr.address, KEY)
        signInWith(before.signIn()!!)
        before.until { it is RequestsState.Connected }
        scopes.last().cancel()
        val kept = tokenKept(account)!!

        // The app starts again. Its first request renews the token, and the service is slow
        // about it: the renewal is on its way when...
        val slow = java.util.concurrent.CountDownLatch(1)
        behind.held["refresh_token=$kept"] = slow
        val requests = requests(account)
        requests.start()
        assertTrue(behind.service.awaitAsked(10_000) { "refresh_token=$kept" in it.body })
        // ...the person signs out, and in again.
        requests.signOut()
        signInWith(requests.signIn()!!)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected })
        val mine = tokenKept(account)!!

        // The old renewal comes back now, with a token for a sign-in nobody is in any more.
        // It is handed back to the service; it is written nowhere, and the token that is
        // kept is not taken away with it.
        slow.countDown()
        withTimeout(10_000) { while (behind.revoked.isEmpty()) kotlinx.coroutines.delay(20) }
        kotlinx.coroutines.delay(600)
        assertEquals(1, behind.revoked.size)
        assertTrue(behind.revoked.single() != mine)
        assertEquals(mine, tokenKept(account))
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.check())
        scopes.last().cancel()
        val again = requests(account)
        again.start()
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), again.until { it is RequestsState.Connected || it is RequestsState.NeedsSignIn })
    }

    @Test
    fun `an account that signs out of the music server leaves no secret of its requests behind`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        signInWith(link)
        requests.until { it is RequestsState.Connected }

        scopes.last().cancel()
        requests.leave()
        withTimeout(10_000) { while (behind.revoked.isEmpty()) kotlinx.coroutines.delay(20) }
        assertTrue(secretsDir(account).listFiles().orEmpty().none { it.name.endsWith(".sealed") })
        // Where Lidarr is stays with the account's other things, for signing in again.
        assertTrue(behind.lidarr.address in File(account.dirs.data, "requests.json").readText())
        val again = requests(account)
        again.start()
        assertEquals(RequestsState.NotConnected, again.until { it != RequestsState.Off })
    }

    @Test
    fun `a sign-in that has ended at the service says so, and asks to sign in again`() = runBlocking {
        val behind = Behind()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(behind.lidarr.address, KEY)
        val link = requests.signIn()!!
        signInWith(link)
        requests.until { it is RequestsState.Connected }

        // The app started again, and the service no longer honours the token.
        behind.refusing.set(true)
        val again = requests(account)
        again.start()
        val state = again.until { it is RequestsState.NeedsSignIn }
        assertEquals(RequestsState.NeedsSignIn(behind.host, why = "Your sign-in to Lidarr has ended. In Settings, under Requests, sign in with your browser again."), state)
        assertFalse(File(secretsDir(account), "lidarr-token.sealed").exists())
    }

    // ---- what is kept --------------------------------------------------------------------------------

    @Test
    fun `what is kept that can't be read is left as it is, and nothing is changed until it can be`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        // (A folder where the file should be: there, and not to be read.)
        File(account.dirs.data, "requests.json").mkdirs()
        val requests = requests(account)
        requests.start()
        assertEquals(RequestsState.CantRead, requests.until { it != RequestsState.Off })
        assertEquals(RequestsState.CantRead, requests.switch(true))
        assertEquals(RequestsState.CantRead, requests.connect(lidarr.address, KEY))
        assertNull(requests.lidarr())
        assertNull(requests.signIn())
        assertTrue(lidarr.asked.isEmpty())
        assertTrue(File(account.dirs.data, "requests.json").isDirectory)
        assertFalse(File(secretsDir(account), "lidarr-key.sealed").exists())
        assertTrue(notes.toString(), notes.any { "couldn't be read" in it })
    }

    @Test
    fun `what is kept that this app can make nothing of is moved aside under a name of its own, never over an earlier one, and the person is told`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        account.dirs.data.mkdirs()
        val file = File(account.dirs.data, "requests.json")
        fun aside() = account.dirs.data.listFiles().orEmpty().filter { it.name.startsWith("requests.damaged-") && it.name.endsWith(".json") }.sortedBy { it.name }

        // Nothing in it yet: that is nothing kept, and nothing to tell.
        file.writeText(" \n")
        val empty = requests(account)
        assertEquals(RequestsState.Off, empty.switch(false))
        assertNull(empty.keptAside.value)
        assertTrue(aside().isEmpty())

        // Cut short: moved aside as it was, before anything is asked or written, and said.
        file.writeText("""{"on":true,"address":"http://cut-sho""")
        val requests = requests(account)
        requests.start()
        withTimeout(10_000) { requests.keptAside.first { it != null } }
        assertEquals(RequestsState.Off, requests.state.value)
        val first = aside().single()
        assertEquals("""{"on":true,"address":"http://cut-sho""", first.readText())
        assertFalse(file.exists())
        assertEquals(first.name, requests.keptAside.value)
        assertTrue("$notes", notes.any { first.name in it })
        // Requests start afresh from there, and are written as ever.
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        assertTrue(lidarr.address in file.readText())

        // Damaged again, in the same second or a later one: the first one kept is not
        // written over.
        file.writeText("not what this app writes")
        val again = requests(account)
        again.start()
        withTimeout(10_000) { again.keptAside.first { it != null } }
        val both = aside()
        assertEquals(2, both.size)
        assertEquals(setOf("""{"on":true,"address":"http://cut-sho""", "not what this app writes"), both.map { it.readText() }.toSet())
        assertTrue(again.keptAside.value in both.map { it.name } && again.keptAside.value != first.name)
    }

    @Test
    fun `the address is kept without what follows a question mark or a hash in it`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        // (Pasted from a browser's address bar, say; or with a key written into it.)
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = null), requests.connect("${lidarr.address}/?apikey=typed-into-the-address#/settings/general", KEY))
        assertEquals("${lidarr.address}/", requests.settings.value.address)
        assertEquals(listOf("GET /api/v1/system/status"), lidarr.asked.map { it.line })
        val kept = everythingKept(account)
        assertFalse(kept, "typed-into-the-address" in kept || "settings/general" in kept)
        requests.connect("${lidarr.address}/lidarr#top", KEY)
        assertEquals("${lidarr.address}/lidarr", requests.settings.value.address)
    }
}
