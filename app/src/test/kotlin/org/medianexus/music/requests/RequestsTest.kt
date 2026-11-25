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
    private val KEY = "standinkeystandinkeystandinkey12"

    private fun account(name: String = "tester") =
        Account("http://music.example", name, accountKey("http://music.example", name), AccountDirs(folder.root.resolve("data-$name"), folder.root.resolve("cache-$name")))

    private fun secretsDir(account: Account) = folder.root.resolve("no-backup/requests/${account.key}")

    private fun requests(account: Account = account(), waitMillis: Long = 60_000): Requests {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return Requests(
            account, scope, RequestsSecrets(secretsDir(account), Turned, account.server, account.username),
            afterwards = { work -> behind.launch { work() } }, comeBack = { cameBack.incrementAndGet() }, note = { notes += it }, waitMillis = waitMillis,
        )
    }

    /** A stand-in Lidarr that answers to [KEY], behind nothing. */
    private fun lidarr() = StandIn { asked, reply ->
        when {
            asked.header("X-Api-Key") != KEY -> reply.send(401)
            asked.target == "/api/v1/system/status" -> reply.json("""{"version":"2.9.6.4552"}""")
            else -> reply.send(404)
        }
    }.also { closing += it }

    /**
     * A stand-in sign-in service, and a stand-in Lidarr behind it that lets through only a
     * pass the service handed out.
     *
     * The service **checks the secret half of a sign-in as a real one does** (PKCE, S256):
     * a code is handed out for the check that came with the request for it, and is traded
     * for a token only by whoever then shows the secret that check was made from. An app
     * that sent the wrong secret, or a code brought to another sign-in, gets nothing.
     */
    private inner class Behind {
        val passes = CopyOnWriteArrayList<String>()
        val refusing = AtomicBoolean(false)
        val revoked = CopyOnWriteArrayList<String>()

        /** Who the service says signed in, as it is written inside its JSON answer. */
        @Volatile var username = "walker"
        private val made = AtomicInteger(0)

        /** Requests the service keeps waiting until the test lets them go, by something in
         *  their address or their body. */
        val held = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

        /** The check each code was asked for with. */
        private val checks = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun s256(secret: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
        val service = StandIn { asked, reply ->
            val form = asked.body.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            held.entries.firstOrNull { it.key in asked.target || it.key in asked.body }?.value?.await(30, java.util.concurrent.TimeUnit.SECONDS)
            when {
                asked.target.startsWith("/application/o/authorize/?response_type=code&") -> {
                    val query = asked.target.substringAfter('?')
                    val check = Core.gateParam(query, "code_challenge").orEmpty()
                    val back = Core.gateParam(query, "redirect_uri").orEmpty()
                    if (Core.gateParam(query, "code_challenge_method") != "S256" || check.isEmpty() || back.isEmpty()) {
                        reply.send(400)
                    } else {
                        val code = "code-${made.incrementAndGet()}"
                        checks[code] = check
                        reply.send(302, "", "Location" to "$back?code=$code&state=${Core.gateParam(query, "state").orEmpty()}")
                    }
                }
                asked.target == "/application/o/token/" && refusing.get() -> reply.json("""{"error":"invalid_grant"}""", status = 400)
                asked.target == "/application/o/token/" && form["grant_type"] == "authorization_code" && checks.remove(form["code"].orEmpty()) != s256(form["code_verifier"].orEmpty()) ->
                    reply.json("""{"error":"invalid_grant","error_description":"the code and the secret do not belong together"}""", status = 400)
                asked.target == "/application/o/token/" && form["grant_type"] == "client_credentials" -> {
                    val pass = "pass-${made.incrementAndGet()}"
                    passes += pass
                    reply.json("""{"access_token":"$pass","expires_in":600}""")
                }
                asked.target == "/application/o/token/" -> reply.json("""{"access_token":"access-${made.incrementAndGet()}","refresh_token":"refresh-${made.incrementAndGet()}","expires_in":300}""")
                asked.target == "/application/o/userinfo/" -> reply.json("""{"sub":"x","preferred_username":"$username","name":"The Walker"}""")
                asked.target == "/application/o/revoke/" -> {
                    revoked += form["token"].orEmpty()
                    reply.send(200)
                }
                else -> reply.send(404)
            }
        }.also { closing += it }
        val lidarr: StandIn = StandIn { asked, reply ->
            val pass = asked.header("Authorization").orEmpty().removePrefix("Bearer ")
            when {
                asked.target.startsWith("/outpost.goauthentik.io/start") ->
                    reply.send(302, "", "Location" to "${service.address}/application/o/authorize/?client_id=lidarr-proxy&redirect_uri=x&response_type=code")
                pass !in passes -> reply.send(302, "", "Location" to "/outpost.goauthentik.io/start?rd=x")
                asked.header("X-Api-Key") != KEY -> reply.send(401)
                asked.target == "/api/v1/system/status" -> reply.json("""{"version":"2.9.6.4552"}""")
                else -> reply.send(404)
            }
        }.also { closing += it }
        val host: String get() = service.address.removePrefix("http://")
    }

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
    fun `an address that reads as one place and is another to the client is taken as the client takes it`() = runBlocking {
        val lidarr = lidarr()
        val other = elsewhere()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        // To the eye, and to a reader that splits at the "@", this is still the first address.
        // The client connects to what is before the backslash.
        val port = lidarr.address.substringAfterLast(':')
        val looks = "${other.address}\\@127.0.0.1:$port"
        assertEquals(RequestsState.KeyForgotten, requests.connect(looks, ""))
        assertTrue(other.asked.isEmpty())
    }

    @Test
    fun `an address with a name and password in it is refused, and nothing of it is kept`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        val requests = requests(account)
        requests.switch(true)
        val named = lidarr.address.replace("http://", "http://admin:hunter2@")
        assertEquals(RequestsState.Unreachable("Leave the name and password out of the address. Lidarr is given its key instead."), requests.connect(named, KEY))
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
        assertEquals(0, cameBack.get())
        assertNull(tokenKept(account))
        slow.countDown()
        val (status, shown) = page.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(200, status)
        assertTrue(shown, "You're signed in" in shown)
        // By the time the browser has its page the token is kept; then the app comes forward.
        assertNotNull(tokenKept(account))
        assertEquals(RequestsState.Connected("2.9.6.4552", signedInAs = "walker"), requests.until { it is RequestsState.Connected })
        assertEquals(1, cameBack.get())
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
    fun `what is kept that is not what this app writes is kept aside before anything is written over it`() = runBlocking {
        val lidarr = lidarr()
        val account = account()
        account.dirs.data.mkdirs()
        File(account.dirs.data, "requests.json").writeText("""{"on":true,"address":"http://cut-sho""")
        val requests = requests(account)
        requests.start()
        assertEquals(RequestsState.Off, requests.until { true })
        requests.switch(true)
        requests.connect(lidarr.address, KEY)
        assertEquals("""{"on":true,"address":"http://cut-sho""", File(account.dirs.data, "requests.json.damaged").readText())
        assertTrue(lidarr.address in File(account.dirs.data, "requests.json").readText())
    }
}
