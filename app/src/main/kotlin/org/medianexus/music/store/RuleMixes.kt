package org.medianexus.music.store

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.library.MixRules

/**
 * The account's mixes of its own, made to rules (Windows' `rule_mixes`, in its form: a list
 * of `Rules`), kept with the account on the phone (`rule-mixes.json`). Windows keeps them
 * with its settings and doesn't share them between devices, so neither does the phone.
 *
 * Read when the account is taken on; every change is written whole, atomically, before it is
 * shown, and one the phone won't take is not made (its caller is told, by an exception, and
 * says so). A file that can't be read is set aside (`rule-mixes.damaged.json`) rather than
 * read as "none" and written over.
 */
class RuleMixes(private val file: File, private val io: CoroutineDispatcher) {
    private val current = MutableStateFlow<List<MixRules>>(emptyList())

    /** The mixes, in the order they were made. */
    val state: StateFlow<List<MixRules>> = current.asStateFlow()

    private val lock = Mutex()
    private var opened = false

    suspend fun open() = lock.withLock {
        if (opened) return@withLock
        current.value = withContext(io) {
            if (!file.isFile) return@withContext emptyList()
            try {
                json.decodeFromString(LIST, file.readText())
            } catch (_: Exception) {
                runCatching { file.renameTo(siblingWithExtension(file, "damaged.json")) }
                emptyList()
            }
        }
        opened = true
    }

    /** A new mix, called `name`, with every rule at its starting value but its length (50
     *  songs), as Windows makes one; its id is the time it was made, in milliseconds. */
    @Throws(IOException::class)
    suspend fun create(name: String, now: Long = System.currentTimeMillis()): MixRules {
        val made = MixRules(id = now, name = name.trim(), size = 1)
        change { it + made }
        return made
    }

    /** Changes one mix's rules. */
    @Throws(IOException::class)
    suspend fun update(id: Long, change: (MixRules) -> MixRules) = change { list -> list.map { if (it.id == id) change(it).copy(id = id) else it } }

    @Throws(IOException::class)
    suspend fun delete(id: Long) = change { list -> list.filter { it.id != id } }

    private suspend fun change(change: (List<MixRules>) -> List<MixRules>) = lock.withLock {
        val next = change(current.value)
        withContext(io) { writeAtomically(file, json.encodeToString(LIST, next).toByteArray(), durable = true) }
        current.value = next
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val LIST = ListSerializer(MixRules.serializer())
    }
}
