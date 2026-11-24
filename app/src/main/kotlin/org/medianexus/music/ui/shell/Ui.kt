package org.medianexus.music.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.medianexus.music.AccountGraph
import org.medianexus.music.Graph
import org.medianexus.music.net.Song
import org.medianexus.music.store.Page
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/** A song whose menu is open, and where it was opened from: `upcoming` is its queue entry's
 *  own number when it was opened from the queue (it can then be taken out, and that entry,
 *  not whatever is in its place by the time the menu is used, is what goes). `remove` is the
 *  queue sheet's own way to take it out, so the sheet shows it gone at once. */
data class SongMenu(val song: Song, val upcoming: Long? = null, val remove: (() -> Unit)? = null, val inPlaylist: InPlaylist? = null)

/** A song's row in a playlist the account can change: the playlist as shown, and the row's
 *  place in it (what "Remove from this playlist" takes out, by the song and which copy). */
data class InPlaylist(val playlist: org.medianexus.music.net.Playlist, val index: Int)

/**
 * What every screen shares while someone is signed in: the app's objects, the account's,
 * the pages' history, and the few things that sit over every page (Now Playing, the queue,
 * a song's menu). Screens reach it through [LocalUi].
 */
@Stable
class Ui(val graph: Graph, val navigator: Navigator) {
    /** The signed-in account's objects; null for a moment while signing out. */
    var account: AccountGraph? by mutableStateOf(null)
        internal set

    var nowPlaying by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var menu: SongMenu? by mutableStateOf(null)

    /** A sheet over everything (see [Sheet]); null for none. */
    var sheet: Sheet? by mutableStateOf(null)

    /**
     * Runs a download the person asked for, having first asked (once, on Android 13 and
     * later) for the notifications permission its progress notification needs. The asking
     * belongs to the shell, which outlives whatever asked (a song's menu closes as it is
     * used, and the answer must still find its way back).
     */
    var askToDownload: (() -> Unit) -> Unit = { it() }
        internal set

    val player get() = graph.player

    /** Opens a page, closing Now Playing if it's over it (its artist, say). */
    fun open(route: Route) {
        queueOpen = false
        nowPlaying = false
        navigator.open(route)
    }

    fun toast(text: String) = graph.toasts.show(text)

    /** Plays a list from one of its songs (see `PlayerConnection.play`). */
    fun play(songs: List<Song>, start: Int, context: String, source: String = "", shuffle: Boolean? = null, anyStart: Boolean = false, view: org.medianexus.music.library.ListView? = null, shown: IntArray? = null) {
        if (songs.isEmpty()) return
        player.play(songs, start, context, source, shuffle, anyStart, view, shown)
    }

    /** Remembers the order a list is shown in, for the next visit (Windows'
     *  `remember_track_sort`), with when it was chosen. */
    fun rememberSort(key: String, view: org.medianexus.music.library.ListView) {
        if (key.isEmpty()) return
        graph.settings.update { s ->
            s.copy(lists = s.lists + (key to org.medianexus.music.library.ListView.remember(s.lists[key], view, System.currentTimeMillis() / 1000)))
        }
    }

    /** The shuffle button on a list's page: sets whether that list plays shuffled, without
     *  starting it; if it's the list playing, the queue follows. */
    fun toggleListShuffle(key: String, playingFrom: String, queueShuffle: Boolean) {
        if (key.isEmpty()) return
        if (key == playingFrom) {
            player.setShuffle(!queueShuffle)
            return
        }
        val on = !(graph.settings.value.lists[key]?.shuffle ?: false)
        graph.settings.update { s ->
            val prefs = s.lists[key] ?: org.medianexus.music.store.ListPrefs()
            s.copy(lists = s.lists + (key to prefs.copy(shuffle = on, at = System.currentTimeMillis() / 1000)))
        }
    }
}

val LocalUi = staticCompositionLocalOf<Ui> { error("Not inside the shell") }

/**
 * One page's content as a screen shows it: the kept copy at once, then the server's, then any
 * change made here meanwhile ([org.medianexus.music.library.Library.live]). Refreshing asks the
 * server again while what's shown stays, and a failure never takes away what's shown.
 */
@Stable
class PageFeed<T> {
    var page: Page<T> by mutableStateOf(Page())
        internal set
    var refreshing by mutableStateOf(false)
        internal set
    internal var asked by mutableIntStateOf(0)

    val value: T? get() = page.value

    /** How many times it has been refreshed. */
    val refreshCount: Int get() = asked

    /** Pulling down, or "Try again". */
    fun refresh() {
        refreshing = true
        asked++
    }
}

/**
 * The feed for one page of the signed-in account; `spec` is made once per `key`. It is
 * collected only while the page is on screen and the app is in front: a page under another,
 * or the app in the background, asks nothing and follows nothing. Coming back shows what it
 * had and checks again (unless it was checked moments ago).
 *
 * A page whose check failed tries again by itself when the connection comes back (see
 * `Graph.reconnects`).
 */
@Composable
fun <T> rememberPageFeed(key: String, spec: (org.medianexus.music.library.Library) -> PageSpec<T>): PageFeed<T> {
    val ui = LocalUi.current
    val account = ui.account
    val feed = remember(key, account) { PageFeed<T>() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feed, feed.asked, lifecycle) {
        val library = account?.library ?: return@LaunchedEffect
        val asked = feed.asked
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            library.live(spec(library), if (asked > 0) 0 else PageCache.FRESH_FOR_MILLIS).collect { page ->
                // A copy that's nothing yet (none kept) doesn't take away what's shown.
                feed.page = if (page.value == null && feed.page.value != null) page.copy(value = feed.page.value) else page
                if (!page.checking) feed.refreshing = false
            }
        }
    }
    val reconnects by ui.graph.reconnects.collectAsStateWithLifecycle()
    LaunchedEffect(feed, reconnects) {
        if (reconnects > 0 && feed.page.error != null && !feed.refreshing) feed.refresh()
    }
    return feed
}

private val NOTHING_LIKED = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())

/** The ids of the songs liked, as they change. */
@Composable
fun likedSongs(): Set<String> {
    val flow = LocalUi.current.account?.likes?.liked ?: NOTHING_LIKED
    return flow.collectAsStateWithLifecycle().value
}

private val NOTHING = kotlinx.coroutines.flow.MutableStateFlow(org.medianexus.music.library.Liked())

/** The ids of the albums liked, as they change. */
@Composable
fun likedAlbums(): Set<String> {
    val flow = LocalUi.current.account?.likes?.state ?: NOTHING
    return flow.collectAsStateWithLifecycle().value.albums
}

/** Opens what a card stands for. */
fun Ui.open(card: org.medianexus.music.ui.parts.CardData) {
    val kind = card.kind
    open(
        when (kind) {
            org.medianexus.music.ui.parts.CardKind.ALBUM -> Route.Album(card.id, card.title, card.subtitle, card.cover)
            org.medianexus.music.ui.parts.CardKind.ARTIST -> Route.Artist(card.id, card.title, card.cover)
            org.medianexus.music.ui.parts.CardKind.PLAYLIST, org.medianexus.music.ui.parts.CardKind.MIX -> Route.Playlist(card.id, card.title, card.cover)
            org.medianexus.music.ui.parts.CardKind.GENRE -> Route.Genre(card.id)
        },
    )
}
