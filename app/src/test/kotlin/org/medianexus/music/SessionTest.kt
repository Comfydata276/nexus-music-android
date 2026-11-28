package org.medianexus.music

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Handed
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.md5Hex
import org.medianexus.music.nexus.Folders
import org.medianexus.music.nexus.KeptAccount
import org.medianexus.music.nexus.KeptMusic
import org.medianexus.music.nexus.NexusKept
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.accountDirs
import org.medianexus.music.store.accountKey

/**
 * The phone's side of a sign-in that the Nexus server's machine made: **a start from what it left**
 * (the music login, kept sealed, and who it is for), with nothing asked of the backend and
 * nothing of the shared library waited for; **a music login handed over**, renewed in place or
 * taken on as an account; **section 11's carry-over** of the folder this phone had, once; and
 * **its being forgotten**.
 */
class SessionTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val answered = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                answered.incrementAndGet()
                return MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","serverVersion":"0.58.0"}}""").build()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val address get() = server.url("/").toString().trimEnd('/')

    private class Made(val session: Session, val settings: SettingsStore, val kept: NexusKept, val folders: Folders, val dir: File)

    private fun made(dir: File = folder.newFolder(), before: Pair<String, String>? = null, takeOn: suspend (String) -> Boolean = { true }): Made {
        val settings = SettingsStore.open(File(dir, "settings.json"), scope, Dispatchers.IO)
        if (before != null) {
            settings.update { it.copy(server = before.first, username = before.second) }
            runBlocking { settings.flush() }
        }
        val kept = NexusKept(File(dir, "nexus-signin.sealed"), File(dir, "nexus-device.sealed"), PlainBox)
        val folders = Folders(File(dir, "files/accounts/carried.json"))
        val session = Session(settings, kept, folders, OkHttpClient(), OfflineSwitch(), { key -> accountDirs(File(dir, "files"), File(dir, "cache"), key) }, Notices(), scope, Dispatchers.IO, takeOn = takeOn, carriesOver = { address, user, music -> music.carriesOver(address, user) })
        return Made(session, settings, kept, folders, dir)
    }

    private fun music(name: String = "ada", count: Long = 1, salt: String = TEST_SALT, at: String = address) = KeptMusic(at, name, Handed(salt, md5Hex("sesame$salt"), count.toString()))

    /** A music server's address over HTTPS (the backend hands no other), where nothing answers. */
    private val secure = "https://127.0.0.1:9"

    /** Who a login is for: the backend's number for the account. */
    private fun who(account: String = "7") = KeptAccount("https://nexus.example.org", "0123456789abcdef0123456789abcdef", account)

    private fun signedIn(session: Session): SessionState.SignedIn = runBlocking {
        withTimeout(10_000) { session.state.first { it is SessionState.SignedIn } as SessionState.SignedIn }
    }

    /** What the machine keeps at a sign-in, in its own shapes, as a cold start reads it. */
    private fun keep(kept: NexusKept, music: KeptMusic, who: KeptAccount) {
        kept.save(
            NexusKept.Parts(
                buildJsonObject {
                    put("nexus", who.backend); put("backend_id", who.backendId); put("account", who.account.toLong()); put("email", "ada@example.org"); put("name", "Ada")
                    put("device", 1); put("restore", 1); put("life", 1); put("change", 1); put("session", "ab".repeat(32))
                    put("music", buildJsonObject { put("address", music.address); put("name", music.name); put("salt", music.handed.salt); put("token", music.handed.token); put("count", music.handed.count.toLong()) })
                },
                emptyList(),
            ),
        )
    }

    @Test
    fun aColdStartPlaysFromWhatSignInLeftAndAsksNothing() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val first = made(dir)
        keep(first.kept, music(), who())
        // The app starts again over what was kept.
        val again = made(dir)
        val now = signedIn(again.session)
        assertEquals("ada", now.account.username)
        assertEquals(TEST_SALT, now.api.login.now.salt)
        assertEquals(md5Hex("sesame$TEST_SALT"), now.api.login.now.token)
        // The person's folder is who they are to the backend, not the music server and user.
        assertEquals(Folders.personOf(who()), now.account.key)
        assertNotEquals(accountKey(now.api.server, "ada"), now.account.key)
        assertEquals("nothing was asked of the music server, or of anyone", 0, answered.get())
    }

    @Test
    fun aMusicLoginHandedForTheSameServerAndUserIsPutInPlaceInTheSameClient() = runBlocking<Unit> {
        val m = made()
        m.session.handed(music(), who())
        val first = signedIn(m.session)
        val other = "f".repeat(64)
        m.session.handed(music(count = 2, salt = other), who())
        withTimeout(10_000) { while (first.api.login.now.count != "2") kotlinx.coroutines.delay(5) }
        assertSame("the account was made again for a renewal", first, m.session.state.value)
        assertEquals(other, first.api.login.now.salt)
    }

    @Test
    fun theFolderThisPhoneHadIsCarriedOverOnceToTheFirstPersonHandedTheSameLogin() = runBlocking<Unit> {
        val m = made(before = secure to "ada")
        m.session.handed(music("ada", at = secure), who("7"))
        val ada = signedIn(m.session)
        assertEquals("the folder this phone had", accountKey(secure, "ada"), ada.account.key)
        assertEquals("", m.settings.value.server)
        assertEquals("", m.settings.value.username)
        // Signed out; someone else later handed the same music user does not get it.
        m.session.forgotten().await()
        m.session.handed(music("ada", at = secure), who("8"))
        withTimeout(10_000) { m.session.state.first { it is SessionState.SignedIn && (it as SessionState.SignedIn).account.key != ada.account.key } }
        // And Ada, signing in again, still has it.
        m.session.forgotten().await()
        m.session.handed(music("ada", at = secure), who("7"))
        withTimeout(10_000) { m.session.state.first { it is SessionState.SignedIn && (it as SessionState.SignedIn).account.key == ada.account.key } }
    }

    @Test
    fun aFirstPersonHandedAnotherLoginDoesNotGetTheFolderAndNobodyLaterDoes() = runBlocking<Unit> {
        val m = made(before = secure to "ada")
        m.session.handed(music("grace", at = secure), who("8"))
        val grace = signedIn(m.session)
        assertNotEquals(accountKey(secure, "ada"), grace.account.key)
        assertEquals("", m.settings.value.server)
        m.session.forgotten().await()
        m.session.handed(music("ada", at = secure), who("7"))
        val ada = withTimeout(10_000) { m.session.state.first { it is SessionState.SignedIn && (it as SessionState.SignedIn).account.username == "ada" } } as SessionState.SignedIn
        assertEquals(Folders.personOf(who("7")), ada.account.key)
    }

    @Test
    fun forgettingTheMusicLoginSignsOutTakesTheQueueAndRecentlyPlayedAndLeavesDownloads() = runBlocking<Unit> {
        val m = made()
        m.session.handed(music(), who())
        val ada = signedIn(m.session)
        val data = ada.account.dirs.data.apply { mkdirs() }
        File(data, "queue.json").writeText("{}")
        File(data, "history.json").writeText("[]")
        File(data, "downloads").mkdirs()
        File(data, "downloads/s1.flac").writeText("music")
        m.session.forgotten().await()
        assertEquals(SessionState.SignedOut, m.session.state.value)
        assertFalse(File(data, "queue.json").exists())
        assertFalse(File(data, "history.json").exists())
        assertTrue(File(data, "downloads/s1.flac").isFile)
        assertEquals("", m.settings.value.folder)
    }

    @Test
    fun anAccountThatCouldNotBeTakenOnIsNotSignedIn() = runBlocking<Unit> {
        val m = made(takeOn = { false })
        m.session.handed(music(), who())
        withTimeout(10_000) { m.session.signIn.first { it.choicesNotPutAway } }
        assertFalse(m.session.state.value is SessionState.SignedIn)
    }
}
