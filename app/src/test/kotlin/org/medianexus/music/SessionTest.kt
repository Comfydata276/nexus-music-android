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
        val folders = Folders(carried(dir))
        val settings = SettingsStore.open(File(dir, "settings.json"), scope, Dispatchers.IO, folders::test1ClearedLegacySignIn)
        if (before != null) {
            settings.update { it.copy(server = before.first, username = before.second) }
            runBlocking { settings.flush() }
        }
        val kept = NexusKept(File(dir, "nexus-signin.sealed"), File(dir, "nexus-device.sealed"), PlainBox)
        val session = Session(settings, kept, folders, OkHttpClient(), OfflineSwitch(), { key -> accountDirs(File(dir, "files"), File(dir, "cache"), key) }, Notices(), scope, Dispatchers.IO, takeOn = takeOn, carriesOver = { address, user, music -> music.carriesOver(address, user) })
        return Made(session, settings, kept, folders, dir)
    }

    private fun carried(dir: File) = File(dir, "files/accounts/carried.json")

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
        kept.save(NexusKept.Parts(savedJson(music, who), emptyList()))
    }

    private fun savedJson(music: KeptMusic, who: KeptAccount, signOut: JsonObject? = null): JsonObject = buildJsonObject {
        put("nexus", who.backend); put("backend_id", who.backendId); put("account", who.account.toLong()); put("email", "ada@example.org"); put("name", "Ada")
        put("device", 1); put("restore", 1); put("life", 1); put("change", 1); put("session", "ab".repeat(32))
        if (signOut != null) put("sign_out", signOut)
        put("music", buildJsonObject { put("address", music.address); put("name", music.name); put("salt", music.handed.salt); put("token", music.handed.token); put("count", music.handed.count.toLong()) })
    }

    /** **The real start seam**: the shared core's machine, started over what is kept, its Hand
     *  and Forget fed to the session exactly as the app does (`Graph.makeNexus`). Nothing is
     *  asked (Offline is on, and no ask is let out anyway). */
    private fun machine(m: Made): org.medianexus.music.nexus.Nexus = org.medianexus.music.nexus.Nexus(
        kept = m.kept,
        side = object : org.medianexus.music.nexus.Nexus.Side {
            override fun handLogin(music: KeptMusic, account: KeptAccount?, generation: String) = m.session.handed(music, account, generation)
            override fun restored(music: KeptMusic, account: KeptAccount, generation: String) = m.session.restoredFrom(music, account, generation)
            override fun started() = m.session.started()
            override fun notKept() = m.session.notKept()
            override fun musicForgotten() {
                m.session.forgotten()
            }
            override fun inside(host: String): Boolean? = null
            override val permission: Boolean? get() = null
        },
        userAgent = "test", appVersion = "0.41.0", deviceName = "test phone", offlineNow = { true },
    ).also { machines += it; it.start() }

    private val machines = java.util.concurrent.CopyOnWriteArrayList<org.medianexus.music.nexus.Nexus>()

    @After
    fun stopMachines() = machines.forEach { it.stop() }

    /** Waits until the session has left Restoring (the machine's start is done). */
    private fun settled(session: Session): SessionState = runBlocking { withTimeout(10_000) { session.restored() } }

    @Test
    fun nothingIsPublishedBeforeTheMachineHasStarted() = runBlocking<Unit> {
        val dir = folder.newFolder()
        keep(made(dir).kept, music(at = secure), who())
        val again = made(dir)
        kotlinx.coroutines.delay(300)
        assertEquals("published from the file, without the machine", SessionState.Restoring, again.session.state.value)
        machine(again)
        assertTrue(settled(again.session) is SessionState.SignedIn)
    }

    @Test
    fun aSignedOutLoginFoundAtStartIsForgottenAndNeverPublished() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val first = made(dir)
        val backend = who().backendId
        val secret = "cd".repeat(32)
        // A sign-out cut between keeping its note and forgetting the sign-in.
        first.kept.save(
            NexusKept.Parts(
                savedJson(music(at = secure), who(), signOut = buildJsonObject { put("session", 5); put("secret", secret) }),
                listOf(buildJsonObject {
                    put("request", 9); put("revoke", buildJsonObject { put("session", 5); put("secret", secret) })
                    put("nexus", who().backend); put("backend_id", backend); put("restore", 1); put("made_ms", System.currentTimeMillis())
                }),
            ),
        )
        val again = made(dir)
        machine(again)
        assertEquals(SessionState.SignedOut, settled(again.session))
        withTimeout(10_000) { while (again.kept.load().saved != null) kotlinx.coroutines.delay(10) }
        assertEquals("the note is kept", 1, again.kept.load().notes.size)
    }

    @Test
    fun aSignInFileThatDoesNotOpenIsRetiredAndNobodyIsSignedIn() = runBlocking<Unit> {
        val dir = folder.newFolder()
        File(dir, "nexus-signin.sealed").writeText("not what the machine wrote")
        val again = made(dir)
        machine(again)
        assertEquals(SessionState.SignedOut, settled(again.session))
        assertFalse("its old bytes were left to be taken up later", File(dir, "nexus-signin.sealed").exists())
    }

    @Test
    fun notesThatDoNotReadAreForgottenByTheirOwnPlaces() {
        val good = buildJsonObject { put("backend_id", "b"); put("revoke", buildJsonObject { put("session", 3) }) }
        val parts = NexusKept.Parts(null, listOf(JsonPrimitive("bad"), JsonPrimitive("worse"), good))
        // The machine names the first two by the places it was handed them at.
        val after = parts.withoutNoteAt(0).withoutNoteAt(1)
        assertEquals(listOf(good), after.notes)
    }

    @Test
    fun signInWithoutMusicLeavesTheEarlierFolderOpenUntilAMatch() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val m = made(dir, before = secure to "ada")
        val noMusic = JsonObject(savedJson(music(at = secure), who("7")) - "music")
        assertTrue(m.kept.save(NexusKept.Parts(noMusic, emptyList())))
        machine(m)
        assertEquals(SessionState.SignedOut, settled(m.session))
        assertFalse(carried(dir).exists())
        assertEquals(secure, m.settings.value.server)
        m.session.handed(music(at = secure), who("7"))
        assertEquals(accountKey(secure, "ada"), signedIn(m.session).account.key)
    }

    @Test
    fun nonMatchingLoginLeavesTheEarlierFolderUntouchedForTheOwner() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val m = made(dir, before = secure to "ada")
        val earlierKey = accountKey(secure, "ada")
        fun data(key: String) = accountDirs(File(dir, "files"), File(dir, "cache"), key).data
        val earlier = data(earlierKey).apply { mkdirs() }
        File(earlier, "queue.json").writeText("{}")
        File(earlier, "history.json").writeText("[]")
        File(earlier, "downloads").mkdirs()
        File(earlier, "downloads/s1.flac").writeText("music")
        m.settings.update { it.copy(sharedFor = earlierKey, pinnedPlaylists = listOf("a")) }
        assertTrue(m.settings.flush())
        val parkedChoices = earlier.resolveSibling(".$earlierKey.in-step.json")
        lateinit var current: Made
        current = made(dir, takeOn = { key ->
            org.medianexus.music.sync.Belongs.takeOn(current.settings, key, ::data, earlierKey to parkedChoices)
        })
        current.session.handed(music("grace", at = secure), who("8"))
        val grace = signedIn(current.session)
        assertEquals(Folders.personOf(who("8")), grace.account.key)
        assertTrue(current.settings.value.pinnedPlaylists.isEmpty())
        assertFalse(File(earlier, "in-step.json").exists())
        assertTrue(parkedChoices.isFile)
        assertFalse(carried(dir).exists())
        assertEquals(secure, current.settings.value.server)
        current.session.forgotten().await()
        assertTrue(current.settings.flush())

        val again = made(dir, takeOn = { key ->
            org.medianexus.music.sync.Belongs.takeOn(current.settings, key, ::data, earlierKey to parkedChoices)
        })
        current = again
        again.session.handed(music(at = secure), who("7"))
        val ada = signedIn(again.session)
        assertEquals(earlierKey, ada.account.key)
        assertTrue(File(ada.account.dirs.data, "downloads/s1.flac").isFile)
        assertTrue(File(ada.account.dirs.data, "queue.json").isFile)
        assertTrue(File(ada.account.dirs.data, "history.json").isFile)
        assertTrue(ada.account.ownsSharedRecord)
        assertEquals(listOf("a"), again.settings.value.pinnedPlaylists)
        assertEquals("", again.settings.value.server)
    }

    @Test
    fun theFirstOfTwoMatchingAccountsKeepsTheFolderAcrossRestart() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val m = made(dir, before = secure to "ada")
        m.session.handed(music(at = secure), who("7"))
        assertEquals(accountKey(secure, "ada"), signedIn(m.session).account.key)
        m.session.forgotten().await()
        assertTrue(m.settings.flush())
        val again = made(dir)
        again.session.handed(music(at = secure), who("8"))
        assertEquals(Folders.personOf(who("8")), signedIn(again.session).account.key)
        assertEquals(accountKey(secure, "ada"), again.folders.of(Folders.personOf(who("7"))))
    }

    @Test
    fun test1FirstPersonStillWaitingLeavesTheFolderOpenToAnotherMatch() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val m = made(dir, before = secure to "ada")
        val first = Folders.personOf(who("8"))
        carried(dir).apply { parentFile!!.mkdirs(); writeText("""{"person":"$first","undecided":true}""") }
        m.session.handed(music(at = secure), who("7"))
        assertEquals(accountKey(secure, "ada"), signedIn(m.session).account.key)
    }

    @Test
    fun test1AssignmentWithAFolderStillBelongsToTheRecordedOwner() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val m = made(dir)
        val first = Folders.personOf(who("8"))
        val legacy = accountKey(secure, "ada")
        carried(dir).apply { parentFile!!.mkdirs(); writeText("""{"person":"$first","folder":"$legacy"}""") }
        m.session.handed(music(at = secure), who("7"))
        assertEquals(Folders.personOf(who("7")), signedIn(m.session).account.key)
        assertEquals(legacy, m.folders.of(first))
    }

    @Test
    fun test1NobodyRecordReopensUsingTheReleasedSignInFromTheBackup() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val first = Folders.personOf(who("8"))
        val file = File(dir, "settings.json")
        assertTrue(SettingsStore.save(file, org.medianexus.music.store.Settings(folder = first)))
        assertTrue(SettingsStore.save(SettingsStore.backupOf(file), org.medianexus.music.store.Settings(server = secure, username = "ada")))
        carried(dir).apply { parentFile!!.mkdirs(); writeText("""{"person":"$first"}""") }
        // Even if the app is started once without anyone signing in, the backup stays.
        made(dir)
        val m = made(dir)
        m.session.handed(music(at = secure), who("7"))
        assertEquals(accountKey(secure, "ada"), signedIn(m.session).account.key)
    }

    @Test
    fun anotherPersonHandedTheSameMusicUserIsAnotherAccountAndTheFirstLoginIsLetGo() = runBlocking<Unit> {
        val m = made()
        m.session.handed(music(), who("7"))
        val ada = signedIn(m.session)
        m.session.handed(music(salt = "e".repeat(64)), who("8"))
        val grace = withTimeout(10_000) { m.session.state.first { it is SessionState.SignedIn && it !== ada } } as SessionState.SignedIn
        assertNotEquals("renewed in place for someone else", ada.account.key, grace.account.key)
        assertTrue("the first person's login still admitted", ada.api.login.isRefused)
    }

    @Test
    fun aQueueWrittenAfterSignOutIsNotPutBack() = runBlocking<Unit> {
        val m = made()
        m.session.handed(music(), who())
        val ada = signedIn(m.session)
        val data = ada.account.dirs.data.apply { mkdirs() }
        File(data, "queue.json").writeText("{}")
        m.session.forgotten().await()
        // The queue's saver, which outlives the account, writes its last snapshot late.
        val late = runCatching { org.medianexus.music.store.writeAtomically(File(data, "queue.json"), "{\"late\":1}".toByteArray()) }
        assertTrue(late.isFailure)
        assertFalse(File(data, "queue.json").exists())
        // Signed in again, the account's files are its own to write.
        m.session.handed(music(), who())
        signedIn(m.session)
        org.medianexus.music.store.writeAtomically(File(data, "queue.json"), "{}".toByteArray())
        assertTrue(File(data, "queue.json").exists())
    }

    @Test
    fun aColdStartPlaysFromWhatSignInLeftAndAsksNothing() = runBlocking<Unit> {
        val dir = folder.newFolder()
        val first = made(dir)
        keep(first.kept, music(at = secure), who())
        // The app starts again over what was kept: through the machine's start.
        val again = made(dir)
        machine(again)
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
