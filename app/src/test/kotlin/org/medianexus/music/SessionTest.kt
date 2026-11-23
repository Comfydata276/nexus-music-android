package org.medianexus.music

import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.PasswordVault
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.Settings
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.accountDirs

class SessionTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Before
    fun start() {
        server = MockWebServer()
        // A server that knows one password ("sesame"), and answers anything else as Navidrome
        // does a wrong one.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val good = url.queryParameter("t") == Subsonic.md5Hex("sesame" + url.queryParameter("s"))
                val body = if (good) {
                    """{"subsonic-response":{"status":"ok","serverVersion":"0.58.0"}}"""
                } else {
                    """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""
                }
                return MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun session(
        dir: File,
        http: OkHttpClient = OkHttpClient(),
        vault: PasswordVault = PasswordVault(File(dir, "password.sealed"), PlainBox),
        offline: OfflineSwitch = OfflineSwitch(),
    ): Triple<Session, SettingsStore, Notices> {
        val settings = SettingsStore.open(File(dir, "settings.json"), scope, Dispatchers.IO)
        val notices = Notices()
        val session = Session(
            settings, vault, http, offline,
            { key -> accountDirs(File(dir, "files"), File(dir, "cache"), key) }, notices, scope, Dispatchers.IO,
        )
        return Triple(session, settings, notices)
    }

    private suspend fun Session.settled() = signIn.first { !it.busy }

    @Test
    fun anAddressWithoutASchemeFallsBackToPlainHttpAndIsRemembered() = runBlocking {
        val dir = folder.newFolder()
        val (session, settings, notices) = session(dir)
        assertEquals(SessionState.SignedOut, withTimeout(5_000) { session.restored() })
        val typed = "127.0.0.1:${server.port}"
        session.signIn(typed, "  ada ", "sesame")
        withTimeout(20_000) { session.settled() }
        val signedIn = session.state.value as SessionState.SignedIn
        // HTTPS was tried first and got nowhere; plain HTTP answered.
        assertEquals("http://$typed", signedIn.account.server)
        assertEquals("ada", signedIn.account.username)
        assertEquals(signedIn.api, session.api.first { it != null })
        assertEquals(File(dir, "files/accounts/${signedIn.account.key}"), signedIn.account.dirs.data)
        settings.flush()
        assertEquals("http://$typed", settings.value.server)
        // A home address: no warning about encryption.
        assertTrue(notices.state.value.isEmpty())

        // Started again: signed in from what was kept, without asking the server.
        val (again, _, _) = session(dir)
        val restored = withTimeout(5_000) { again.restored() } as SessionState.SignedIn
        assertEquals("ada", restored.account.username)

        // Signing out forgets the password but keeps the address for next time. Once it is
        // done, the password is gone and the state and the client agree.
        withTimeout(5_000) { again.signOut().join() }
        assertEquals(SessionState.SignedOut, again.state.value)
        assertNull(again.api.value)
        assertFalse(File(dir, "password.sealed").exists())
        val (third, thirdSettings, _) = session(dir)
        assertEquals(SessionState.SignedOut, withTimeout(5_000) { third.restored() })
        assertEquals("http://$typed", thirdSettings.value.server)
    }

    /** A client that finds "music.example.org" at the stand-in server, as if it were out on
     *  the internet. */
    private fun awayClient() = OkHttpClient.Builder()
        .dns { host -> if (host == "music.example.org") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(host) }
        .build()

    @Test
    fun beyondTheHomeNetworkAnAddressWithoutASchemeIsTriedOverHttpsOnly() = runBlocking {
        val (session, _, _) = session(folder.newFolder(), awayClient())
        withTimeout(5_000) { session.restored() }
        // The stand-in only speaks plain HTTP, so HTTPS fails; plain HTTP isn't tried.
        session.signIn("music.example.org:${server.port}", "ada", "sesame")
        val result = withTimeout(20_000) { session.settled() }
        assertTrue(result.error != null)
        assertTrue(result.suggestHttp)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun plainHttpBeyondHomeIsUsedWhenAskedForAndWarnedAbout() = runBlocking {
        val (session, _, notices) = session(folder.newFolder(), awayClient())
        withTimeout(5_000) { session.restored() }
        session.signIn("HTTP://music.example.org:${server.port}", "ada", "sesame")
        val result = withTimeout(20_000) { session.settled() }
        assertNull(result.error)
        val signedIn = session.state.value as SessionState.SignedIn
        assertEquals("http://music.example.org:${server.port}", signedIn.account.server)
        assertTrue(Notice.SignedInWithoutEncryption in notices.state.value)
    }

    /** Signs in to the stand-in and waits for it to settle. */
    private suspend fun Session.signInHere() {
        signIn("http://127.0.0.1:${server.port}", "ada", "sesame")
        withTimeout(10_000) { settled() }
        assertTrue(state.value is SessionState.SignedIn)
    }

    @Test
    fun aSignOutWhileTheSavedSignInIsBeingReadWins() = runBlocking {
        val dir = folder.newFolder()
        val address = "http://127.0.0.1:${server.port}"
        SettingsStore.save(File(dir, "settings.json"), Settings(server = address, username = "ada"))
        PasswordVault(File(dir, "password.sealed"), PlainBox).save(address, "ada", "sesame")
        // Opening the password waits until told to go on.
        val opening = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val slowBox = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray {
                opening.countDown()
                goOn.await()
                return sealed
            }
        }
        val (session, _, _) = session(dir, vault = PasswordVault(File(dir, "password.sealed"), slowBox))
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        val out = session.signOut()
        goOn.countDown()
        withTimeout(5_000) { out.join() }
        // The restore had the password in hand, but the sign-out came after it was asked for.
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(session.api.value)
        assertFalse(File(dir, "password.sealed").exists())
    }

    @Test
    fun signingInStraightAfterSigningOutKeepsTheNewPassword() = runBlocking {
        val dir = folder.newFolder()
        val (session, _, _) = session(dir)
        withTimeout(5_000) { session.restored() }
        session.signInHere()
        // No waiting between the two: the sign-out's deletion must not land after the
        // sign-in has saved its password.
        session.signOut()
        session.signInHere()
        val signedIn = session.state.value as SessionState.SignedIn
        assertEquals(signedIn.api, session.api.value)
        assertEquals("sesame", PasswordVault(File(dir, "password.sealed"), PlainBox).load(signedIn.account.server, "ada"))
        // And it survives a restart.
        val (again, _, _) = session(dir)
        assertTrue(withTimeout(5_000) { again.restored() } is SessionState.SignedIn)
    }

    @Test
    fun aPasswordThatCantBeRemovedLeavesTheAccountSignedInAndSaysSo() = runBlocking {
        val dir = folder.newFolder()
        val vaultDir = File(dir, "vault").apply { mkdirs() }
        val (session, _, notices) = session(dir, vault = PasswordVault(File(vaultDir, "password.sealed"), PlainBox))
        withTimeout(5_000) { session.restored() }
        session.signInHere()
        // A folder the file can't be removed from.
        assertTrue(vaultDir.setWritable(false))
        try {
            withTimeout(5_000) { session.signOut().join() }
            assertTrue(session.state.value is SessionState.SignedIn)
            assertTrue(File(vaultDir, "password.sealed").exists())
            assertTrue(Notice.SignOutFailed in notices.state.value)
        } finally {
            vaultDir.setWritable(true)
        }
        // Once it can be removed, signing out works and the notice goes.
        withTimeout(5_000) { session.signOut().join() }
        assertEquals(SessionState.SignedOut, session.state.value)
        assertFalse(File(vaultDir, "password.sealed").exists())
        assertFalse(Notice.SignOutFailed in notices.state.value)
    }

    @Test
    fun aWrongPasswordSaysSoAndSignsNobodyIn() = runBlocking {
        val (session, _, _) = session(folder.newFolder())
        withTimeout(5_000) { session.restored() }
        session.signIn("http://127.0.0.1:${server.port}", "ada", "guess")
        val result = withTimeout(10_000) { session.settled() }
        assertEquals("Wrong username or password.", result.error)
        assertEquals(SessionState.SignedOut, session.state.value)
    }
}
