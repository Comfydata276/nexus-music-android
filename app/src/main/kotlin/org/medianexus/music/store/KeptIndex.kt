package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Song

/**
 * Which songs are kept whole among the kept songs, by kept-song key, with what is known of
 * each song: so that whether a song can play with no server is a question for memory, not
 * the disk (Windows keeps the same in memory, `SongCache.holds`), and so that the car can
 * show, and search, what plays offline.
 *
 * It follows the kept songs: told when a song is kept whole and when one goes (cleared away
 * for the limit, or found not whole), and put right against the cache itself each time that
 * is opened ([reconcile]). Written to a small file beside the kept songs (never inside their
 * folder, which Media3's cache keeps to itself), so it is known before the cache has been
 * opened this time; and since the file lives in the same place as the songs, a system that
 * clears one clears the other.
 */
class KeptIndex(private val file: File, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    @Serializable
    private data class Entry(val key: String, val song: Song)

    private val whole = ConcurrentHashMap<String, Song>()
    private val version = MutableStateFlow(0)
    private var saving: Job? = null
    @Volatile private var loaded = false
    private val loadLock = Any()

    /** Goes up whenever what is kept whole changes. */
    val changes: StateFlow<Int> = version.asStateFlow()

    private fun load() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            try {
                if (file.isFile) json.decodeFromString(LIST, file.readText()).forEach { whole[it.key] = it.song }
            } catch (_: IOException) {
            } catch (_: IllegalArgumentException) {
            }
            loaded = true
        }
    }

    fun isWhole(key: String): Boolean {
        load()
        return whole.containsKey(key)
    }

    /** Every song kept whole, as far as is known of each. */
    fun songs(): List<Song> {
        load()
        return whole.values.distinctBy { it.id }
    }

    /** The song under `key` is kept whole now. */
    fun kept(key: String, song: Song) {
        load()
        val before = whole.put(key, song.copy(queued = false))
        if (before == null || before != song) changed()
    }

    /** The song under `key` is no longer kept whole. */
    fun gone(key: String) {
        load()
        if (whole.remove(key) != null) changed()
    }

    /** The cache has been opened: `keys` are the ones kept whole in it now, and `songFor`
     *  names a key's song where nothing better is known (its id). */
    fun reconcile(keys: Set<String>, songFor: (String) -> Song) {
        load()
        var moved = whole.keys.retainAll(keys)
        for (key in keys) if (whole.putIfAbsent(key, songFor(key)) == null) moved = true
        if (moved) changed()
    }

    private fun changed() {
        version.value++
        // Written a moment later, once: a song kept and the next dropped are one write.
        if (saving?.isActive == true) return
        saving = scope.launch(io) {
            delay(SAVE_AFTER_MILLIS)
            val now = whole.entries.map { Entry(it.key, it.value) }
            try {
                writeAtomically(file, json.encodeToString(LIST, now).toByteArray())
            } catch (_: IOException) {
                // Rebuilt from the cache itself the next time it is opened.
            }
        }
    }

    private companion object {
        const val SAVE_AFTER_MILLIS = 2_000L
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val LIST = ListSerializer(Entry.serializer())
    }
}
