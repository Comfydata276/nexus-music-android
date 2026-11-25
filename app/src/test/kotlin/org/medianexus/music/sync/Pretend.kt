package org.medianexus.music.sync

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Song
import org.medianexus.music.net.StatedPlaylist
import org.medianexus.music.net.Timed
import org.medianexus.music.store.Added
import org.medianexus.music.store.PlayLog
import org.medianexus.music.store.RecentlyPlayed
import org.medianexus.music.store.SettingsStore

/** The time the pretend server's clock shows, and the tests' phones' unless they say otherwise. */
internal const val PRETEND_NOW = 1_795_000_000L

    /** A pretend server for the exchange: its playlists, and everything asked of it. */
internal class PretendServer(val owner: String = "tester") : StepServer {
        val lists = LinkedHashMap<String, StatedPlaylist>()
        var reads = 0
        var listed = 0
        val writes = ArrayList<Pair<String, String>>()
        val made = ArrayList<String>()
        private var next = 100

        /** Held before each call goes through, by the tests that interleave two devices. */
        var gate: suspend (what: String) -> Unit = {}

        /** A write that reaches the server and gets no answer. */
        var loseAnswers = 0

        /** The server's own clock, as its answers give it (null: it gives none). */
        var time: Long? = PRETEND_NOW

        fun put(id: String, comment: String?, name: String = PLAYLIST_NAME, by: String? = owner) {
            lists[id] = StatedPlaylist(id = id, name = name, comment = comment, owner = by)
        }

        fun comment(id: String) = lists.getValue(id).comment

        override suspend fun playlists(): Timed<List<StatedPlaylist>> {
            gate("list")
            listed++
            return Timed(lists.values.toList(), time)
        }

        override suspend fun playlist(id: String): Timed<StatedPlaylist> {
            gate("read")
            reads++
            return Timed(lists[id] ?: throw ApiError.Server(70, "not found"), time)
        }

        override suspend fun create(name: String): String {
            gate("make")
            val id = "new${next++}"
            made += id
            put(id, "", name)
            return id
        }

        override suspend fun setComment(id: String, comment: String) {
            gate("write")
            if (lists[id]?.owner?.lowercase() != owner.lowercase()) throw ApiError.Server(50, "not yours")
            lists[id] = lists.getValue(id).copy(comment = comment)
            writes += id to comment
            if (loseAnswers > 0) {
                loseAnswers--
                throw ApiError.Network("The connection dropped.")
            }
        }

        fun asked() = reads + listed + writes.size + made.size
    }


/**
 * A phone, for the tests that need its real stores: the settings and an account's data in
 * `root`, as the app keeps them. Made again over the same folder, it is the phone after its
 * process was killed and started again: whatever reached the disk, and nothing else.
 */
internal class PretendPhone(val root: File, val account: String, private val scope: CoroutineScope, var read: Boolean = true, version: String = "9.9.9") {
    private val io = Dispatchers.Unconfined
    val settingsFile = File(root, "settings.json")
    val settings = SettingsStore.open(settingsFile, scope, io)
    val data = File(root, "accounts/$account").apply { mkdirs() }
    val added = Added(File(data, "playlist-added.json"), null, io)
    val history = RecentlyPlayed(File(data, "history.json"), scope, io) { PRETEND_NOW }
    val log = PlayLog(File(data, "plays.log"), io)
    var playlists: Set<String>? = null

    /** Where the record as first found is kept before this phone's first write. */
    val keptFirst = File(data, "shared-record-kept-$version.json")
    val notes = ArrayList<String>()
    val shared = PhoneShared(
        settings, account, "tester", added, history, log, read = { read }, playlists = { playlists },
        songs = { ids -> ids.associateWith { Song(id = it, title = "Song $it") } }, keptFirst = keptFirst, note = { notes += it },
    )

    init {
        Belongs.takeOn(settings, account, { File(root, "accounts/$it").apply { mkdirs() } })
        runBlocking {
            history.open()
            log.open()
        }
    }

    /** Its exchange with `server`, as this phone's account. */
    fun step(server: StepServer, may: () -> Boolean = { true }, clock: () -> Long = { PRETEND_NOW }, notes: MutableList<String> = this.notes, on: CoroutineScope = scope, standing: () -> Long = { 0 }) =
        InStep(server, shared, username = "tester", account = "$account-${System.nanoTime()}", may = may, scope = on, clock = clock, pause = {}, note = { notes += it }, standing = standing)

    /** The person does something on this phone to what is shared: kept, on the disk, before
     *  it shows (as `Graph.choose` does it). */
    fun does(did: Chosen.Did, now: Long = PRETEND_NOW, under: String = account): Boolean = runBlocking { settings.commit { Chosen.made(it, did, under, now) { said -> notes += said } } }

    fun pin(id: String) = does(Chosen.Did.Pin(id, true))
    fun unpin(id: String) = does(Chosen.Did.Pin(id, false))

    val pins: List<String> get() = settings.value.pinnedPlaylists

    /** The settings as they are on the disk at this moment, as text. */
    fun onDisk(): String = settingsFile.readText()

    /** The process ends: the log lets its file go (what it had been given is on the disk). */
    fun stop() = runBlocking {
        settings.flush()
        log.close().join()
    }
}

/** A pretend player for the tests of the shared queue: its queue, when that last changed,
 *  and every queue put in its place. */
internal class PretendPlayer(var playing: Boolean = false, var current: String? = "mine", var stamp: Long = 0, var taken: String? = null) : QueueHere {
    var songs: List<String>? = listOf("mine", "next")
    var positionSeconds = 5.0
    val replaced = ArrayList<String>()

    /** What `replace` answers: whether the player took the queue. */
    var takes = true

    /** The place in the order of choices each queue came with (null: taken up unasked). */
    val choices = ArrayList<Long?>()

    /** Something that happens while a queue is being made ready. */
    var meanwhile: () -> Unit = {}

    override fun queue() = songs?.let { ids -> QueueNow(ids.map { org.medianexus.music.core.QueueSong(it, 200) }, ids.indexOf(current).coerceAtLeast(0), positionSeconds) }
    override fun currentId() = current
    override fun playing() = playing
    override fun stamp() = stamp
    override fun taken() = taken
    override suspend fun replace(songs: List<Song>, cursor: Int, positionMs: Long, stamp: Long, taken: String, choice: Long?, asOf: Long, stampThen: Long, still: () -> Boolean): Boolean {
        meanwhile()
        // (As the playback service: not if the person has done anything to the queue since
        // it was decided to take this one up.)
        if (choice == null && (this.stamp != stampThen || !org.medianexus.music.playback.Choices.stands(asOf))) return false
        if (!takes || !still()) return false
        choices += choice
        replaced += "${songs.map { it.id }} at $cursor, $positionMs ms, ${if (choice != null) "playing" else "paused"}"
        this.songs = songs.map { it.id }
        current = songs[cursor].id
        positionSeconds = positionMs / 1000.0
        this.stamp = stamp
        this.taken = taken.takeIf { choice == null }
        return true
    }
}
