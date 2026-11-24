package org.medianexus.music.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.medianexus.music.Reach
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.downloads.SongState
import org.medianexus.music.net.Song
import org.medianexus.music.ui.shell.LocalUi

/** What a song's row shows of the downloads and of being offline: where its download stands,
 *  and whether it can't be played now (offline, and not on the phone). */
@Immutable
data class RowMark(val state: SongState = SongState.None, val unavailable: Boolean = false) {
    companion object {
        val NONE = RowMark()
    }
}

/**
 * The downloads and where the app stands with the server, for pages that list songs, held
 * as Compose state that the page itself never reads. Each row reads its own mark through
 * [rowMark], which is worked out from these when they change and redraws the row only if
 * its own mark changed: while songs arrive, the downloads change twice a second, and only
 * the rows of the songs arriving are drawn again (as Windows marks only the rows of the
 * song that moved). A page of thousands of songs scrolls the same with marks as without.
 */
@Stable
class DownloadMarks internal constructor(
    private val downloads: State<DownloadsState>,
    private val reach: State<Reach>,
    private val plays: (Song) -> Boolean,
) {
    /** The downloads as they stand. Reading it makes the reader follow every change: for
     *  what summarises the downloads (a list's download button, the Downloads page), inside
     *  `derivedStateOf` where it can be. */
    val now: DownloadsState get() = downloads.value

    val offline: Boolean get() = reach.value.offline

    val reachNow: Reach get() = reach.value

    /** A song's mark, as things stand. */
    fun markOf(song: Song): RowMark = RowMark(downloads.value.stateOf(song.id), reach.value.offline && !plays(song))

    fun playsNow(song: Song): Boolean = plays(song)
}

private val NO_DOWNLOADS = MutableStateFlow(DownloadsState())

/** The signed-in account's download marks (see [DownloadMarks]). */
@Composable
fun rememberDownloadMarks(): DownloadMarks {
    val ui = LocalUi.current
    val account = ui.account
    val downloads = (account?.downloads?.state ?: NO_DOWNLOADS).collectAsStateWithLifecycle()
    val reach = ui.graph.watch.state.collectAsStateWithLifecycle()
    val availability = account?.availability
    return remember(downloads, reach, availability) {
        DownloadMarks(downloads, reach, availability?.let { it::plays } ?: { _: Song -> true })
    }
}

/** This song's mark, for its row: the row is drawn again only when it changes. */
@Composable
fun DownloadMarks.rowMark(song: Song): RowMark {
    val mark by remember(this, song) { derivedStateOf { markOf(song) } }
    return mark
}

/** The signed-in account's downloads, as they stand, followed in full: for the pages that
 *  are about the downloads themselves. */
@Composable
fun downloadsNow(): DownloadsState {
    val flow = LocalUi.current.account?.downloads?.state ?: NO_DOWNLOADS
    val state by flow.collectAsStateWithLifecycle()
    return state
}

/** Where the app stands with the server (offline by hand, or out of reach). */
@Composable
fun reachNow(): Reach {
    val reach by LocalUi.current.graph.watch.state.collectAsStateWithLifecycle()
    return reach
}

/**
 * A list's download button, as Windows' `sync_download_button` works it out: 0 nothing of it
 * downloaded (or only some), 1 some of it on its way, 2 all of it downloaded; how many of
 * its songs are downloaded, of how many; and how far it has got, counting songs part-way.
 */
@Immutable
data class ListDownload(val state: Int, val done: Int, val total: Int, val progress: Float) {
    companion object {
        /** `ids` are the list's songs, as a set: made once for the list, not at every change
         *  (a song in it twice is one song to download). */
        fun of(downloads: DownloadsState, ids: Set<String>): ListDownload {
            if (ids.isEmpty()) return ListDownload(0, 0, 0, 0f)
            // Whichever of the two is smaller is the one gone through.
            val saved = if (ids.size <= downloads.downloaded.size) ids.count { it in downloads.downloaded } else downloads.downloaded.keys.count { it in ids }
            var busy = false
            var partial = 0f
            for (active in downloads.active) {
                if (active.song.id !in ids) continue
                busy = true
                partial += ((active.state as? SongState.Fetching)?.fraction ?: 0f).coerceAtLeast(0f)
            }
            val state = when {
                busy -> 1
                saved == ids.size && saved > 0 -> 2
                else -> 0
            }
            return ListDownload(state, saved, ids.size, ((saved + partial) / ids.size.coerceAtLeast(1)).coerceIn(0f, 1f))
        }
    }
}

/**
 * Offline (by hand, or the server out of reach): every song that plays with no server, the
 * downloaded ones first (`Availability.playable`), worked out off the main thread when being
 * offline begins and whenever what is downloaded changes. Null while the server answers, and
 * for the moment before the first answer. What the offline Library, Home and Search are made
 * of.
 */
@Composable
fun playableOffline(): List<Song>? {
    val ui = LocalUi.current
    val marks = rememberDownloadMarks()
    val offline by remember(marks) { derivedStateOf { marks.offline } }
    // What is downloaded, and nothing else about the downloads: it changes when a song
    // arrives or goes, not as each one does.
    val downloaded by remember(marks) { derivedStateOf { marks.now.downloaded } }
    val availability = ui.account?.availability
    val playable by androidx.compose.runtime.produceState<List<Song>?>(null, offline, downloaded, availability) {
        value = if (!offline || availability == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { availability.playable() }
    }
    return if (offline) playable else null
}
