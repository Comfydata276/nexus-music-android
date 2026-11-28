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
        assertEquals("1".padStart(64, '0'), now.api.login.now.salt)
        assertEquals("1", now.api.login.now.count)
        // Kept, as one thing: the session, the music login, the account; never the password.
        val parts = app.graph.nexusKept.load()
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
    fun aWrongPasswordIsSaidAndNobodyIsSignedIn() {
        standIn.backendThere = true
        standIn.refuseSignIn = true
        val nexus = nexus(plainGraph())
        press(nexus)
        val said = shown(nexus) { it.signIn.state == "wrong_login" }
        assertTrue("the machine's words for it", said.signIn.words.isNotEmpty())
        assertEquals(SessionState.SignedOut, app.graph.session.state.value)
        assertFalse(app.graph.nexusKept.load().session)
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

    /** The app's objects made again, sealing with a box the test runner can open (it has no
     *  Keystore), and made the app's own. */
    private fun plainGraph(): Graph {
        runCatching { app.graph.nexus?.stop() }
        return Graph(app, object : org.medianexus.music.store.SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
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
