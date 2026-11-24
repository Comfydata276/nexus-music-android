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
internal class PretendPhone(val root: File, val account: String, private val scope: CoroutineScope, var read: Boolean = true) {
    private val io = Dispatchers.Unconfined
    val settings = SettingsStore.open(File(root, "settings.json"), scope, io)
    private val data = File(root, "accounts/$account").apply { mkdirs() }
    val added = Added(File(data, "playlist-added.json"), null, io)
    val history = RecentlyPlayed(File(data, "history.json"), scope, io) { PRETEND_NOW }
    val log = PlayLog(File(data, "plays.log"), io)
    var playlists: Set<String>? = null
    val shared = PhoneShared(settings, account, added, history, log, read = { read }, playlists = { playlists }, songs = { ids -> ids.associateWith { Song(id = it, title = "Song $it") } })

    init {
        Belongs.takeOn(settings, account, { File(root, "accounts/$it").apply { mkdirs() } })
        runBlocking {
            history.open()
            log.open()
        }
    }

    /** Its exchange with `server`, as this phone's account. */
    fun step(server: StepServer, may: () -> Boolean = { true }, clock: () -> Long = { PRETEND_NOW }, notes: MutableList<String> = ArrayList()) =
        InStep(server, shared, username = "tester", account = "$account-${System.nanoTime()}", may = may, scope = scope, clock = clock, pause = {}, note = { notes += it })

    /** The person does something to the settings on this phone. */
    fun does(change: (org.medianexus.music.store.Settings) -> org.medianexus.music.store.Settings) = settings.update(change)

    /** The process ends: the log lets its file go (what it had been given is on the disk). */
    fun stop() = runBlocking {
        settings.flush()
        log.close().join()
    }
}

/** A pretend player for the tests of the shared queue: what it would save, when its queue
 *  last changed, and every queue put in its place. */
internal class PretendPlayer(var playing: Boolean = false, var current: String? = "mine", var stamp: Long = 0) : QueueHere {
    var queue: QueueUpload? = QueueUpload(listOf("mine", "next"), "mine", 5_000)
    val replaced = ArrayList<String>()

    /** What `replace` answers: whether the player took the queue. */
    var takes = true

    /** The place in the order of choices each queue came with (null: taken up unasked). */
    val choices = ArrayList<Long?>()

    /** Something that happens while a queue is being made ready. */
    var meanwhile: () -> Unit = {}

    override fun upload() = queue
    override fun currentId() = current
    override fun playing() = playing
    override fun stamp() = stamp
    override suspend fun replace(songs: List<Song>, cursor: Int, positionMs: Long, stamp: Long, choice: Long?, still: () -> Boolean): Boolean {
        meanwhile()
        if (!takes || !still()) return false
        choices += choice
        replaced += "${songs.map { it.id }} at $cursor, $positionMs ms, ${if (choice != null) "playing" else "paused"}"
        queue = QueueUpload(songs.map { it.id }, songs[cursor].id, positionMs)
        this.stamp = stamp
        return true
    }
}
