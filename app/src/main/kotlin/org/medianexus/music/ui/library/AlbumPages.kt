package org.medianexus.music.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.medianexus.music.net.Album
import org.medianexus.music.net.ApiError

/**
 * Albums in one sort, a page at a time as the grid scrolls, as Windows asks for them: the
 * first page is a kept page like any other ([first]); the rest are asked for as they're
 * reached ([more]). Plain state, so it can be tested without a screen.
 *
 * - Every page belongs to one refresh: a refresh starts a new one, and a page asked for under
 *   an older one is dropped when it arrives, whatever it says.
 * - No album is ever shown twice. An album added to the library while the grid is open
 *   shifts the server's pages along by one; the next page then starts with one already
 *   shown, which is left out. (One taken away shifts them back, and one album is passed over
 *   until the next refresh: the server gives nothing to notice it by.)
 * - What's loaded stays on screen while a refresh is under way; the refreshed first page
 *   replaces it when it arrives.
 * - A page that fails is said so at the foot of the grid, with "Try again".
 * - A page is added to the list in place: the albums already shown aren't copied again.
 */
@Stable
class AlbumPages(private val pageSize: Int, private val fetch: suspend (offset: Int) -> List<Album>) {
    /** Everything shown, in order, each album once. */
    val albums = mutableStateListOf<Album>()

    /** The refresh the pages belong to. */
    var generation by mutableStateOf(0)
        private set

    /** A page is being asked for. */
    var asking by mutableStateOf(false)
        private set

    /** The last page asked for failed: why. */
    var failed: ApiError? by mutableStateOf(null)
        private set

    /** The server has no more. */
    var ended by mutableStateOf(false)
        private set

    private val shown = HashSet<String>()
    private var base: List<Album>? = null
    /** Albums from the pages after the first, in order. */
    private val later = ArrayList<Album>()
    /** How many albums the server has given in all (the next page's offset), counting any
     *  that were left out as already shown. */
    private var offset = 0

    /**
     * The first page, kept or fresh. The pages after it stay, less anything it now has
     * (the server's order may have moved on a little since they were fetched); a first page
     * that differs entirely, as after a pull to refresh, comes with [refresh].
     */
    fun first(page: List<Album>) {
        if (page == base) return
        base = page
        shown.clear()
        albums.clear()
        for (album in page) if (shown.add(album.id)) albums.add(album)
        for (album in later) if (shown.add(album.id)) albums.add(album)
        if (later.isEmpty()) {
            offset = page.size
            ended = page.size < pageSize
        }
    }

    /** A pull to refresh (or "Try again" on the whole page): the pages after the first are
     *  forgotten, and any still on their way are dropped when they arrive. What's shown stays
     *  until the new first page does. */
    fun refresh() {
        generation++
        later.clear()
        base = null
        failed = null
        asking = false
    }

    /** Asks for the next page, unless one is on its way, the last failed (see [retry]), or
     *  there is no more. */
    suspend fun more() {
        if (asking || ended || failed != null || base == null) return
        load()
    }

    /** Asks again for the page that failed. */
    suspend fun retry() {
        if (asking || base == null) return
        failed = null
        load()
    }

    private suspend fun load() {
        val mine = generation
        val from = offset
        asking = true
        val page = try {
            fetch(from)
        } catch (e: ApiError) {
            if (mine == generation) {
                failed = e
                asking = false
            }
            return
        }
        // A refresh has happened since: this page belongs to a list that's gone.
        if (mine != generation) return
        asking = false
        offset = from + page.size
        if (page.size < pageSize) ended = true
        val added = page.filter { shown.add(it.id) }
        later.addAll(added)
        albums.addAll(added)
    }
}
