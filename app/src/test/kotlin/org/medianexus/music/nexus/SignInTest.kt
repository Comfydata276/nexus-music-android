package org.medianexus.music.nexus

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.Graph
import org.medianexus.music.SessionState
import org.medianexus.music.graph
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Signing in, through the Nexus server's machine, end to end** (the backend design's 8.5, 6.4,
 * section 11): the app's own driver of the shared core's `nexus::client`, over HTTPS to a
 * stand-in backend whose every answer the core's own encoder wrote, the music server at the
 * address it hands back. What is kept afterwards; what is never kept; a refusal; and **the
 * local-network permission judged after a failure in which no connection was opened, both ways
 * round**: the address leads inside and the permission is not held (the machine says to ask), and
 * it does not (it says the server can't be reached, and nothing of the permission).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class SignInTest {
    companion object {
        @JvmStatic @BeforeClass fun trust() = BackendStandIn.trust()
        @JvmStatic @AfterClass fun untrust() = BackendStandIn.untrust()

        const val PASSWORD = "a password long enough"
    }

    private val app = RuntimeEnvironment.getApplication()
    private val standIn = BackendStandIn(BackendStandIn.Broken.ABSENT)

    @Before
    fun start() {
        standIn.start()
        runBlocking { app.graph.session.restored() }
    }

    @After
    fun stop() {
        Nexus.socketsForTests = null
        runCatching { app.graph.nexus?.stop() }
        standIn.stop()
    }

    private fun nexus(graph: Graph = app.graph): Nexus = runBlocking {
        graph.started()
        withTimeout(30_000) {
            while (graph.nexus?.started != true) kotlinx.coroutines.delay(10)
            graph.nexus!!
        }
    }

    private fun press(nexus: Nexus, address: String = standIn.address) = nexus.press(buildJsonObject {
        put("sign_in", buildJsonObject { put("address", address); put("email", "tester@example.org"); put("password", PASSWORD) })
    })

    private fun shown(nexus: Nexus, what: (NexusShown) -> Boolean): NexusShown = runBlocking { withTimeout(30_000) { nexus.shown.first { it != null && what(it) }!! } }

    @Test
    fun signingInKeepsTheSessionAndTheMusicLoginAndNeverThePassword() {
        plainGraph()
        standIn.signIn(app)
        val now = app.graph.session.state.value as SessionState.SignedIn
        // The client for the music server is made from the salt and token handed over.
        assertEquals(64, now.api.login.now.salt.length)
        assertEquals("1", now.api.login.now.count)
        // Kept, as one thing: the session, the music login, the account; never the password.
        val parts = app.graph.nexusKept.load()!!
        assertTrue(parts.session)
        assertEquals("tester", parts.music?.name)
        assertEquals("7", parts.account?.account)
        val onDisk = Graph.signInFile(app).readBytes().toString(Charsets.UTF_8)
        assertFalse("the password typed was kept", PASSWORD in onDisk)
        for (file in app.filesDir.walkTopDown().filter { it.isFile } + app.noBackupFilesDir.walkTopDown().filter { it.isFile }) {
            assertFalse("the password typed is in ${file.name}", PASSWORD in file.readBytes().toString(Charsets.ISO_8859_1))
        }
        // Asked: hello, then the sign-in, to the address typed, and nothing else of it.
        assertEquals(listOf("/nexus/api/hello", "/nexus/api/signin"), standIn.backendAnswered.toList().take(2))
        // And the folder is who the person is to the backend.
        assertEquals(Folders.personOf(KeptAccount(standIn.address, BackendStandIn.BACKEND_ID, "7")), now.account.key)
    }

    @Test
    fun aDefiniteOpenFailureRemovesTheOldFileAndAllowsSignIn() {
        val sealed = byteArrayOf(1, 2, 3)
        val graph = plainGraph { if (it.contentEquals(sealed)) null else it }
        val file = Graph.signInFile(app).apply { writeBytes(sealed) }
        val driver = nexus(graph)
        shown(driver) { it.screen == "sign_in" && it.signIn.state == "idle" }
        assertFalse(file.exists())
        standIn.signIn(app)
        assertTrue(graph.nexusKept.load()!!.session)
    }

    @Test
    fun aPassingOpenFailureKeepsTheFileUntilAFreshSignInSucceeds() {
        val sealed = byteArrayOf(1, 2, 3)
        val graph = plainGraph {
            if (it.contentEquals(sealed)) throw java.security.ProviderException("test keystore failure")
            it
        }
        val file = Graph.signInFile(app).apply { writeBytes(sealed) }
        val driver = nexus(graph)
        shown(driver) { it.screen == "sign_in" && it.signIn.state == "not_opened" }
        assertTrue(file.readBytes().contentEquals(sealed))
        standIn.signIn(app)
        assertFalse(file.readBytes().contentEquals(sealed))
        assertTrue(graph.nexusKept.load()!!.session)
    }

    @Test
    fun aWrongPasswordIsSaidAndNobodyIsSignedIn() {
        standIn.backendThere = true
        standIn.refuseSignIn = true
        val nexus = nexus(plainGraph())
        press(nexus)
        val said = shown(nexus) { it.signIn.state == "wrong_login" }
        assertTrue("the machine's words for it", said.signIn.words.isNotEmpty())
        assertEquals(SessionState.SignedOut, app.graph.session.state.value)
        assertFalse(app.graph.nexusKept.load()!!.session)
    }

    /** **The journal and Diagnostics hear who is signed in from the driver** (the ten cautions,
     *  3, 7): nobody while the notice of what the server records waits to be seen, the account
     *  once it is, Diagnostics signed in and knowing the default `hello` said; nobody after
     *  signing out. */
    @Test
    fun theJournalAndDiagnosticsHearWhoIsSignedInFromTheDriver() {
        val graph = plainGraph()
        standIn.signIn(app)
        val nexus = graph.nexus!!
        val now = shown(nexus) { it.screen == "signed_in" }
        if (now.notice != null) {
            assertNull("recorded before the notice was seen", graph.recording.scope())
            nexus.press(buildJsonObject { put("notice_seen", buildJsonObject {}) })
        }
        val scope = until { graph.recording.scope() }
        assertEquals(BackendStandIn.BACKEND_ID, scope.backend.content)
        assertEquals("7", scope.number.content)
        val diagnostics = runBlocking { withTimeout(30_000) { graph.recording.diagnostics.settings.first { it.signedIn && it.knowsDefault } } }
        assertTrue("on by default", diagnostics.switchedOn)
        standIn.backendThere = true
        nexus.press(buildJsonObject { put("sign_out", buildJsonObject {}) })
        until { if (graph.recording.scope() == null) Unit else null }
        runBlocking { withTimeout(30_000) { graph.recording.diagnostics.settings.first { !it.signedIn } } }
    }

    @Test
    fun statsAreCachedOnlyForTheirLifeAndDeletionWaitsForTheJournal() {
        val graph = plainGraph()
        standIn.signIn(app)
        val nexus = graph.nexus!!
        val scope = until { nexus.appSession.value?.takeIf { it.verified }?.scope }
        nexus.press(buildJsonObject { put("notice_seen", buildJsonObject {}) })
        until { graph.recording.scope() }
        var allowDeletion = false
        standIn.answer = { request ->
            when (request.url.encodedPath) {
                "/nexus/api/events/stats" -> standIn.feature("stats")
                "/nexus/api/grant" -> standIn.feature(if (allowDeletion) "granted" else "wrong_login")
                "/nexus/api/delete-listening-data" -> standIn.feature("signed_out", life = 2)
                else -> standIn.there(request)
            }
        }
        runBlocking {
            val read = nexus.listening.read(scope) as ListeningResult.Done
            assertEquals(0uL, read.value.starts)
            assertEquals(read.value, nexus.listening.cached(scope))
            graph.settings.update { it.copy(offline = true) }
            withTimeout(30_000) { nexus.appSession.first { it?.offline == true } }
            val before = standIn.asked.count { it == "POST /nexus/api/events/stats" }
            assertTrue(nexus.listening.read(scope) is ListeningResult.Error)
            assertEquals(before, standIn.asked.count { it == "POST /nexus/api/events/stats" })
            assertEquals(read.value, nexus.listening.cached(scope))
            graph.settings.update { it.copy(offline = false) }
            withTimeout(30_000) { nexus.appSession.first { it?.offline == false } }
            assertEquals(ListeningResult.Error("That password didn't match. Try again."), nexus.listening.delete(scope, "wrong password"))
            assertFalse(standIn.asked.contains("POST /nexus/api/delete-listening-data"))
            allowDeletion = true
            val deleted = nexus.listening.delete(scope, PASSWORD) as ListeningResult.Done
            assertEquals("2", deleted.value.life.toString())
            assertEquals("2", graph.recording.scope()?.life?.content)
            assertNull(nexus.listening.cached(scope))
            assertEquals(0uL, nexus.listening.cached(deleted.value)?.starts)
        }
    }

    @Test
    fun requestsOnlyUseDirectAfterTheCheckedUnconfiguredStatus() {
        val graph = plainGraph()
        standIn.signIn(app)
        val nexus = graph.nexus!!
        until { nexus.appSession.value?.takeIf { it.verified } }
        val results = java.util.concurrent.atomic.AtomicReference("requests_ready")
        standIn.answer = { request ->
            if (request.url.encodedPath == "/nexus/api/requests") {
                if (results.get() == "failure") mockwebserver3.MockResponse.Builder().code(503).build()
                else standIn.feature(results.get())
            } else standIn.there(request)
        }
        val router = graph.requestsRouter
        fun select(kind: String, next: (org.medianexus.music.requests.RequestsRoute) -> Boolean) = runBlocking {
            val previous = router.route.value
            results.set(kind)
            router.refresh()
            withTimeout(30_000) { router.route.first { it !== previous && next(it) } }
        }
        val usable: (org.medianexus.music.requests.RequestsRoute) -> Boolean = { it is org.medianexus.music.requests.RequestsRoute.Usable }
        val refused: (org.medianexus.music.requests.RequestsRoute) -> Boolean = { it is org.medianexus.music.requests.RequestsRoute.Unavailable }
        select("requests_ready", usable)
        assertNull(router.directEndpoint())
        select("requests_unconfigured", usable)
        assertTrue(router.directEndpoint() != null)
        select("requests_off", refused)
        assertNull(router.directEndpoint())
        select("requests_unconfigured", usable)
        select("requests_denied", refused)
        assertNull(router.directEndpoint())
        select("requests_unconfigured", usable)
        select("failure", refused)
        assertNull(router.directEndpoint())
        select("requests_unconfigured", usable)
        graph.settings.update { it.copy(offline = true) }
        runBlocking { withTimeout(30_000) { nexus.appSession.first { it?.offline == true } } }
        assertNull(router.directEndpoint())
    }

    @Test
    fun failedDiagnosticsScheduleDoesNotBlockSignInOrSignOut() {
        // An occupied destination refuses the atomic rename, as a full phone would.
        val schedule = File(app.noBackupFilesDir, "nexus-diag-schedule.json")
        schedule.delete()
        assertTrue(schedule.mkdirs())
        File(schedule, "occupied").writeText("test")
        val graph = plainGraph()
        standIn.signIn(app)
        assertTrue(graph.nexusKept.load()!!.session)
        val nexus = graph.nexus!!
        nexus.press(buildJsonObject { put("sign_out", buildJsonObject {}) })
        shown(nexus) { it.screen == "sign_in" }
        assertFalse(graph.nexusKept.load()!!.session)
    }

    @Test
    fun signOutRemovesTheSavedLoginEvenWhenTheNoteCannotBeWritten() {
        val graph = plainGraph()
        standIn.signIn(app)
        // Force the sign-in file's replacement to fail while the existing file can be removed.
        val signIn = Graph.signInFile(app)
        assertTrue(signIn.isFile)
        rejectSeals = true
        val nexus = graph.nexus!!
        nexus.press(buildJsonObject { put("sign_out", buildJsonObject {}) })
        shown(nexus) { it.screen == "sign_in" }
        until { Unit.takeIf { graph.session.state.value == SessionState.SignedOut } }
        assertFalse(signIn.exists())
    }

    @Test
    fun aFailedSavedKeepEndsWorkingAndDoesNotHandOffTheLogin() {
        standIn.backendThere = true
        val graph = plainGraph()
        refuseSaved = true
        val nexus = nexus(graph)
        press(nexus)
        shown(nexus) { it.signIn.state == "not_kept" }
        assertEquals(SessionState.SignedOut, graph.session.state.value)
        assertFalse(graph.nexusKept.load()!!.session)
        nexus.network(true)
        nexus.workers.first().submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("not_kept", nexus.shown.value?.signIn?.state)
        assertEquals(SessionState.SignedOut, graph.session.state.value)
    }

    @Test
    fun aKeptSignInThatCannotBeTakenOnOffersSignOut() {
        standIn.backendThere = true
        val graph = plainGraph()
        graph.settings.update { it.copy(sharedFor = "old-account", pinnedPlaylists = listOf("a")) }
        val choices = File(app.filesDir, "accounts/old-account/in-step.json")
        assertTrue(choices.mkdirs())
        File(choices, "occupied").writeText("test")
        val nexus = nexus(graph)
        press(nexus)
        val signedIn = shown(nexus) { it.screen == "signed_in" && it.plays }
        until { graph.session.signIn.value.takeIf { it.choicesNotPutAway } }
        assertEquals(SessionState.SignedOut, graph.session.state.value)
        assertEquals(
            org.medianexus.music.R.string.sign_in_choices_not_put_away,
            org.medianexus.music.ui.signin.signedInWithoutPlaybackMessage(signedIn, graph.session.signIn.value),
        )
        nexus.press(buildJsonObject { put("sign_out", buildJsonObject {}) })
        shown(nexus) { it.screen == "sign_in" }
        assertFalse(graph.nexusKept.load()!!.session)
    }

    private fun <T : Any> until(what: () -> T?): T = runBlocking {
        withTimeout(30_000) {
            var found = what()
            while (found == null) {
                kotlinx.coroutines.delay(10)
                found = what()
            }
            found
        }
    }

    /** No connection is ever opened (Android 17 drops it, without the permission, where the
     *  name leads inside the house): with the address inside and the permission not held, the
     *  machine says to ask for it. */
    @Test
    fun aFailureInsideTheHouseWithoutThePermissionAsksForIt() {
        val graph = graphWith(inside = true, permission = false)
        val nexus = nexus(graph)
        Nexus.socketsForTests = NeverConnects
        press(nexus)
        val said = shown(nexus) { it.signIn.state == "needs_permission" }
        assertTrue(said.signIn.askPermission || said.askPermission)
    }

    /** The other way round: the address is public from here. Nothing about the permission. */
    @Test
    fun aFailureWithAPublicAddressSaysOnlyThatItCantBeReached() {
        val graph = graphWith(inside = false, permission = false)
        val nexus = nexus(graph)
        Nexus.socketsForTests = NeverConnects
        press(nexus)
        val said = shown(nexus) { it.signIn.state == "cant_reach" }
        assertFalse(said.signIn.askPermission || said.askPermission)
    }

    /** The app's objects, with where an address leads and the permission as the test says
     *  (the build machine cannot be the owner's Wi-Fi). */
    private fun graphWith(inside: Boolean, permission: Boolean): Graph {
        Graph.localForTests = Graph.LocalForTests(inside, permission)
        return plainGraph()
    }

    @Volatile private var rejectSeals = false
    @Volatile private var refuseSaved = false

    /** The app's objects made again, sealing with a box the test runner can open (it has no
     *  Keystore), and made the app's own. */
    private fun plainGraph(openSealed: (ByteArray) -> ByteArray? = { it }): Graph {
        runCatching { app.graph.nexus?.stop() }
        return Graph(app, object : org.medianexus.music.store.SecretBox {
            override fun seal(plain: ByteArray): ByteArray {
                if (rejectSeals || (refuseSaved && "\"saved\":" in plain.decodeToString())) {
                    throw java.io.IOException("test write failure")
                }
                return plain
            }
            override fun open(sealed: ByteArray) = openSealed(sealed)
        }).also { graph ->
            App::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        }
    }

    /** Sockets whose connect is refused at once: no connection is ever opened. */
    private object NeverConnects : javax.net.SocketFactory() {
        private fun refuse(): Nothing = throw java.net.ConnectException("refused, as Android 17 drops it")
        override fun createSocket(): java.net.Socket = object : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = refuse()
        }
        override fun createSocket(host: String?, port: Int): java.net.Socket = refuse()
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket = refuse()
        override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = refuse()
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket = refuse()
    }

    @After
    fun forget() {
        Graph.localForTests = null
        File(app.noBackupFilesDir, "nexus-signin.sealed").delete()
    }
}
