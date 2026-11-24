package org.medianexus.music.jam

import android.content.Context
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
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

    init {
        JamCovers.http = graph.http
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
