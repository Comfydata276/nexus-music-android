package org.medianexus.music.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The shared library gave up on something: its own words for why. Either what it was
 *  handed isn't what the function takes, or it stopped part-way (a Rust panic, caught at
 *  the boundary). The app carries on; whatever asked has not been done. */
class CoreException(message: String) : RuntimeException(message)

/** A playlist as the server stated it: what an answer left out is null (the core's
 *  `Stated`). */
@Serializable
data class Stated(val id: String, val name: String, val owner: String? = null, val comment: String? = null)

/** Which of the playlists the server listed holds the shared record (the core's `Found`). */
sealed interface Found {
    /** This one. */
    data class Ours(val id: String) : Found

    /** There is none. */
    data object None : Found

    /** There is one of that name, and the server did not say whose it is. */
    data object NotStated : Found
}

/**
 * The shared core: the part of the app that is the Windows app's own code, compiled into a
 * library the app carries (`libnexus_core_android.so`; the Rust side of this file is
 * `native/src/lib.rs`, and docs/DESIGN.md, "The shared core", says how it gets here).
 *
 * **This object is the only thing in the app that knows the library exists.** Everything
 * else calls the functions below, which take and give Kotlin values. Across the boundary
 * each call is one argument and one answer, both JSON as UTF-8 bytes; whatever goes wrong
 * there (an argument the library doesn't take, an answer this side can't read, a panic in
 * the library) is a [CoreException] with the reason in words.
 *
 * The library is loaded at the first call, **never on the main thread**: a call from there
 * is refused before anything is loaded. It opens no connections, keeps no files, starts no
 * threads and reads no clock: what it is given is all it knows.
 */
object Core {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val loaded: Unit by lazy { System.loadLibrary("nexus_core_android") }

    /** One call across: `given` written out, `native` run on it, its answer read. */
    private fun <In, Out> across(given: In, takes: KSerializer<In>, gives: KSerializer<Out>, native: (ByteArray) -> ByteArray?): Out {
        if (onMainThread()) throw CoreException("The shared library was called on the main thread.")
        val answer = try {
            loaded
            native(json.encodeToString(takes, given).encodeToByteArray())
        } catch (e: UnsatisfiedLinkError) {
            // (The app was built without it, or for another kind of processor: the build
            // refuses both, so this is a build that didn't come from it.)
            throw CoreException("The shared library isn't in this build of the app: ${e.message}")
        } ?: throw CoreException("The shared library gave no answer.")
        return try {
            json.decodeFromString(gives, answer.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw CoreException("The shared library's answer couldn't be read: ${e.message}")
        }
    }

    /** Whether this is the phone's main thread. (Where there is no Android, in the unit
     *  tests, there is none to be on.) */
    private fun onMainThread(): Boolean = try {
        val main = android.os.Looper.getMainLooper()
        main != null && main.thread === Thread.currentThread() && !underRobolectric
    } catch (_: RuntimeException) {
        false
    }

    /** Robolectric's copy of Android runs a test on what it calls the main thread. */
    private val underRobolectric: Boolean by lazy { android.os.Build.FINGERPRINT == "robolectric" }

    // ---- Keeping devices in step ----

    @Serializable
    private class FindIn(val listed: List<Stated>, val username: String)

    @Serializable
    private class FindOut(val kind: String, val id: String = "")

    /**
     * Which playlist holds the shared record: the one with the lowest id among those of the
     * record's name that the server says are the account's own (the core's `step::find`).
     * Asked afresh at every exchange.
     */
    fun find(listed: List<Stated>, username: String): Found {
        val out = across(FindIn(listed, username), FindIn.serializer(), FindOut.serializer(), ::find)
        return when (out.kind) {
            "ours" -> Found.Ours(out.id)
            "none" -> Found.None
            "not_stated" -> Found.NotStated
            else -> throw CoreException("The shared library's answer couldn't be read: ${out.kind}")
        }
    }

    // ---- For the tests of the boundary itself ----

    /** The text handed across and back, read as text on the other side and written again. */
    internal fun echo(text: String): String = across(text, String.serializer(), String.serializer(), ::echo)

    /**
     * Makes the library panic, with these words: for the check that a panic reaches Kotlin
     * as a [CoreException] and the app carries on. Only the tests and the debug build's
     * check (`CoreCheck`) call it.
     */
    internal fun stopForTheTest(words: String) {
        across(words, String.serializer(), kotlinx.serialization.serializer<Unit?>(), ::stop)
    }

    /** What the library was handed, as bytes of this side's choosing: for the tests of what
     *  it makes of something it doesn't take. */
    internal fun findRaw(bytes: ByteArray): ByteArray? {
        loaded
        return find(bytes)
    }

    @JvmStatic private external fun find(input: ByteArray): ByteArray?
    @JvmStatic private external fun echo(input: ByteArray): ByteArray?
    @JvmStatic private external fun stop(input: ByteArray): ByteArray?
}
