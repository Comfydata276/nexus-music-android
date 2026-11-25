package org.medianexus.music.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.BuildConfig
import org.medianexus.music.graph

/**
 * Hands cover art to other apps: the car and the system's media controls can't fetch
 * pictures from the server, they can only open content addresses. Each address names one
 * cover of one account (`content://<app>.covers/<the account's artwork id>/<cover id>`); the
 * picture comes from that account's cover store, fetched first if it isn't kept yet, within
 * limits: see `openFile`.
 *
 * The account is in the address because a cover id is only its server's to give: two
 * servers can use the same id for two pictures, and a car keeps the addresses it was sent.
 * An address made for an account that is not the one signed in is "no such cover", whether
 * or not the phone has a picture by that id, and nothing is fetched for it. The account is
 * named by an identifier made at random for its covers alone (`artworkIdOf`), never by its
 * storage key, which is a hash of the server and username and the same on every phone.
 *
 * It gives away nothing but covers: it is read-only, answers no queries, and an address
 * names a cover id, never a file, so nothing else on the phone can be reached through it
 * (an id that isn't one the server would give is refused before any file is looked at).
 *
 * And it gives covers only to those the player itself lets connect (`Callers`): this app,
 * callers Android trusts with media (the system's own media controls, a car's screens,
 * anything allowed to read notifications) and the known hosts of media apps, by package
 * name and signing certificate. A person's album covers are as much theirs as the list of
 * their albums; any other app on the phone is refused.
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read-only")
        val context = context ?: throw FileNotFoundException("Not ready")
        // Who is asking, before what for: a caller that isn't let in learns nothing here,
        // not even which ids are covers.
        val asker = admit(context)
        // What became of it is noted when it is known: "given" used to be written the moment
        // the asker was let in, before the address had been looked at or any file opened.
        try {
            return find(context, uri).also { asker?.noted("given") }
        } catch (e: FileNotFoundException) {
            asker?.noted("not given (${e.message})")
            throw FileNotFoundException("No such cover")
        }
    }

    /** The cover an address names, opened; or `FileNotFoundException` saying, in one of a
     *  few fixed phrases, why not. */
    private fun find(context: android.content.Context, uri: Uri): ParcelFileDescriptor {
        val (key, id) = partsOf(uri) ?: throw FileNotFoundException(NO_SUCH)
        if (CoverStore.fileName(id) == null || !isKey(key)) throw FileNotFoundException(NO_SUCH)
        val accounts = try {
            context.graph.accounts
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
        val known = accounts?.state?.value
        if (known != null) {
            // Someone is signed in, and it is not who this address was made for: no such
            // cover. At once, with nothing fetched and nothing waited for.
            if (known.artwork != key) throw FileNotFoundException("it is another account's")
            // A cover already on the phone, for the account signed in: no waiting at all.
            // Handed over only if that account is still the one signed in, as below.
            val file = known.covers.kept(id)
            if (file != null && accounts.state.value === known) return open(file)
        }
        // Anything else waits: for the app to start, for the saved sign-in, for the server.
        // This is one of the handful of threads the system gives the app for every call from
        // outside it, the car's browsing and the player's controls among them, so only a few
        // may wait here at once and none for long. A cover that isn't had in time is fetched
        // all the same (see CoverStore.forOutside) and is there the next time it's asked for.
        if (!waiting.tryAcquire()) throw FileNotFoundException("too many being fetched at once")
        val file = try {
            runBlocking {
                withTimeoutOrNull(WAIT_MILLIS) {
                    val graph = graphOf(context)
                    // The cover is the account's that was signed in when it was asked for, and
                    // is handed over only if that account is still the one signed in: a wait
                    // of seconds is long enough for someone else to have signed in, and their
                    // car must not be shown the last person's album.
                    forWhoeverIsSignedIn(graph.accounts::restored, { graph.accounts.state.value }) { account ->
                        // (Asked of the account the address names, or of nobody.)
                        if (account.artwork != key) null else account.covers.kept(id) ?: account.covers.forOutside(id, MISS_WAIT_MILLIS)
                    }
                }
            }
        } finally {
            waiting.release()
        } ?: throw FileNotFoundException(NO_SUCH)
        return open(file)
    }

    private fun open(file: java.io.File): ParcelFileDescriptor = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (_: FileNotFoundException) {
        // (Cleared away between being found and being opened.)
        throw FileNotFoundException(NO_SUCH)
    }

    /** Someone who has been let in, and where to note what became of what they asked for. */
    private class Asker(private val name: String?, private val standing: org.medianexus.music.playback.Callers.Standing, private val note: (String) -> Unit) {
        fun noted(outcome: String) = note(
            "covers asked for by ${name ?: "an app that gave no name"}: $outcome. " +
                "Android trusts it: ${yes(standing.trusted)}; known host: ${yes(standing.knownHost)}",
        )

        private fun yes(on: Boolean) = if (on) "yes" else "no"
    }

    /**
     * Lets the caller through, or throws: only this app and callers the player would let
     * connect may read covers. Who is calling is the system's word (the uid, and a package
     * name it has checked against it), never anything in the request. Null for this app
     * itself, whose reads are not noted.
     */
    private fun admit(context: android.content.Context): Asker? {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return null
        val name = runCatching { callingPackage }.getOrNull()
        val graph = try {
            context.graph
        } catch (_: UninitializedPropertyAccessException) {
            // Asked before the app has started: not yet. (The caller asks again; a car does.)
            throw FileNotFoundException("Not ready")
        }
        val standing = graph.callers.standing(name, android.os.Binder.getCallingPid(), uid)
        val asker = Asker(name, standing) { graph.diagnostics.note(it) }
        if (!standing.admitted) {
            asker.noted("refused")
            for (line in graph.callers.signedBy(name, uid)) graph.diagnostics.note(line)
            throw SecurityException("Covers are for the player's own callers")
        }
        return asker
    }

    /** The app's objects. A provider can be asked for something while the app is still
     *  starting, before they're made: wait for them. */
    private suspend fun graphOf(context: android.content.Context): org.medianexus.music.Graph {
        while (true) {
            try {
                return context.graph
            } catch (_: UninitializedPropertyAccessException) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    override fun getType(uri: Uri): String? = if (idOf(uri) != null) "image/*" else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Covers are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Covers are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Covers are read-only")

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.covers"

        /** Why a cover was not given, when there is nothing more to say than that. */
        private const val NO_SUCH = "no such cover"

        /** The longest a caller is kept, start-up and all; and how long of that a cover not
         *  yet on the phone is waited for. A car's screen gives up on a picture in a few
         *  seconds anyway. */
        private const val WAIT_MILLIS = 6_000L
        private const val MISS_WAIT_MILLIS = 4_000L

        /** Callers that may be waiting at once. The system gives an app about sixteen
         *  threads for calls from outside: this leaves most of them for everything else. */
        private const val MOST_WAITING = 6
        private val waiting = java.util.concurrent.Semaphore(MOST_WAITING)

        /**
         * Something of the account that is signed in (`account`), fetched with `fetch`, and
         * given only if that account is still the one signed in (`current`) when the fetch
         * has finished: the same last check a song's bytes get before they are played.
         */
        internal suspend fun <A : Any, T : Any> forWhoeverIsSignedIn(account: suspend () -> A?, current: () -> A?, fetch: suspend (A) -> T?): T? {
            val theirs = account() ?: return null
            val found = fetch(theirs) ?: return null
            return if (current() === theirs) found else null
        }

        /** How long an account's artwork id is (`artworkIdOf`: sixteen bytes, in hex). */
        const val KEY_CHARS = 32

        /**
         * The address of a cover of the account with this artwork id (`AccountGraph.artwork`,
         * which says nothing about the account), for a media item; null for no cover, or no
         * account.
         */
        fun uriFor(artwork: String, coverId: String): Uri? =
            if (CoverStore.fileName(coverId) == null || !isKey(artwork)) null
            else Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(artwork).appendPath(coverId).build()

        /** Whether this could be an account's artwork id: hex digits, of the right number. */
        internal fun isKey(key: String): Boolean = key.length == KEY_CHARS && key.all { it in '0'..'9' || it in 'a'..'f' }

        /** The account's artwork id and the cover's id that an address names; null if it isn't one. */
        internal fun partsOf(uri: Uri): Pair<String, String>? =
            if (uri.scheme == "content" && uri.authority == AUTHORITY && uri.pathSegments.size == 2) uri.pathSegments[0] to uri.pathSegments[1] else null

        private fun idOf(uri: Uri): String? = partsOf(uri)?.second
    }
}
