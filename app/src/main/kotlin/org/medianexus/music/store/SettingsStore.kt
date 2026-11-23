package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** What happened when the settings file couldn't be read (see `SettingsStore.loadChecked`). */
sealed interface SettingsDamage {
    /** The copy from the last good start was used. */
    data object UsedBackup : SettingsDamage

    /** There was no copy either: the damaged file was put aside under this name and the app
     *  started with its default settings. */
    data class StartedAfresh(val keptAs: String) : SettingsDamage
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

    // Each change gets a number; the writer writes whatever is newest and notes its number.
    private val changes = AtomicLong(0)
    private val written = MutableStateFlow(0L)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(io) {
            for (signal in wake) {
                val number = changes.get()
                save(file, current.value)
                written.value = number
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

    /** Waits until every change so far is on disk. */
    suspend fun flush() {
        val wanted = changes.get()
        written.first { it >= wanted }
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

        internal fun read(file: File): Settings? = try {
            json.decodeFromString(Settings.serializer(), file.readText())
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /**
         * Reads the settings when the app starts. A file that can't be read (cut short when
         * the phone died, edited by hand into something else) is never quietly replaced by a
         * fresh one: the copy kept from the last good start is used, and failing that the
         * damaged file is put aside under another name. The second value says when either
         * happened.
         */
        fun loadChecked(file: File): Pair<Settings, SettingsDamage?> {
            val backup = backupOf(file)
            if (!file.exists()) {
                // A first run, or the file was deleted to start afresh.
                return Settings() to null
            }
            read(file)?.let { settings ->
                // Good: this is the copy to fall back on next time.
                runCatching { file.copyTo(backup, overwrite = true) }
                return settings to null
            }
            read(backup)?.let { settings ->
                runCatching { backup.copyTo(file, overwrite = true) }
                return settings to SettingsDamage.UsedBackup
            }
            val aside = siblingWithExtension(file, "damaged.json")
            aside.delete()
            file.renameTo(aside)
            return Settings() to SettingsDamage.StartedAfresh(aside.name)
        }

        fun save(file: File, settings: Settings) {
            try {
                writeAtomically(file, json.encodeToString(Settings.serializer(), settings).toByteArray())
            } catch (_: IOException) {
                // As on Windows: a failed write leaves the last good file in place, and the
                // next change tries again.
            }
        }
    }
}
