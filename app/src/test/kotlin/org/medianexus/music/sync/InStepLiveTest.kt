package org.medianexus.music.sync

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Added
import org.medianexus.music.store.PlayLog
import org.medianexus.music.store.RecentlyPlayed
import org.medianexus.music.store.SettingsStore

/**
 * The phone's code and the Windows app's own code, exchanging in turn through a real
 * Navidrome: the end-to-end check (docs/TESTING.md, "In step with other devices").
 *
 * The phone here is everything of the app's below its screens and its player: the real API
 * client, the exchange, the stores on disk. The PC is `tools/instep`, which is the Windows
 * app's `exchange` and API client compiled from its source. Run by `scripts/instep-check`,
 * which starts a server of its own and says where it and the tool are; without that, this
 * test is passed over.
 */
class InStepLiveTest {
    /** No test here may wait for ever: one that did would hold the machine's one turn at
     *  building, for everyone, until someone noticed. */
    @get:org.junit.Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(180)

    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val io = Dispatchers.Unconfined
    private val logs = ArrayList<PlayLog>()
    private val said = StringBuilder()

    private val server: String? = System.getenv("NEXUS_STEP_SERVER")
    private val tool: String? = System.getenv("NEXUS_STEP_TOOL")

    @After
    fun stop() {
        runBlocking { logs.forEach { it.close().join() } }
        scope.cancel()
        if (said.isNotEmpty()) File("build/instep").apply { mkdirs() }.resolve("live.txt").writeText(said.toString())
    }

    private fun say(line: String) {
        said.append(line).append('\n')
        println(line)
    }

    private val http = okhttp3.OkHttpClient()

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

    /** A phone: an account's stores in a folder of its own, the real client, the exchange. */
    private inner class Phone(val name: String, user: String = "tester", var on: Boolean = true) {
        val root = folder.newFolder()
        val api = Subsonic(server!!, user, if (user == "tester") "tester-pass-1" else "guest-pass-1", http, OfflineSwitch())
        val settings = SettingsStore.open(File(root, "settings.json"), scope, io)
        val account = "account-of-$user"
        val added = Added(File(root, "playlist-added.json"), null, io)
        val history = RecentlyPlayed(File(root, "history.json"), scope, io)
        val log = PlayLog(File(root, "plays.log"), io).also { logs += it }
        val shared = PhoneShared(settings, account, added, history, log, read = { true }, playlists = { null }, songs = { ids -> ids.mapNotNull { id -> runCatching { api.song(id) }.getOrNull()?.takeIf { it.id == id } }.associateBy { it.id } })
        val step = InStep(
            object : StepServer {
                override suspend fun playlists() = api.playlistsStated()
                override suspend fun playlist(id: String) = api.playlistStated(id)
                override suspend fun create(name: String) = api.once().createPlaylist(name, emptyList()).id
                override suspend fun setComment(id: String, comment: String) = api.setPlaylistComment(id, comment)
            },
            shared, user, account, may = { on }, scope = scope, pause = {},
        )

        init {
            Belongs.takeOn(settings, account, { File(root, "accounts/$it").apply { mkdirs() } })
            runBlocking {
                history.open()
                log.open()
            }
        }

        fun exchange(): InStep.Outcome = runBlocking { step.exchange() }.also { say("  $name exchanged: $it") }
    }

    /** What the PC holds: the file `tools/instep pc` reads and writes. */
    private inner class Pc(val name: String) {
        val state: File = folder.newFile("$name.json")

        fun holds(): Shared = SharedText.read(state.readText()) ?: error("the PC's record couldn't be read")

        /** The person does something on the PC: its record changes, with the time. */
        fun change(with: (Shared) -> Shared) = state.writeText(SharedText.write(with(if (state.length() == 0L) Shared(layout = 1) else holds())))

        fun exchange(): JsonObject = windows("pc", "tester", "--state", state.path).also { say("  $name (Windows' code) exchanged: wrote ${it.getValue("wrote").jsonPrimitive.boolean}") }
    }

    private fun now() = System.currentTimeMillis() / 1000

    @Test
    fun thePhoneAndTheWindowsCodeExchangeInTurn() {
        assumeTrue("run by scripts/instep-check", !server.isNullOrEmpty() && !tool.isNullOrEmpty())
        val api = Subsonic(server!!, "tester", "tester-pass-1", http, OfflineSwitch())
        val songs: List<Song> = runBlocking { api.libraryPage(0, 0, 6, 0).songs }
        val playlists = runBlocking { api.playlists() }.filter { it.name != PLAYLIST_NAME && it.owner == "tester" }
        assertTrue("the test server has songs and playlists", songs.size == 6 && playlists.size >= 2)
        val (p1, p2) = playlists[0].id to playlists[1].id
        val t = now()

        say("1. Sharing off on the phone: nothing is read, made or written.")
        val off = Phone("phone (sharing off)", on = false)
        assertEquals(InStep.Outcome.NOT_NOW, off.exchange())
        assertNull("no sync playlist was made", peek().text("playlist"))

        say("2. A PC, with pins, a theme, a list's choices, history and plays, exchanges first.")
        val pc = Pc("pc")
        pc.change {
            it.copy(
                pins = listOf(p1, p2), pinsAt = t - 500, theme = "Ocean", themeAt = t - 490,
                lists = mapOf("playlist:$p1" to SharedPrefs(shuffle = true, sort = 4u, descending = true, sortChosen = true, at = t - 480), "liked" to SharedPrefs(sort = 5u, descending = true, sortChosen = true, at = t - 470)),
                added = mapOf(p1 to mapOf(songs[0].id to SharedNote(listOf(t - 460), t - 460))),
                history = listOf(Play(songs[1].id, t - 400), Play(songs[0].id, t - 450)),
                plays = listOf(Play(songs[1].id, t - 400), Play(songs[0].id, t - 450)),
                playSources = listOf(PlayFrom(songs[0].id, t - 450, "playlist:$p1")),
            )
        }
        assertTrue(pc.exchange().getValue("wrote").jsonPrimitive.boolean)
        val pcWrote = peek().text("comment")!!
        val id = peek().text("playlist")!!

        say("3. A phone freshly installed, with nothing of its own, exchanges: the PC's record is unchanged, byte for byte.")
        val phone = Phone("phone")
        assertEquals(InStep.Outcome.DONE, phone.exchange())
        assertEquals(pcWrote, peek().text("comment"))
        say("   the comment is the ${pcWrote.length} characters the Windows code wrote, exactly")
        // And the phone shows what the PC had: the playlists pinned, the theme, the list's
        // choices, the history with its songs looked up, the plays in its log.
        phone.settings.value.let { s ->
            assertEquals(listOf(p1, p2), s.pinnedPlaylists)
            assertEquals("Ocean", s.theme)
            assertEquals(4, s.lists.getValue("playlist:$p1").sort)
        }
        assertEquals(listOf(songs[1].title, songs[0].title), phone.history.state.value.map { it.song.title })
        assertEquals(listOf(songs[0].id to t - 450, songs[1].id to t - 400), phone.log.shared())
        assertEquals(listOf(Triple(songs[0].id, t - 450, "playlist:$p1")), phone.log.sources())
        // Again, holding all of it now: still nothing written.
        assertEquals(InStep.Outcome.DONE, phone.exchange())
        assertEquals(pcWrote, peek().text("comment"))

        say("4. The phone unpins one: the PC's next exchange has it unpinned, and writes nothing.")
        phone.settings.update { Changes.pin(it, p1, on = false) }
        assertEquals(InStep.Outcome.DONE, phone.exchange())
        assertFalse(pc.exchange().getValue("wrote").jsonPrimitive.boolean)
        assertEquals(listOf(p2), pc.holds().pins)
        // The PC pins another; the phone shows it.
        pc.change { it.copy(pins = listOf(p2, p1), pinsAt = now() + 1) }
        assertTrue(pc.exchange().getValue("wrote").jsonPrimitive.boolean)
        phone.exchange()
        assertEquals(listOf(p2, p1), phone.settings.value.pinnedPlaylists)

        say("5. Plays made on each appear once in the other.")
        val (onPhone, onPc) = Play(songs[2].id, now()) to Play(songs[3].id, now() + 2)
        phone.log.add(onPhone.song, onPhone.at, "playlist:$p2")
        phone.history.played(songs[2])
        pc.change { it.copy(plays = listOf(onPc) + it.plays, history = listOf(onPc) + it.history) }
        repeat(2) {
            phone.exchange()
            pc.exchange()
        }
        phone.exchange()
        assertEquals(1, phone.log.shared().count { it.first == onPc.song && it.second == onPc.at })
        assertEquals(1, phone.log.shared().count { it.first == onPhone.song })
        assertEquals(1, pc.holds().plays.count { it == onPhone })
        assertEquals(1, pc.holds().plays.count { it == onPc })
        assertEquals(4, pc.holds().plays.size)
        assertTrue(PlayFrom(onPhone.song, onPhone.at, "playlist:$p2") in pc.holds().playSources)
        assertEquals(songs[3].id, phone.history.state.value.first().song.id)
        // The phone has nothing to add to what the PC holds: they hold the same record.
        assertEquals(pc.holds(), Merge.merge(runBlocking { phone.shared.record() }!!, pc.holds(), now()))
        say("   the phone and the PC hold the same record: ${pc.holds().plays.size} plays, ${pc.holds().history.size} in history")

        say("6. A second phone, freshly installed, exchanges with the record as it now is: unchanged, byte for byte.")
        val before = peek().text("comment")!!
        val second = Phone("second phone")
        assertEquals(InStep.Outcome.DONE, second.exchange())
        assertEquals(before, peek().text("comment"))
        assertEquals(pc.holds(), Merge.merge(runBlocking { second.shared.record() }!!, pc.holds(), now()))

        say("7. A comment that isn't a record is left exactly as it is by the phone (Windows writes over it).")
        windows("plant", "tester", "--text", "do not delete: my notes")
        assertEquals(InStep.Outcome.UNREADABLE, phone.exchange())
        assertEquals("do not delete: my notes", peek().text("comment"))
        assertTrue(pc.exchange().getValue("wrote").jsonPrimitive.boolean)
        assertEquals(pc.holds(), SharedText.read(peek().text("comment")!!))

        say("8. A record in a later layout is read by the phone and not written.")
        val later = """{"nexus_music_sync":2,"pins":["from-the-future"],"pins_at":${now() + 50},"more":[1,2,3]}"""
        windows("plant", "tester", "--text", later)
        assertEquals(InStep.Outcome.NEWER_LAYOUT, phone.exchange())
        assertEquals(later, peek().text("comment"))
        assertEquals(listOf("from-the-future"), phone.settings.value.pinnedPlaylists)
        // (Put back as it was, for what follows. The phone holds the later layout's pins as
        // agreed, with their time, which is after anything here: a new phone takes its place.)
        windows("plant", "tester", "--text", before)

        say("9. Signed in as another account: nothing of the first's is shared into the second's record.")
        val guest = Phone("phone, as the second account", user = "guest")
        assertEquals(InStep.Outcome.DONE, guest.exchange())
        // (With nothing of its own to say, it makes no record at all.)
        assertEquals(0, peek("guest").getValue("sync_playlists").jsonPrimitive.int)
        guest.settings.update { Changes.pin(it, "guests-own", on = true) }
        assertEquals(InStep.Outcome.DONE, guest.exchange())
        val theirs = SharedText.read(peek("guest").text("comment")!!)!!
        assertEquals(Shared(layout = 1, pins = listOf("guests-own"), pinsAt = theirs.pinsAt), theirs)
        assertEquals(before, peek().text("comment"))
        assertEquals(1, peek().getValue("sync_playlists").jsonPrimitive.int)

        say("10. With two sync playlists, the phone uses the one Windows uses: the lowest id.")
        val other = windows("plant", "tester", "--new", "yes", "--text", """{"nexus_music_sync":1,"pins":["other"],"pins_at":5}""").text("playlist")!!
        val lowest = peek().text("playlist")!!
        // A phone that has been exchanging with the higher one, and a new one, both go to the
        // lowest: the first's pins (the PC's, held on the phone) are written into it.
        val third = Phone("third phone")
        third.exchange()
        second.exchange()
        assertEquals(listOf(p2, p1), SharedText.read(windows("peek", "tester").text("comment")!!)!!.pins)
        assertEquals(2, peek().getValue("sync_playlists").jsonPrimitive.int)
        say("   two there ($id and $other); both use $lowest")
        runBlocking { api.deletePlaylist(if (lowest == id) other else id) }

        say("11. The queue: the PC's saved queue is taken up by a phone that isn't playing, offered to one that is; the phone's is saved under its own name.")
        val ids = songs.take(3).map { it.id }
        windows("queue-save", "tester", "--device", "NexusMusic-0badc0de", "--ids", ids.joinToString(","), "--current", ids[1], "--position", "30000")
        class Player(var playing: Boolean, var current: String?) : QueueHere {
            val replaced = ArrayList<String>()
            override fun upload() = QueueUpload(listOf(songs[4].id, songs[5].id), songs[5].id, 12_000)
            override fun currentId() = current
            override fun playing() = playing
            override fun stamp() = t - 1_000
            override suspend fun replace(songs: List<Song>, cursor: Int, positionMs: Long, stamp: Long, play: Boolean): Boolean {
                replaced += "${songs.map { it.id }} at $cursor, $positionMs ms, ${if (play) "playing" else "paused"}"
                return true
            }
        }
        fun queueOf(player: Player) = QueueStep(
            save = { device, upload -> api.savePlayQueue(device, upload.ids, upload.current, upload.positionMs) }, saved = { api.playQueue() },
            device = { "NexusMusic-feedf00d" }, may = { true }, scope = scope, pause = {}, main = Dispatchers.Unconfined,
        ).also { it.here = player }
        // Not playing: taken up, paused, where the PC had got to.
        val idle = Player(playing = false, current = null)
        val idleQueue = queueOf(idle)
        runBlocking { idleQueue.check() }
        assertEquals(listOf("$ids at 1, 30000 ms, paused"), idle.replaced)
        assertNull(idleQueue.elsewhere.value)
        // Playing something of its own: left alone, and offered.
        val busy = Player(playing = true, current = songs[5].id)
        val busyQueue = queueOf(busy)
        runBlocking { busyQueue.check() }
        assertTrue(busy.replaced.isEmpty())
        assertEquals(songs[1].title, busyQueue.elsewhere.value?.title)
        busyQueue.continueHere()
        assertEquals(listOf("$ids at 1, 30000 ms, playing"), busy.replaced)
        // The phone's own queue, saved: the PC reads it as the phone's.
        busyQueue.changedHere()
        var saved = windows("queue-get", "tester")
        // (Saved on another thread: looked for until it is there.)
        repeat(50) {
            if (saved.text("changed_by") != "NexusMusic-feedf00d") {
                Thread.sleep(100)
                saved = windows("queue-get", "tester")
            }
        }
        assertEquals("NexusMusic-feedf00d", saved.text("changed_by"))
        assertEquals(listOf(songs[4].id, songs[5].id), saved.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(songs[5].id to "12000", saved.text("current") to saved.text("position"))
        // Its own queue, read back, is neither taken up nor offered.
        runBlocking { idleQueue.check() }
        assertEquals(1, idle.replaced.size)
        assertNull(idleQueue.elsewhere.value)
        say("All of it held.")
    }
}
