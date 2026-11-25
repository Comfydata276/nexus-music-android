package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** What happened when the settings file couldn't be read (see `SettingsStore.loadChecked`). */
sealed interface SettingsDamage {
    /** The copy from the last good start was used. */
    data object UsedBackup : SettingsDamage

    /** There was no copy either, and the app started with its default settings. The damaged
     *  file was put aside under `keptAs`; null if even that failed, so it wasn't kept. */
    data class StartedAfresh(val keptAs: String?) : SettingsDamage
}

/**
 * The settings: read once when the app starts, then held in memory and written back, off the
 * main thread, whenever they change.
 */
class SettingsStore private constructor(
    private val file: File,
    loaded: Settings,
    /** Set when the file was damaged at start-up, so the app can say so. */
    val damage: SettingsDamage?,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
) {
    private val current = MutableStateFlow(loaded)
    val state: StateFlow<Settings> = current.asStateFlow()
    val value: Settings get() = current.value

    // Each change gets a number; the writer writes whatever is newest and notes its number:
    // as written if the file was saved, as failed if not. `tries` counts the writes made.
    private val changes = AtomicLong(0)
    private data class Outcome(val written: Long = 0, val failed: Long = -1, val tries: Long = 0)
    private val outcome = MutableStateFlow(Outcome())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val failing = MutableStateFlow(false)

    /** The file's writes, one at a time: the writer's, and a checked write's ([commit]). */
    private val writing = kotlinx.coroutines.sync.Mutex()

    /** Whether the last write failed. A failed write leaves the last good file in place (as
     *  on Windows) and is tried again at the next change; the app tells the person. */
    val saveFailed: StateFlow<Boolean> = failing.asStateFlow()

    init {
        scope.launch(io) {
            for (signal in wake) {
                // The number first, then the settings: what's written is never older than
                // the number it's noted under.
                val number = changes.get()
                // (Not while a checked write is being made: an older copy must not be put
                // over it.)
                val saved = writing.withLock { save(file, current.value) }
                val before = outcome.value
                outcome.value = if (saved) Outcome(written = number, tries = before.tries + 1) else before.copy(failed = number, tries = before.tries + 1)
                failing.value = !saved
            }
        }
    }

    /** Changes the settings. The change shows at once; the file is written soon after, on
     *  another thread, and a burst of changes is written once. */
    fun update(change: (Settings) -> Settings) {
        var changed = false
        while (true) {
            val before = current.value
            val after = change(before)
            if (current.compareAndSet(before, after)) {
                changed = after != before
                break
            }
        }
        if (changed) {
            changes.incrementAndGet()
            wake.trySend(Unit)
        }
    }

    /**
     * Changes the settings **and has them on the disk before the change shows**: for a
     * change that must not be lost once the person has seen it made (one that waits to be
     * shared with their other devices), and for what an exchange with the server took in.
     * False if the file couldn't be written: then nothing has changed, here or on the disk.
     * Off the main thread.
     */
    suspend fun commit(change: (Settings) -> Settings): Boolean = writing.withLock {
        while (true) {
            val before = current.value
            val after = change(before)
            if (after == before) return@withLock true
            // (Surely: something is about to be done on the strength of it, so the folder
            // is synced too, as for the other writes the app relies on.)
            if (!save(file, after, sure = true)) {
                failing.value = true
                return@withLock false
            }
            // (Something else changed meanwhile, by `update`: made again on top of that.)
            if (current.compareAndSet(before, after)) break
        }
        // (Whatever `update` changed while this was writing is still to be written.)
        changes.incrementAndGet()
        wake.trySend(Unit)
        true
    }

    /**
     * Waits until every change so far has been written, or the writing failed. True if they
     * are on disk.
     *
     * If the last write failed and nothing has changed since, this tries it again rather
     * than report the old failure: asking for the same thing a second time (signing in again
     * with the same address and name) makes no change, so nothing else would ever tell the
     * writer to have another go, and the failure would be reported for ever.
     */
    suspend fun flush(): Boolean {
        val wanted = changes.get()
        val before = outcome.value
        if (before.written >= wanted) return true
        if (before.failed >= wanted) wake.trySend(Unit)
        return outcome.first { it.written >= wanted || (it.failed >= wanted && it.tries > before.tries) }.written >= wanted
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        internal val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            // The field names the Windows app's file uses.
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

        fun open(file: File, scope: CoroutineScope, io: CoroutineDispatcher): SettingsStore {
            val (settings, damage) = loadChecked(file)
            return SettingsStore(file, settings, damage, scope, io)
        }

        /** Where the copy of the settings from the last good start is kept. */
        fun backupOf(file: File): File = siblingWithExtension(file, "backup.json")

        internal fun read(file: File): Settings? = decode(bytesOf(file))

        private fun bytesOf(file: File): ByteArray? = try {
            file.readBytes()
        } catch (_: IOException) {
            null
        }

        private fun decode(bytes: ByteArray?): Settings? = try {
            bytes?.let { json.decodeFromString(Settings.serializer(), it.decodeToString()).repaired() }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Writes the bytes whole or not at all; false if they couldn't be. */
        private fun tryWrite(file: File, bytes: ByteArray, sure: Boolean = false): Boolean = try {
            writeAtomically(file, bytes, durable = sure)
            true
        } catch (_: IOException) {
            false
        }

        /**
         * Reads the settings when the app starts. A file that can't be read (cut short when
         * the phone died, edited by hand into something else) is never quietly replaced by a
         * fresh one: the copy kept from the last good start is used, and failing that the
         * damaged file is put aside under another name. The second value says when either
         * happened. Copies are written beside their place and then moved into it, so one cut
         * short part-way never spoils the copy it was replacing.
         */
        fun loadChecked(file: File): Pair<Settings, SettingsDamage?> {
            val backup = backupOf(file)
            if (!file.exists()) {
                // A first run, or the file was deleted to start afresh.
                return Settings() to null
            }
            val bytes = bytesOf(file)
            decode(bytes)?.let { settings ->
                // Good: this is the copy to fall back on next time. If it can't be written,
                // the copy from an earlier start stays.
                tryWrite(backup, bytes!!)
                return settings to null
            }
            val backupBytes = bytesOf(backup)
            decode(backupBytes)?.let { settings ->
                // Mended from the copy. If even that fails, the next change writes it anyway.
                tryWrite(file, backupBytes!!)
                return settings to SettingsDamage.UsedBackup
            }
            val aside = siblingWithExtension(file, "damaged.json")
            val kept = try {
                Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                true
            } catch (_: IOException) {
                // Can't be moved: a copy of it will do, if it could be read.
                bytes != null && tryWrite(aside, bytes)
            }
            return Settings() to SettingsDamage.StartedAfresh(if (kept) aside.name else null)
        }

        /** Writes the settings; false if they couldn't be. As on Windows, a failed write
         *  leaves the last good file in place. */
        fun save(file: File, settings: Settings, sure: Boolean = false): Boolean =
            tryWrite(file, json.encodeToString(Settings.serializer(), settings).toByteArray(), sure)
    }
}
