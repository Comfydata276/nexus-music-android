package org.medianexus.music.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.withLock
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
 *   shown, which is left out. A page that brings nothing new still moves the place on, and
 *   counts as progress ([fetched]), so loading carries on past it. (One taken away shifts
 *   them back, and one album is passed over until the next refresh: the server gives nothing
 *   to notice it by.)
 * - What's loaded stays on screen while a refresh is under way, kept copies of the first page
 *   included; the server's first page replaces it when it arrives.
 * - A later page shown from the phone's copy is put right when the server's arrives.
 * - A page that fails is said so at the foot of the grid, with "Try again". One whose job is
 *   cancelled (the screen moved on) leaves the pager able to ask again.
 * - A page is added to the list in place: the albums already shown aren't copied again.
 */
@Stable
class AlbumPages(
    private val pageSize: Int,
    /** Fetches the page at `offset`; `changed` is to be told if what it gave is replaced by
     *  the server's afterwards (it gave the phone's copy), on the thread the pager is used on. */
    private val fetch: suspend (offset: Int, changed: (List<Album>) -> Unit) -> List<Album>,
) {
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

    /** Pages after the first fetched in this refresh: goes up with each, even one that
     *  brought nothing new, so whatever waits on progress knows to ask again. */
    var fetched by mutableIntStateOf(0)
        private set

    private val shown = HashSet<String>()
    private var base: List<Album>? = null
    /** The pages after the first, by where they start, as fetched. */
    private val later = java.util.TreeMap<Int, List<Album>>()
    /** How many albums the server has given in all (the next page's offset), counting any
     *  that were left out as already shown. */
    private var offset = 0
    /** A refresh is waiting for the server's first page: until it comes, what is shown stays. */
    private var refreshing = false

    /**
     * The first page, kept (`fresh` false) or the server's. The pages after it stay, less
     * anything it now has (the server's order may have moved on a little since they were
     * fetched). After [refresh], only the server's is taken, and it starts the list afresh.
     */
    fun first(page: List<Album>, fresh: Boolean = true) {
        if (refreshing) {
            if (!fresh) return
            refreshing = false
            // Anything still on its way belongs to the list before.
            generation++
            asking = false
            later.clear()
            base = null
            fetched = 0
        }
        if (page == base) return
        base = page
        rebuild()
        if (later.isEmpty()) {
            offset = page.size
            ended = page.size < pageSize
        }
    }

    /** A pull to refresh (or "Try again" on the whole page): pages still on their way are
     *  dropped when they arrive. What's shown stays until the server's first page comes. */
    fun refresh() {
        generation++
        refreshing = true
        failed = null
        asking = false
    }

    /** Pages are asked for one at a time: a second ask waits for the first's page, then asks
     *  for the one after it. */
    private val turn = kotlinx.coroutines.sync.Mutex()

    /** Asks for the next page, unless the last failed (see [retry]) or there is no more.
     *  Says whether a page came (new albums or not). */
    suspend fun more(): Boolean = turn.withLock {
        if (ended || failed != null || base == null) return false
        load()
    }

    /** Asks again for the page that failed. */
    suspend fun retry(): Boolean = turn.withLock {
        if (base == null) return false
        failed = null
        load()
    }

    /** Every page, one after another, to the end (a genre's albums). */
    suspend fun loadAll() {
        while (!ended && failed == null) if (!more()) break
    }

    /** Pages until at least `count` albums are shown (coming back to where the grid was). */
    suspend fun loadUntil(count: Int) {
        while (albums.size < count && !ended && failed == null) if (!more()) break
    }

    private suspend fun load(): Boolean {
        val mine = generation
        val from = offset
        asking = true
        val page = try {
            fetch(from) { fresh -> replaced(mine, from, fresh) }
        } catch (e: ApiError) {
            if (mine == generation) {
                failed = e
                asking = false
            }
            return false
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Its job was cancelled (the screen restarted it, or went): nothing is on its
            // way any more, and the next ask must be let through.
            if (mine == generation) asking = false
            throw e
        }
        // A refresh has happened since: this page belongs to a list that's gone.
        if (mine != generation) return false
        asking = false
        offset = from + page.size
        if (page.size < pageSize) ended = true
        later[from] = page
        albums.addAll(page.filter { shown.add(it.id) })
        fetched++
        return true
    }

    /** The page at `from` was shown from the phone's copy, and the server's differs. */
    private fun replaced(mine: Int, from: Int, page: List<Album>) {
        if (mine != generation || from !in later) return
        later[from] = page
        rebuild()
    }

    private fun rebuild() {
        shown.clear()
        val list = ArrayList<Album>()
        for (album in base.orEmpty()) if (shown.add(album.id)) list.add(album)
        for (part in later.values) for (album in part) if (shown.add(album.id)) list.add(album)
        albums.clear()
        albums.addAll(list)
    }
}
