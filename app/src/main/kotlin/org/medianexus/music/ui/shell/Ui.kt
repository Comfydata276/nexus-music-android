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
import org.medianexus.music.AccountGraph
import org.medianexus.music.Graph
import org.medianexus.music.net.Song
import org.medianexus.music.store.Page
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/** A song whose menu is open, and where it was opened from: `upcoming` is its place after
 *  the song playing when it was opened from the queue (it can then be taken out). */
data class SongMenu(val song: Song, val upcoming: Int? = null)

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

    /** Set by the shell: asks for what playing needs (notifications, the first time). */
    internal var beforePlay: () -> Unit = {}

    val player get() = graph.player

    /** Opens a page, closing Now Playing if it's over it (its artist, say). */
    fun open(route: Route) {
        queueOpen = false
        nowPlaying = false
        navigator.open(route)
    }

    fun toast(text: String) = graph.toasts.show(text)

    /** Plays a list from one of its songs (see `PlayerConnection.play`). */
    fun play(songs: List<Song>, start: Int, context: String, source: String = "", shuffle: Boolean? = null, anyStart: Boolean = false) {
        if (songs.isEmpty()) return
        beforePlay()
        player.play(songs, start, context, source, shuffle, anyStart)
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

/** The feed for one page of the signed-in account; `spec` is made once per `key`. */
@Composable
fun <T> rememberPageFeed(key: String, spec: (org.medianexus.music.library.Library) -> PageSpec<T>): PageFeed<T> {
    val account = LocalUi.current.account
    val feed = remember(key, account) { PageFeed<T>() }
    LaunchedEffect(feed, feed.asked) {
        val library = account?.library ?: return@LaunchedEffect
        val asked = feed.asked
        library.live(spec(library), if (asked > 0) 0 else PageCache.FRESH_FOR_MILLIS).collect { page ->
            // A copy that's nothing yet (none kept) doesn't take away what's shown.
            feed.page = if (page.value == null && feed.page.value != null) page.copy(value = feed.page.value) else page
            if (!page.checking) feed.refreshing = false
        }
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
