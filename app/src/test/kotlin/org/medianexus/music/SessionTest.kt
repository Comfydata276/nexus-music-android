package org.medianexus.music

import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError
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
    /** Requests the stand-in understood and answered (a TLS hello sent to it doesn't count). */
    private val answered = AtomicInteger()
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
                answered.incrementAndGet()
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
        assertEquals(0, answered.get())
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
    fun aSignOutWhileTheSignInIsBeingSavedWins() = runBlocking {
        val dir = folder.newFolder()
        // Saving the password waits until told to go on: the sign-in is past its first look
        // at whether it has been overtaken, and has not yet said "signed in".
        val saving = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val slowBox = object : SecretBox {
            override fun seal(plain: ByteArray): ByteArray {
                saving.countDown()
                goOn.await()
                return plain
            }
            override fun open(sealed: ByteArray) = sealed
        }
        val (session, _, notices) = session(dir, vault = PasswordVault(File(dir, "password.sealed"), slowBox))
        withTimeout(5_000) { session.restored() }
        // Everything `state` says from here on, as it says it.
        val said = java.util.concurrent.CopyOnWriteArrayList<SessionState>()
        val watching = scope.launch(Dispatchers.Unconfined) { session.state.collect { said += it } }
        session.signIn("http://127.0.0.1:${server.port}", "ada", "sesame")
        assertTrue(saving.await(10, TimeUnit.SECONDS))
        val out = session.signOut()
        goOn.countDown()
        withTimeout(5_000) { out.join() }
        watching.cancel()
        // Nobody was signed in, even for a moment: nothing built an account's objects, and
        // nothing was told the sign-in had gone through.
        assertTrue(said.toString(), said.none { it is SessionState.SignedIn })
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(session.api.value)
        assertFalse(File(dir, "password.sealed").exists())
        assertTrue(notices.state.value.isEmpty())
        assertFalse(session.signIn.value.busy)
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
    fun settingsThatCantBeWrittenLeaveNobodySignedInAndNoPasswordSaved() = runBlocking {
        val dir = folder.newFolder()
        val (session, _, _) = session(dir)
        withTimeout(5_000) { session.restored() }
        // A folder where the settings file should go.
        File(dir, "settings.json/in-the-way").apply { parentFile!!.mkdirs() }.writeText("x")
        session.signIn("http://127.0.0.1:${server.port}", "ada", "sesame")
        val result = withTimeout(10_000) { session.settled() }
        assertTrue(result.settingsNotSaved)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertFalse(File(dir, "password.sealed").exists())
    }

    @Test
    fun signingInWorksInOfflineModeAndLeavesItOn() = runBlocking {
        // Saved as offline, with no password that can be opened: the sign-in screen.
        val dir = folder.newFolder()
        SettingsStore.save(File(dir, "settings.json"), Settings(offline = true))
        val offline = OfflineSwitch(initially = true)
        val (session, settings, _) = session(dir, offline = offline)
        assertEquals(SessionState.SignedOut, withTimeout(5_000) { session.restored() })
        session.signInHere()
        // Signed in, and still offline: the setting is as it was, and the client kept
        // doesn't go out.
        assertTrue(offline.on)
        settings.flush()
        assertTrue(settings.value.offline)
        assertTrue(SettingsStore.read(File(dir, "settings.json"))!!.offline)
        val api = (session.state.value as SessionState.SignedIn).api
        val before = answered.get()
        try {
            api.ping()
            fail("went out in offline mode")
        } catch (_: ApiError.Network) {
        }
        assertEquals(before, answered.get())
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
    // ---- A sign-in waiting on Android's local-network question -------------------------------

    @Test
    fun aSignInWaitingOnTheQuestionKeepsWhatWasTypedWhateverHappensToTheScreen() = runBlocking {
        val (session, _, _) = session(folder.newFolder())
        withTimeout(5_000) { session.restored() }
        // Continue: what was typed is held, and the system's question goes up.
        assertTrue(session.hold("http://127.0.0.1:${server.port}", "ada", "sesame"))
        // Only one question at a time: a second is turned away and holds nothing.
        assertFalse(session.hold("http://127.0.0.1:${server.port}", "eve", "other"))
        // The screen is rebuilt while the question is up (its fields are empty now): the
        // answer comes, and the sign-in goes out with what was held.
        assertTrue(session.resumeWaiting())
        withTimeout(10_000) { session.settled() }
        assertEquals("ada", (session.state.value as SessionState.SignedIn).account.username)
        // Used once, and let go.
        assertFalse(session.isWaiting)
        assertFalse(session.resumeWaiting())
    }

    @Test
    fun aQuestionAbandonedLetsGoOfThePassword() = runBlocking {
        val (session, _, _) = session(folder.newFolder())
        withTimeout(5_000) { session.restored() }
        assertTrue(session.hold("http://127.0.0.1:${server.port}", "ada", "sesame"))
        session.dropWaiting()
        assertFalse(session.isWaiting)
        assertFalse(session.resumeWaiting())
        assertEquals(SessionState.SignedOut, session.state.value)
        // A new question can be put.
        assertTrue(session.hold("http://127.0.0.1:${server.port}", "ada", "sesame"))
    }
}
