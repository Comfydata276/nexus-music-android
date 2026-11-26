package org.medianexus.music

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.medianexus.music.store.writeAtomically

/**
 * A short record of what the player has met, for working out what went wrong somewhere
 * nobody was watching: in a car above all, where the only apps that can say why Nexus Music
 * was listed but would not open are Android Auto and this one.
 *
 * It notes which apps connected to the player and whether they were let in, what started the
 * player and whether Android let it into the foreground, which decoder plays MP3, and
 * playback errors by kind. Held in memory and in one small file, both bounded: something
 * that happens again is counted, not written again, and the oldest go when there are too
 * many.
 *
 * **Nothing private goes in.** No server address, no account, no song, album or playlist
 * name: whoever writes a line here writes only the kinds of things above (another app's
 * package name, an error's kind, a decoder's name, a time taken). It stays on the phone
 * unless the person shares it from Settings.
 */
class Diagnostics(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val most: Int = MOST,
) {
    /** Something that happened: when first and last, how many times, and what. */
    data class Entry(val first: Long, val last: Long, val count: Int, val text: String)

    private val entries = ArrayList<Entry>()
    private var loaded = false
    private var writing = false
    private var dirty = false
    private val changed = MutableStateFlow(0)

    /** Moves on whenever something is noted, for a screen that is showing it. */
    val changes: StateFlow<Int> get() = changed

    init {
        scope.launch(io) { load() }
    }

    /**
     * Notes that something happened. The same words again add to the count of the line
     * already there. `now` writes it to the phone at once rather than in a moment: for what
     * may be the last thing the app does before Android stops it.
     */
    fun note(text: String, now: Boolean = false) {
        val line = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MOST_CHARS)
        if (line.isEmpty()) return
        val at = clock()
        synchronized(entries) {
            val index = entries.indexOfFirst { it.text == line }
            if (index >= 0) {
                val before = entries.removeAt(index)
                entries += before.copy(last = at, count = before.count + 1)
            } else {
                entries += Entry(at, at, 1, line)
                while (entries.size > most) entries.removeAt(0)
            }
            dirty = true
        }
        changed.value++
        save(now)
    }

    /**
     * Notes something that only ever matters as it last was (the last attempt to connect
     * requests, say): every line beginning with `prefix` goes, and `text`, which begins with
     * it, takes their place.
     */
    fun replacing(prefix: String, text: String, now: Boolean = false) {
        synchronized(entries) {
            replaced += prefix
            if (entries.removeAll { it.text.startsWith(prefix) }) dirty = true
        }
        note(text, now)
    }

    /** What [replacing] has replaced in this run: one of those read back from the phone
     *  afterwards is older still, and is not put back. */
    private val replaced = HashSet<String>()

    /** What has been noted, the most lately seen last. */
    fun entries(): List<Entry> = synchronized(entries) { entries.toList() }

    /** The record as text to read or share: `about` first (what the app is and how it is set
     *  up; the caller's to keep free of anything private), then each thing noted. */
    fun report(about: List<String>): String = buildString {
        about.forEach { appendLine(it) }
        appendLine()
        appendLine("What the player has met (times in UTC):")
        val all = entries()
        if (all.isEmpty()) appendLine("  (nothing yet)")
        for (entry in all) {
            append("  ").append(DAY.format(Instant.ofEpochMilli(entry.last))).append("  ").append(entry.text)
            if (entry.count > 1) append("  (").append(entry.count).append(" times since ").append(DAY.format(Instant.ofEpochMilli(entry.first))).append(')')
            appendLine()
        }
    }.trimEnd()

    fun clear() {
        synchronized(entries) {
            entries.clear()
            dirty = true
        }
        changed.value++
        save(now = true)
    }

    /** What was noted before the app last stopped, put ahead of anything noted since. */
    private fun load() {
        val before = try {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size != 4) return@mapNotNull null
                Entry(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1].toLongOrNull() ?: return@mapNotNull null, parts[2].toIntOrNull() ?: return@mapNotNull null, parts[3].take(MOST_CHARS))
            }
        } catch (_: IOException) {
            emptyList()
        }
        synchronized(entries) {
            if (loaded) return
            loaded = true
            val since = entries.toList()
            entries.clear()
            entries += before.filter { entry -> replaced.none { entry.text.startsWith(it) } }.takeLast(most)
            for (entry in since) {
                val index = entries.indexOfFirst { it.text == entry.text }
                if (index >= 0) {
                    val was = entries.removeAt(index)
                    entries += was.copy(last = entry.last, count = was.count + entry.count)
                } else {
                    entries += entry
                }
            }
            while (entries.size > most) entries.removeAt(0)
        }
        changed.value++
        // Anything noted while the file was being read is written now.
        save(now = true)
    }

    private fun save(now: Boolean) {
        synchronized(entries) {
            if (writing) return
            writing = true
        }
        scope.launch(io) {
            if (!now) delay(WRITE_AFTER_MILLIS)
            while (true) {
                val lines = synchronized(entries) {
                    // Not before what was there has been read: it would be written over.
                    if (!dirty || !loaded) {
                        writing = false
                        return@launch
                    }
                    dirty = false
                    entries.joinToString("\n") { "${it.first}\t${it.last}\t${it.count}\t${it.text}" }
                }
                try {
                    writeAtomically(file, lines.toByteArray())
                } catch (_: IOException) {
                    // Still in memory; written with whatever is noted next.
                }
            }
        }
    }

    companion object {
        /** Lines kept. Each is at most [MOST_CHARS] characters, so the file stays under 64 KB. */
        const val MOST = 200
        const val MOST_CHARS = 300
        private const val WRITE_AFTER_MILLIS = 1_500L
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
