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
 * cover by its id (`content://<app>.covers/<id>`); the picture comes from the cover store,
 * fetched first if it isn't kept yet, within limits: see `openFile`.
 *
 * It gives away nothing but covers: it is read-only, answers no queries, and an address
 * names a cover id, never a file, so nothing else on the phone can be reached through it
 * (an id that isn't one the server would give is refused before any file is looked at).
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read-only")
        val id = idOf(uri) ?: throw FileNotFoundException("Not a cover")
        if (CoverStore.fileName(id) == null) throw FileNotFoundException("Not a cover")
        val context = context ?: throw FileNotFoundException("Not ready")
        // A cover already on the phone, for an account already known: no waiting at all.
        val known = try {
            context.graph.accounts.state.value?.covers
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
        known?.kept(id)?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        // Anything else waits: for the app to start, for the saved sign-in, for the server.
        // This is one of the handful of threads the system gives the app for every call from
        // outside it, the car's browsing and the player's controls among them, so only a few
        // may wait here at once and none for long. A cover that isn't had in time is fetched
        // all the same (see CoverStore.forOutside) and is there the next time it's asked for.
        if (!waiting.tryAcquire()) throw FileNotFoundException("Busy")
        val file = try {
            runBlocking {
                withTimeoutOrNull(WAIT_MILLIS) {
                    val covers = graphOf(context).accounts.restored()?.covers ?: return@withTimeoutOrNull null
                    covers.kept(id) ?: covers.forOutside(id, MISS_WAIT_MILLIS)
                }
            }
        } finally {
            waiting.release()
        } ?: throw FileNotFoundException("No cover $id")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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

        /** The longest a caller is kept, start-up and all; and how long of that a cover not
         *  yet on the phone is waited for. A car's screen gives up on a picture in a few
         *  seconds anyway. */
        private const val WAIT_MILLIS = 6_000L
        private const val MISS_WAIT_MILLIS = 4_000L

        /** Callers that may be waiting at once. The system gives an app about sixteen
         *  threads for calls from outside: this leaves most of them for everything else. */
        private const val MOST_WAITING = 6
        private val waiting = java.util.concurrent.Semaphore(MOST_WAITING)

        /** The address of a cover, for a media item; null for no cover. */
        fun uriFor(coverId: String): Uri? =
            if (CoverStore.fileName(coverId) == null) null else Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(coverId).build()

        private fun idOf(uri: Uri): String? =
            if (uri.scheme == "content" && uri.authority == AUTHORITY && uri.pathSegments.size == 1) uri.pathSegments[0] else null
    }
}
