package org.medianexus.music

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.medianexus.music.library.Library
import org.medianexus.music.library.Likes
import org.medianexus.music.net.Subsonic
import org.medianexus.music.playback.KeptQueue
import org.medianexus.music.playback.QueueFiles
import org.medianexus.music.playback.QueueSaver
import org.medianexus.music.store.Account
import org.medianexus.music.store.CoverStore
import org.medianexus.music.store.PageCache

/**
 * What belongs to the account that's signed in: its client for the server, its kept pages
 * and covers, its library, its likes and its saved queue. Made when someone signs in (or the
 * saved sign-in is read at start-up) and closed when they sign out, so nothing of one account
 * is ever shown to another.
 */
class AccountGraph(
    val account: Account,
    val api: Subsonic,
    queueSaver: QueueSaver,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Work for this account; cancelled when it signs out. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pages = PageCache(File(account.dirs.cache, "pages"), scope, io)

    val covers = CoverStore(File(account.dirs.cache, "covers"), scope, io, fetch = { id, size ->
        api.fetchBytes(api.coverUrl(id, size), MOST_COVER_BYTES)
    })

    val library = Library(api, pages)

    val likes = Likes(pages, library.liked(), send = { target, id, liked -> api.setStarred(target, id, liked) }, scope)

    /** The play queue as it was left, kept with the account's own data. */
    val queue = KeptQueue(QueueFiles.of(account.dirs.data), queueSaver)

    /** Where kept songs go. Opened by the playback service, which owns them. */
    val keptSongsDir = File(account.dirs.cache, "songs")

    init {
        likes.load()
        // Covers past their limit go, oldest first, once a start: it reads the whole folder.
        scope.launch { covers.trim() }
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        const val MOST_COVER_BYTES = 16L * 1024 * 1024
    }
}
