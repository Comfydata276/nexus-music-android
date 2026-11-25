package org.medianexus.music.sync

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.ResponseBody.Companion.toResponseBody
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic

/**
 * A phone and two PCs keeping in step through a real Navidrome: the end-to-end check
 * (docs/TESTING.md, "In step with other devices").
 *
 * The phone here is everything of the app's below its screens and its player: the real API
 * client, the exchange, the stores on disk, and the shared library it carries. Each PC is
 * `tools/instep pc`: the Windows app's own asking of the server and its library's exchange,
 * compiled from the Windows checkout beside this repository (0.40.5's code), with what the
 * PC holds kept in a file between its exchanges. Run by `scripts/instep-check`, which
 * starts a server of its own and says where it and the tool are; without that, this test is
 * passed over.
 */
class InStepLiveTest {
    /** No test here may wait for ever: one that did would hold the machine's one turn at
     *  building, for everyone, until someone noticed. */
    @get:org.junit.Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(180)

    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val io = Dispatchers.Unconfined
    private val phones = ArrayList<PretendPhone>()
    private val said = StringBuilder()

    private val server: String? = System.getenv("NEXUS_STEP_SERVER")
    private val tool: String? = System.getenv("NEXUS_STEP_TOOL")

    @After
    fun stop() {
        phones.forEach { runCatching { it.stop() } }
        scope.cancel()
        // (Each test adds its own lines: the tests of one run share the file.)
        if (said.isNotEmpty()) File("build/instep").apply { mkdirs() }.resolve("live.txt").appendText(said.toString())
    }

    private fun say(line: String) {
        said.append(line).append('\n')
        println(line)
    }

    private val http = okhttp3.OkHttpClient()
    private val passwords = mapOf("tester" to "tester-pass-1", "guest" to "guest-pass-1")

    private fun live() = !server.isNullOrEmpty() && !tool.isNullOrEmpty()

    private fun client(user: String = "tester", through: okhttp3.OkHttpClient = http) = Subsonic(server!!, user, passwords.getValue(user), through, OfflineSwitch())

    /** The server with no sync playlist for either account: as each test here begins. */
    private fun fresh() = runBlocking {
        for (user in passwords.keys) {
            val api = client(user)
            api.playlistsStated().value.filter { it.name == PLAYLIST_NAME && it.owner == user }.forEach { api.deletePlaylist(it.id) }
        }
    }

    /** The real server, as the exchange asks it; `gate` is held before each question. */
    private fun asked(api: Subsonic, gate: suspend (String) -> Unit = {}) = object : StepServer {
        override suspend fun playlists() = run { gate("list"); api.playlistsStated() }
        override suspend fun playlist(id: String) = run { gate("read"); api.playlistStated(id) }
        override suspend fun create(name: String) = run { gate("make"); api.once().createPlaylist(name, emptyList()).id }
        override suspend fun setComment(id: String, comment: String) = run { gate("write"); api.setPlaylistComment(id, comment) }
    }

    /**
     * A way to the server on which its answers about playlists come without these fields, as
     * Navidrome sends them to a client it has been told to say little to
     * (`Subsonic.MinimalClients`). Everything else is the server's own answer.
     */
    private fun leavingOut(vararg fields: String): okhttp3.OkHttpClient = http.newBuilder().addInterceptor { chain ->
        val answer = chain.proceed(chain.request())
        val body = answer.body
        if (!chain.request().url.encodedPath.contains("getPlaylist") || body == null) return@addInterceptor answer
        var text = body.string()
        for (field in fields) {
            val value = """"$field":"(?:[^"\\]|\\.)*""""
            text = text.replace(Regex(",$value"), "").replace(Regex("$value,?"), "")
        }
        answer.newBuilder().body(text.toResponseBody(body.contentType())).build()
    }.build()

    /** A phone over real files in `root` (see [PretendPhone]), for the account "a". */
    private fun phoneIn(root: File = folder.newFolder()) = PretendPhone(root, "a", scope).also { phones += it }

    private fun PretendPhone.exchange(with: Subsonic, clock: () -> Long = ::now, notes: MutableList<String> = ArrayList()): InStep.Outcome =
        runBlocking { step(asked(with), clock = clock, notes = notes).exchange() }.also { say("  the phone exchanged: $it") }

    /** Three of the account's own playlists and six songs, as the test server has them. */
    private class Library(val songs: List<Song>, val p1: String, val p2: String, val p3: String)

    private fun library(api: Subsonic): Library {
        val songs: List<Song> = runBlocking { api.libraryPage(0, 0, 6, 0).songs }
        val playlists = runBlocking { api.playlists() }.filter { it.name != PLAYLIST_NAME && it.owner == "tester" }
        assertTrue("the test server has songs and playlists", songs.size == 6 && playlists.size >= 3)
        return Library(songs, playlists[0].id, playlists[1].id, playlists[2].id)
    }

    /** The Windows code, run: its answer, as it printed it. */
    private fun windows(mode: String, user: String, vararg more: String): JsonObject {
        val password = if (user == "tester") "tester-pass-1" else "guest-pass-1"
        val process = ProcessBuilder(listOf(tool!!, mode, "--server", server!!, "--user", user, "--password", password) + more).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertEquals("the Windows code ($mode) failed: $err", 0, process.waitFor())
        return if (out.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(out).jsonObject
    }

    private fun peek(user: String = "tester") = windows("peek", user)
    private fun JsonObject.text(name: String): String? = this[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    /** A PC on 0.40.5's code: what it holds is the file `tools/instep pc` reads and writes. */
    private inner class Pc(val name: String) {
        private val state = File(folder.root, "$name.json")

        /** One exchange, after the person has done `does` on it (`--pin <id>` and so on:
         *  see `tools/instep/src/household.rs`). What it printed. */
        fun exchange(vararg does: String): JsonObject = windows("pc", "tester", "--state", state.path, *does).also {
            say("  $name (0.40.5's code) exchanged${if (does.isEmpty()) "" else " after ${does.toList().chunked(2).joinToString { (what, value) -> "${what.removePrefix("--")} ${value.take(24)}" }}"}: wrote ${it.wrote()}${it.text("held")?.let { held -> ", held: $held" }.orEmpty()}${it.text("stopped")?.let { why -> ", stopped: $why" }.orEmpty()}")
        }
    }

    private fun JsonObject.wrote() = getValue("wrote").jsonPrimitive.boolean
    private fun JsonObject.pins() = getValue("pins").jsonArray.map { it.jsonPrimitive.content }
    private fun record() = SharedText.read(peek().text("comment")!!)!!

    private fun now() = System.currentTimeMillis() / 1000

    @Test
    fun aPhoneAndTwoPCsKeepInStep() {
        assumeTrue("run by scripts/instep-check", live())
        fresh()
        val api = client()
        val lib = library(api)
        val (pc1, pc2) = Pc("pc-one") to Pc("pc-two")
        val t = now() - 1_000
        say("1. A PC with a record full of its own: pins, a theme, a list's sort and another's shuffle, a song's date, plays. A second PC, and then a phone that has never kept in step.")
        var out = pc1.exchange(
            "--pin", lib.p1, "--pin", lib.p2, "--theme", "Ocean", "--sort", "playlist:${lib.p1}=3,desc", "--shuffle", "liked=on",
            "--added", "${lib.p1}=${lib.songs[0].id},$t", "--play", "${lib.songs[1].id}=${t + 10},playlist:${lib.p1}", "--play", "${lib.songs[2].id}=${t + 20}",
        )
        assertTrue(out.wrote())
        out = pc2.exchange()
        assertFalse("the second PC has nothing to add", out.wrote())
        assertEquals(listOf(lib.p1, lib.p2), out.pins())
        val full = peek().text("comment")!!
        // The phone's first exchange leaves the record byte for byte as the PC wrote it...
        val phone = phoneIn()
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertEquals("the record on the server, byte for byte", full, peek().text("comment"))
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertEquals(full, peek().text("comment"))
        assertFalse("it has written nothing, so it has kept no copy either", phone.keptFirst.exists())
        // ...and the phone has everything the PC had.
        val s = phone.settings.value
        assertEquals(listOf(lib.p1, lib.p2) to "Ocean", s.pinnedPlaylists to s.theme)
        assertEquals(3 to true, s.lists.getValue("playlist:${lib.p1}").let { it.sort to it.descending })
        assertTrue(s.lists.getValue("liked").shuffle)
        assertEquals(t, phone.added.dates(lib.p1, listOf(lib.songs[0]))[0])
        assertEquals(listOf(lib.songs[2].id, lib.songs[1].id), phone.history.state.value.map { it.song.id })
        assertEquals(2, phone.log.state.value.size)
        say("   the phone's first exchange, and its second: the record byte for byte as the PC wrote it (${full.length} characters); the phone shows the PC's pins, theme, sort, shuffle, date and plays")

        say("2. A pin and a play on the phone; then the second PC unpins one of the first's.")
        phone.pin(lib.p3)
        phone.log.add(lib.songs[3].id, now())
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertEquals("the record as the phone first found it, kept before its first write", full, phone.keptFirst.readText())
        out = pc1.exchange()
        assertFalse(out.wrote())
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), out.pins())
        assertEquals(3, out.getValue("plays").jsonPrimitive.int)
        out = pc2.exchange("--unpin", lib.p1)
        assertTrue(out.wrote())
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertEquals(listOf(lib.p2, lib.p3), phone.pins)
        assertEquals(listOf(lib.p2, lib.p3), pc1.exchange().pins())
        // Nothing of the first PC's was lost on the way round.
        val now = record()
        assertEquals("Ocean", now.theme)
        assertEquals(SharedText.read(full)!!.lists, now.lists)
        assertEquals(SharedText.read(full)!!.added, now.added)
        say("   all three show the same pins; the first PC's theme, sort, shuffle and date are as it wrote them")

        say("3. Everyone looks again with nothing new to say.")
        val settled = peek().text("comment")!!
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertFalse(pc1.exchange().wrote())
        assertFalse(pc2.exchange().wrote())
        assertEquals("nobody wrote", settled, peek().text("comment"))
        assertEquals(1, peek().getValue("sync_playlists").jsonPrimitive.int)
        say("   nobody wrote, and the record is byte for byte the same")
    }

    @Test
    fun aPinMadeWhileThePhonesFirstReadIsOnItsWayIsAddedToThePCs() {
        assumeTrue("run by scripts/instep-check", live())
        fresh()
        val api = client()
        val lib = library(api)
        say("4. The PC has two playlists pinned. A phone's first read is still on its way when a third is pinned on it, and its process is killed.")
        val pc = Pc("pc-four")
        assertTrue(pc.exchange("--pin", lib.p1, "--pin", lib.p2).wrote())
        val root = folder.newFolder()
        val phone = phoneIn(root)
        val reached = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val first = scope.async { phone.step(asked(api) { reached.complete(Unit); held.await() }, clock = ::now).exchange() }
        runBlocking { withTimeout(30_000) { reached.await() } }
        // It has read nothing of the PC's yet: all it can show is its own pin.
        phone.pin(lib.p3)
        assertEquals(listOf(lib.p3), phone.pins)
        // Killed: the read never comes back, and what is on the disk is all there is.
        first.cancel()
        phone.stop()
        val again = phoneIn(root)
        assertEquals(listOf(lib.p3), again.pins)
        assertEquals(InStep.Outcome.DONE, again.exchange(api))
        assertEquals("the PC's pins, with the phone's added", listOf(lib.p1, lib.p2, lib.p3), pc.exchange().pins())
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), again.pins)
        say("   the PC ends with all three pinned, and so does the phone")
    }

    @Test
    fun aPhoneWith040sFilesIsUpgradedAndWhatItChoseLosesToWhatThePCChose() {
        assumeTrue("run by scripts/instep-check", live())
        fresh()
        val api = client()
        val lib = library(api)
        say("5. A phone with 0.4.0's files (that build kept the person's changes, waiting, though it shared nothing): a playlist pinned, Rose chosen, a sort and a shuffle. The PC then chooses Ember, and a sort of its own. The phone is upgraded to this build.")
        val pc = Pc("pc-five")
        assertTrue(pc.exchange("--pin", lib.p1, "--theme", "Ember", "--sort", "playlist:${lib.p1}=2,asc").wrote())
        val before = peek().text("comment")!!
        // The settings as 0.4.0 wrote them (the fixture: see its ABOUT.txt), real ids put in.
        val root = folder.newFolder()
        File(root, "settings.json").writeText(
            javaClass.getResource("/instep-upgrade/settings-0.4.0.json")!!.readText()
                .replace("ACCOUNT", "a").replace("PLAYLIST_A", lib.p1).replace("PLAYLIST_B", lib.p2).replace("PLAYLIST_C", lib.p1),
        )
        val phone = phoneIn(root)
        assertEquals(listOf(lib.p2) to "Rose", phone.settings.value.let { it.pinnedPlaylists to it.theme })
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertEquals("the record as it found it, kept before its first write", before, phone.keptFirst.readText())
        val out = pc.exchange()
        assertFalse(out.wrote())
        // The PC's pin stands (the unpin that was waiting on the phone took back a pin of
        // the phone's own, and does not take the PC's), with the phone's after it.
        assertEquals(listOf(lib.p1, lib.p2), out.pins())
        // What was chosen on 0.4.0 was never a statement about the PCs: the PC's theme and
        // its sort stand, on every device.
        assertEquals("Ember", out.text("theme"))
        val now = record()
        assertEquals(SharedText.read(before)!!.let { it.theme to it.themeAt }, now.theme to now.themeAt)
        assertEquals(SharedText.read(before)!!.lists.getValue("playlist:${lib.p1}"), now.lists.getValue("playlist:${lib.p1}"))
        // The shuffle of a list the PC had said nothing about is offered, as chosen long ago.
        assertEquals(SharedPrefs(shuffle = true, at = 1), now.lists.getValue("liked"))
        val s = phone.settings.value
        assertEquals(listOf(lib.p1, lib.p2) to "Ember", s.pinnedPlaylists to s.theme)
        assertEquals(2 to false, s.lists.getValue("playlist:${lib.p1}").let { it.sort to it.descending })
        say("   every device ends on Ember and the PC's sort; the PC's pin stands with the phone's after it; a copy of the record as it was is on the phone")
    }

    @Test
    fun theAnswerToThePhonesWriteIsLostAndThePCThenSetsTheOpposite() {
        assumeTrue("run by scripts/instep-check", live())
        fresh()
        val api = client()
        val lib = library(api)
        say("6. The reviewer's case. The phone pins a playlist; the server takes the write; the answer never reaches the phone, and its process is killed. The PC sees the pin and deliberately unpins it. The phone starts again.")
        val pc = Pc("pc-six")
        assertTrue(pc.exchange("--pin", lib.p1, "--pin", lib.p2, "--theme", "Ocean").wrote())
        val root = folder.newFolder()
        val phone = phoneIn(root)
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        phone.pin(lib.p3)
        val real = asked(api)
        val losing = object : StepServer by real {
            override suspend fun setComment(id: String, comment: String) {
                real.setComment(id, comment)
                throw ApiError.Network("The connection dropped.")
            }
        }
        assertEquals(InStep.Outcome.FAILED, runBlocking { phone.step(losing, clock = ::now).exchange() })
        assertEquals("the server took the write", listOf(lib.p1, lib.p2, lib.p3), record().pins)
        phone.stop()
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), pc.exchange().pins())
        assertTrue(pc.exchange("--unpin", lib.p3).wrote())
        assertEquals(listOf(lib.p1, lib.p2), record().pins)
        // The phone, started again, still holds "pin it" as tried and unanswered.
        val again = phoneIn(root)
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), again.pins)
        assertEquals(InStep.Outcome.DONE, again.exchange(api))
        // KNOWN, ACCEPTED AND WRITTEN DOWN (the Windows repository's core/src/step.rs, and
        // its README): the change is made again, on top of the PC's unpin, as the newer.
        // Telling "my own write, unanswered" from "a later choice the other way" would need
        // the server to say which write a value came from. What must hold is that this is
        // all that happens: the one pin, and nothing else of the PC's touched.
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), record().pins)
        val out = pc.exchange()
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), out.pins())
        assertEquals("Ocean", out.text("theme"))
        assertFalse("the phone has nothing left waiting", again.onDisk().contains("\"tried\": true"))
        // If the PC had not touched it, the phone's second exchange would have written
        // nothing: made again over its own result, the change alters nothing.
        say("   as accepted and written down: the phone's pin is made again on top of the PC's unpin, and nothing else of the PC's is touched")
    }

    @Test
    fun aServerToldToSayLittleIsNotReadAsHavingNothing() {
        assumeTrue("run by scripts/instep-check", live())
        fresh()
        val api = client()
        val lib = library(api)
        say("7. The server's answers come without a playlist's owner and comment (as Navidrome sends them to a client it is told to say little to).")
        val pc = Pc("pc-seven")
        assertTrue(pc.exchange("--pin", lib.p1, "--pin", lib.p2, "--theme", "Ocean").wrote())
        val before = peek().text("comment")!!
        val little = client(through = leavingOut("owner", "comment"))
        runBlocking { little.playlistsStated() }.value.first { it.name == PLAYLIST_NAME }.let {
            assertNull(it.owner)
            assertNull(it.comment)
        }
        for (pinned in listOf(false, true)) {
            val phone = phoneIn()
            if (pinned) phone.pin(lib.p3)
            val notes = ArrayList<String>()
            assertEquals(InStep.Outcome.NOT_STATED, phone.exchange(little, notes = notes))
            assertEquals("the record on the server, byte for byte", before, peek().text("comment"))
            assertEquals("no second playlist was made beside it", 1, peek().getValue("sync_playlists").jsonPrimitive.int)
            // Nothing was read into it either: the phone shows what it had.
            assertEquals(if (pinned) listOf(lib.p3) else emptyList(), phone.pins)
            assertTrue(notes.single().contains("didn't say"))
            if (pinned) {
                // Once the server says whose it is and what is in it, the pin goes on top.
                assertEquals(InStep.Outcome.DONE, phone.exchange(api))
                assertEquals(listOf(lib.p1, lib.p2, lib.p3), pc.exchange().pins())
            }
        }
        say("   nothing read, made or written while it said neither; the pin went on top of the PC's once it did")

        say("7. A sync playlist just made, its comment empty: Navidrome leaves an empty comment out of every answer, and states the owner.")
        fresh()
        val made = runBlocking { api.once().createPlaylist(PLAYLIST_NAME, emptyList()).id }
        runBlocking { api.playlistStated(made) }.value.let {
            assertEquals("tester", it.owner)
            assertNull("an empty comment is in the answer after all: the rule can be the simpler one", it.comment)
        }
        // A phone with nothing to say leaves it empty; one with a pin fills it, in place.
        assertEquals(InStep.Outcome.DONE, phoneIn().exchange(api))
        assertEquals("", peek().text("comment").orEmpty())
        val busy = phoneIn()
        busy.pin(lib.p3)
        assertEquals(InStep.Outcome.DONE, busy.exchange(api))
        assertEquals(listOf(lib.p3), record().pins)
        assertEquals(made, peek().text("playlist"))
        assertEquals(1, peek().getValue("sync_playlists").jsonPrimitive.int)
        assertEquals("an empty record is kept as an empty file", "", busy.keptFirst.readText())
        say("   left empty by a phone with nothing to say; filled, in place, by one with a pin")
    }

    @Test
    fun aPhoneWhoseClockIsAnHourOutTakesInAndSharesNothing() {
        assumeTrue("run by scripts/instep-check", live())
        fresh()
        val api = client()
        val lib = library(api)
        say("8. A phone whose clock is an hour ahead of the server's (by the Date on the server's own answers), with a pin waiting.")
        val pc = Pc("pc-eight")
        assertTrue(pc.exchange("--pin", lib.p1, "--pin", lib.p2).wrote())
        val before = peek().text("comment")!!
        val phone = phoneIn()
        phone.pin(lib.p3)
        for (out in listOf(3_600L, -3_600L)) {
            val notes = ArrayList<String>()
            val wrong = phone.step(asked(api), clock = { now() + out }, notes = notes)
            repeat(2) { assertEquals(InStep.Outcome.CLOCK_WRONG, runBlocking { wrong.exchange() }) }
            assertTrue(wrong.clockWrong.value)
            assertEquals("said once, not at every exchange", 1, notes.size)
            assertEquals("the record on the server, byte for byte", before, peek().text("comment"))
            // What the PC has is taken in, and the pin still waits, on top of it on the screen.
            assertEquals(listOf(lib.p1, lib.p2, lib.p3), phone.pins)
        }
        // The clock put right: the pin goes, on top of the PC's.
        assertEquals(InStep.Outcome.DONE, phone.exchange(api))
        assertEquals(listOf(lib.p1, lib.p2, lib.p3), pc.exchange().pins())
        say("   an hour ahead, and an hour behind: the PC's pins taken in, nothing written; with the clock right, the pin went on top of the PC's")
    }
}
