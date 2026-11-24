package org.medianexus.music.jam

import android.content.Context
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.library.LibrarySnapshot
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Steered

/**
 * [Jams] made for the app: its words, its toasts, its settings, the phone's own player, the
 * library copy that tells a jam's songs this phone has from those it doesn't, and the
 * notification that keeps the relay's connection while the phone is in a jam (see
 * [JamService]). Made the first time anything asks for jams (the jam page, Connect, a link
 * shared to the app, or linking switched on at start), never otherwise: with the feature
 * unused nothing of it exists.
 */
class JamApp(private val app: Context, private val graph: Graph, steered: Steered) : JamPlace {
    override val words = JamWords(app.resources)

    val jams = Jams(this, steered, graph.http, graph.scope, Dispatchers.Main.immediate, Dispatchers.IO)

    /** The phone's own player and library, for a session hosted here. */
    private val own by lazy { OwnPlayer(app, graph) }

    /** Starts following what jams depend on; called once it is made. */
    fun start() {
        JamCovers.http = graph.http
        jams.playingChanged(graph.playing)
        // The app's screens in front: a reason to be among the person's devices (rule 1).
        graph.scope.launch(Dispatchers.Main.immediate) { graph.front.collect { jams.front(it) } }
        // Another account taken on (or none): the room is another's, the key another's.
        graph.scope.launch(Dispatchers.Main.immediate) {
            var before: Any? = graph.accounts.state.value
            graph.accounts.state.collect { account ->
                if (account === before) return@collect
                before = account
                jams.accountChanged()
            }
        }
        // The settings linking depends on.
        graph.scope.launch(Dispatchers.Main.immediate) {
            graph.settings.state.map { Triple(it.jamHost, it.jamRelay, it.linkDevices) to it.deviceName }.distinctUntilChanged().collect { jams.linkDevices() }
        }
    }

    override fun hostingSetUp(): Boolean = graph.accounts.state.value != null && canHost()

    override fun linkingOn(): Boolean = hostingSetUp() && graph.settings.value.linkDevices

    override suspend fun relay(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val settings = graph.settings.value
        val account = graph.accounts.state.value?.account ?: return@withContext null
        if (settings.jamRelay.isEmpty()) return@withContext null
        graph.relayKey.load(account.server, account.username)?.let { settings.jamRelay to it }
    }

    override suspend fun accountWords(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val account = graph.accounts.state.value?.account ?: return@withContext null
        val password = org.medianexus.music.store.PasswordVault(Graph.passwordFile(app), org.medianexus.music.store.KeystoreBox()).load(account.server, account.username) ?: return@withContext null
        RoomSide.words(account.username, password)
    }

    override fun device(): Pair<String, String> {
        var settings = graph.settings.value
        if (settings.deviceId.isEmpty()) {
            val made = org.medianexus.music.sync.QueueShare.newDeviceId()
            graph.settings.update { if (it.deviceId.isEmpty()) it.copy(deviceId = made) else it }
            settings = graph.settings.value
        }
        val chosen = settings.deviceName.trim()
        val name = chosen.ifEmpty {
            runCatching { android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()?.trim()?.ifEmpty { null }
                ?: android.os.Build.MODEL.orEmpty().ifEmpty { "This phone" }
        }
        return settings.deviceId to shownText(name, 24)
    }

    override fun guestsSteer(): Boolean = graph.settings.value.jamGuestsControl

    override val answerWords: AnswerWords = object : AnswerWords {
        override val ownOnly = app.getString(R.string.jam_ans_own_only)
        override val noneOfThose = app.getString(R.string.jam_ans_no_songs)
        override val hostChooses = app.getString(R.string.jam_ans_host_chooses)
        override val notForGuests = app.getString(R.string.jam_ans_not_guest)
        override val nothingPlaying = app.getString(R.string.jam_ans_nothing_playing)
        override val notInLibrary = app.getString(R.string.jam_ans_not_in_library)
        override val already = app.getString(R.string.jam_ans_already)
        override val ownSongs = app.getString(R.string.jam_ans_own_songs)
        override val gone = app.getString(R.string.jam_ans_gone)
    }

    override fun host(): HostPlace? {
        if (graph.accounts.state.value == null) return null
        return hostPlace
    }

    private val hostPlace = object : HostPlace {
        override val player: HostPlayer get() = own
        override val library: HostLibrary get() = own

        override fun hold(on: Boolean) = if (on) own.connection.connect() else own.connection.release()

        override suspend fun load(songs: List<Song>, index: Int, positionMs: Long, play: Boolean, context: String, place: Long): String? =
            when (own.load(songs, index, positionMs, play, context, place)) {
                null -> null
                OwnPlayer.Refusal.IN_CALL -> app.getString(R.string.jam_ans_in_call)
                OwnPlayer.Refusal.SOUND_TAKEN -> app.getString(R.string.jam_ans_sound_taken)
                OwnPlayer.Refusal.OFFLINE -> app.getString(R.string.jam_ans_offline)
                OwnPlayer.Refusal.NOT_READY -> app.getString(R.string.jam_didnt_work)
            }

        override fun takePlace(): Long = own.take()

        override val changes = kotlinx.coroutines.flow.combine(
            own.connection.song, own.connection.playing, own.connection.queue, own.connection.shuffle, own.connection.repeat,
        ) { song, playing, queue, shuffle, repeat -> listOf(song?.id, playing, queue, shuffle, repeat) }

        override suspend fun cover(id: String, large: Boolean): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
            val covers = graph.accounts.state.value?.covers ?: return@withContext null
            val file = covers.file(id) ?: return@withContext null
            GuestCovers.cut(file.readBytes(), large)
        }

        override suspend fun fetchSongs(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
            val api = graph.accounts.state.value?.api ?: return@withContext emptyList()
            ids.take(MOST_ADDED).mapNotNull { id -> runCatching { api.song(id) }.getOrNull()?.takeIf { it.id == id } }
        }
    }

    override fun toast(text: String) = graph.toasts.show(text)

    override fun pauseOwn() = graph.player.pauseOwn()

    override fun jamName(): String {
        val settings = graph.settings.value
        return settings.jamName.trim().ifEmpty { settings.username }
    }

    override fun setJamName(typed: String) {
        val name = typed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(24)
        if (name.isNotEmpty() && name != jamName()) graph.settings.update { it.copy(jamName = name) }
    }

    override fun canHost(): Boolean {
        val settings = graph.settings.value
        return settings.jamHost && settings.jamRelay.isNotEmpty() && graph.relayKeyKept()
    }

    override fun rowOf(song: JamSong, relay: String, jam: String): Song {
        val known = song.id.takeIf { it.isNotEmpty() }?.let(::known)
        return known ?: Song(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            coverArt = JamCovers.mark(relay, jam, song.cover),
            duration = song.duration,
            queued = song.queued,
        )
    }

    /** A song this phone's library has, by id: looked up in the copy of the library. */
    private fun known(id: String): Song? {
        val copy = graph.accounts.state.value?.copy?.state?.value ?: return null
        val at = places(copy)[id] ?: return null
        return copy.songs.song(at)
    }

    private val placesOf = WeakHashMap<LibrarySnapshot, Map<String, Int>>()

    /** Each song's place in a copy of the library, by id: made once for each copy. */
    @Synchronized
    private fun places(copy: LibrarySnapshot): Map<String, Int> = placesOf.getOrPut(copy) {
        HashMap<String, Int>(copy.songs.size * 2).apply { for (i in 0 until copy.songs.size) put(copy.songs.id(i), i) }
    }

    private var noted: Triple<String?, String, String>? = null

    override fun reasonsChanged() = shown(jams.ui.value)

    override fun shown(ui: JamUi) {
        val res = app.resources
        val note: Triple<String?, String, String> = when {
            ui.linked != null -> Triple(res.getString(R.string.jam_note_linked, ui.linked), res.getString(R.string.jam_note_detail), res.getString(R.string.jam_note_stop))
            ui.joined != null -> Triple(res.getString(R.string.jam_note_joined, ui.joined.host), res.getString(R.string.jam_note_detail), res.getString(R.string.jam_note_leave))
            ui.hosting != null -> Triple(res.getString(R.string.jam_note_hosting), res.getString(R.string.jam_note_hosting_detail), res.getString(R.string.jam_end))
            else -> Triple(null, "", "")
        }
        if (note == noted) return
        noted = note
        JamService.update(app, note.first, note.second, note.third)
    }
}
