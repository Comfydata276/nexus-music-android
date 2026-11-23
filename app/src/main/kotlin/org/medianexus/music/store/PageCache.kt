package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.medianexus.music.net.ApiError

/**
 * What a screen, or the car, is shown of one page: whatever there is to show, and how the
 * check with the server went.
 */
data class Page<out T>(
    /** The page as kept on the phone, or as the server just sent it. Null until there is
     *  something to show. */
    val value: T? = null,
    /** `value` is what the server said just now. */
    val fresh: Boolean = false,
    /** The check with the server failed. Whatever was kept is still in `value`. */
    val error: ApiError? = null,
) {
    /** Still waiting to hear from the server. */
    val checking: Boolean get() = !fresh && error == null
}

/** One page that can be asked for: what it's kept under, how it's written down, and how it's
 *  fetched. `key` is what was asked for, so the same question always finds the same copy. */
class PageSpec<T>(val key: String, val serializer: KSerializer<T>, val fetch: suspend () -> T)

/**
 * Pages kept on the phone, so every page can be drawn at once from what was there last time
 * and then put right from the server ("Show what you have, then check").
 *
 * Each page is a JSON file under the account's cache folder, named after a hash of its key.
 * The pages used lately are also held in memory, already read, so going back to one costs
 * nothing. Two requests for the same page at once share one trip to the server.
 */
class PageCache(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val held: Int = 64,
) {
    private class Entry(val value: Any?, val at: Long, val fresh: Boolean)

    /** The pages in memory, least lately used first. */
    private val memory = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > held
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /** The kept copy of a page, from memory or disk; null if there isn't one (or it can no
     *  longer be read, as when the app has changed how pages are written). */
    suspend fun <T> kept(spec: PageSpec<T>): T? {
        synchronized(memory) { memory[spec.key] }?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val read = withContext(io) { readFile(spec) } ?: return null
        remember(spec.key, read, fresh = false)
        return read
    }

    /** Asks the server for the page now (or joins a request for it already on its way) and
     *  keeps the answer. Throws `ApiError` if the server can't be asked. */
    suspend fun <T> fresh(spec: PageSpec<T>): T {
        val shared = inFlight.computeIfAbsent(spec.key) {
            // On the cache's own scope: a caller that gives up (a screen closed, the car moved
            // on) doesn't cancel the request for anyone else, and the answer is kept anyway.
            scope.async(io, start = CoroutineStart.LAZY) {
                try {
                    val value = spec.fetch()
                    put(spec, value)
                    value
                } finally {
                    inFlight.remove(spec.key)
                }
            }
        }
        shared.start()
        @Suppress("UNCHECKED_CAST")
        return shared.await() as T
    }

    /** The page as the server sent it within the last `withinMillis`, without asking again. */
    private fun recent(key: String, withinMillis: Long): Entry? {
        val entry = synchronized(memory) { memory[key] } ?: return null
        return if (entry.fresh && clock() - entry.at <= withinMillis) entry else null
    }

    /**
     * The kept copy straight away (or nothing yet), then the server's. A failure while there
     * is a kept copy leaves that copy on show, with the error alongside: never a blank page.
     * A page that was fetched moments ago isn't fetched again.
     */
    fun <T> page(spec: PageSpec<T>, freshFor: Long = FRESH_FOR_MILLIS): Flow<Page<T>> = flow {
        recent(spec.key, freshFor)?.let {
            @Suppress("UNCHECKED_CAST")
            emit(Page(it.value as T, fresh = true))
            return@flow
        }
        val kept = kept(spec)
        emit(Page(kept))
        try {
            emit(Page(fresh(spec), fresh = true))
        } catch (e: ApiError) {
            emit(Page(kept, error = e))
        }
    }

    /**
     * One answer, as soon as possible: the kept copy if there is one, else the server's.
     * When a kept copy is given, it is checked with the server afterwards, and `changed` is
     * told if the server's differs. For the car, which wants one list and can be told later
     * that it has changed.
     */
    suspend fun <T> quick(spec: PageSpec<T>, changed: ((T) -> Unit)? = null): T {
        recent(spec.key, FRESH_FOR_MILLIS)?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val kept = kept(spec) ?: return fresh(spec)
        scope.launch {
            val now = try {
                fresh(spec)
            } catch (_: ApiError) {
                return@launch
            }
            if (now != kept) changed?.invoke(now)
        }
        return kept
    }

    /** Replaces the kept copy, as when a change made here (a like, say) is known to have
     *  reached the server. */
    suspend fun <T> put(spec: PageSpec<T>, value: T) {
        remember(spec.key, value, fresh = true)
        withContext(io) { writeFile(spec, value) }
    }

    /** Changes the kept copy, if there is one. */
    suspend fun <T> update(spec: PageSpec<T>, change: (T) -> T) {
        val kept = kept(spec) ?: return
        val changed = change(kept)
        if (changed != kept) put(spec, changed)
    }

    /** Forgets a kept page, so the next request for it goes to the server. */
    suspend fun forget(key: String) {
        synchronized(memory) { memory.remove(key) }
        withContext(io) { fileFor(key).delete() }
    }

    private fun remember(key: String, value: Any?, fresh: Boolean) {
        synchronized(memory) { memory[key] = Entry(value, clock(), fresh) }
    }

    internal fun fileFor(key: String): File = File(dir, "${nameFor(key)}.json")

    private fun <T> readFile(spec: PageSpec<T>): T? {
        val file = fileFor(spec.key)
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return null
        }
        return try {
            json.decodeFromString(spec.serializer, text)
        } catch (_: SerializationException) {
            file.delete()
            null
        } catch (_: IllegalArgumentException) {
            file.delete()
            null
        }
    }

    private fun <T> writeFile(spec: PageSpec<T>, value: T) {
        try {
            writeAtomically(fileFor(spec.key), json.encodeToString(spec.serializer, value).toByteArray())
        } catch (_: IOException) {
            // A page that can't be written is fetched again next time; nothing is lost.
        }
    }

    companion object {
        /** A page fetched within this long is shown as it is, without asking again. */
        const val FRESH_FOR_MILLIS = 15_000L

        /** Small files: defaults and nulls are left out, and anything unknown when reading
         *  back (written by a newer version) is ignored. */
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = false
        }

        /** A file name for a key: keys hold anything (a genre's name, a search), so they're
         *  hashed. */
        fun nameFor(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
    }
}
