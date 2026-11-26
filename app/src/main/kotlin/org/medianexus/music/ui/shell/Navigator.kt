package org.medianexus.music.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.medianexus.music.R

/**
 * A page the app can show. A page for one thing carries what the card or row it was opened
 * from already said about it (a name, a cover), so its header is drawn the moment it opens,
 * before anything is read, as Windows does (`pages::hint`).
 */
@Serializable
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Search : Route
    @Serializable data object Library : Route
    @Serializable data object Settings : Route
    @Serializable data object Diagnostics : Route
    @Serializable data object ControlApps : Route
    @Serializable data class Album(val id: String, val title: String = "", val artist: String = "", val cover: String = "") : Route
    @Serializable data class Artist(val id: String, val name: String = "", val cover: String = "") : Route
    @Serializable data class Playlist(val id: String, val name: String = "", val cover: String = "") : Route
    @Serializable data object Liked : Route
    @Serializable data class Genre(val name: String) : Route
    /** Every album, in one of the Albums sorts (a place in `ALBUM_SORTS`). */
    @Serializable data class Albums(val sort: Int) : Route
    /** What is downloaded and on its way (reached from Library). */
    @Serializable data object Downloads : Route
    /** An album, playlist or Liked songs as the downloads have it (`DownloadedViews`), with
     *  no server and no kept page needed: `key` is the list's ("album:<id>", "playlist:<id>",
     *  "liked"). */
    @Serializable data class Downloaded(val list: String, val title: String = "") : Route
    /** The licences of what comes with the app (Settings, About). */
    @Serializable data object Licences : Route
    /** Every song in the library, newest first (Library's Songs, on a page of its own). */
    @Serializable data object Songs : Route
    /** The last hundred songs played (from Home and Library). */
    @Serializable data object RecentlyPlayed : Route
    /** The internet radio stations set up on the server (Library). */
    @Serializable data object Radio : Route
    /** One of the "Made for you" mixes, by its id ("on-repeat", "genre:Rock", "rule:<id>"). */
    @Serializable data class Mix(val id: String, val title: String = "", val cover: String = "") : Route
    /** "Your stats", for a period (`Stats.TODAY` … `Stats.ALL`, a finer choice, or a year). */
    @Serializable data class Stats(val period: Int = org.medianexus.music.library.Stats.TODAY) : Route
    /** Jams (from Library, Connect and the strip): `link` is one another app handed over. */
    @Serializable data class Jam(val link: String = "") : Route
    /** Requests: an artist as the wider world knows them (Lidarr's name for them), with what
     *  the card or row it was opened from said of them. */
    @Serializable data class OutsideArtist(val id: String, val name: String = "", val cover: String = "") : Route
    /** Requests: an album that isn't in the library, by Lidarr's name for it. */
    @Serializable data class OutsideAlbum(val id: String, val title: String = "", val artist: String = "", val cover: String = "") : Route
    /** Requests: what has arrived lately and what is on its way (from Library, and from the
     *  news of an arrival). */
    @Serializable data object Requests : Route

    /** The key the Windows app files the page under ("album:<id>"): where its list's
     *  choices are remembered, and what a queue started from it is said to come from. */
    val key: String
        get() = when (this) {
            is Album -> "album:$id"
            is Artist -> "artist:$id"
            is Playlist -> "playlist:$id"
            Liked -> "liked"
            is Genre -> "genre:$name"
            Downloads -> "downloads"
            is Downloaded -> "downloads:$list"
            Songs -> org.medianexus.music.library.Library.SONGS
            // (Windows' keys: what a queue started there is said to come from.)
            is Mix -> "mix:$id"
            is Stats -> "stats"
            else -> ""
        }
}

/** The bottom bar's tabs, each with its own history of pages. */
enum class Tab(val root: Route, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    HOME(Route.Home, R.string.tab_home, R.drawable.ic_house),
    SEARCH(Route.Search, R.string.tab_search, R.drawable.ic_search),
    LIBRARY(Route.Library, R.string.tab_library, R.drawable.ic_library),
}

/** One visit to a page. The number tells two visits to the same page apart, so each keeps
 *  its own scroll position and the like. */
@Serializable
data class Place(val route: Route, val id: Long)

/** How the last change of page came about, so it can be animated to suit. */
enum class Motion { PUSH, POP, SWITCH }

@Serializable
private data class Saved(val tab: Tab, val stacks: Map<Tab, List<Place>>, val next: Long)

/**
 * The app's own back stack: one history per tab. Back goes back through the tab's history;
 * from the first page of Search or Library it goes to Home, as it would on most phones; from
 * Home's first page it leaves the app.
 */
@Stable
class Navigator private constructor(saved: Saved?) {
    var tab: Tab by mutableStateOf(saved?.tab ?: Tab.HOME)
        private set
    var motion: Motion = Motion.SWITCH
        private set
    private var next = saved?.next ?: 0L
    private val stacks: Map<Tab, SnapshotStateList<Place>> = Tab.entries.associateWith { tab ->
        mutableStateListOf<Place>().apply {
            // (A history saved by a build with pages this one doesn't have, requests' or jams',
            // comes back without them: never a blank page in their place.)
            addAll(saved?.stacks?.get(tab)?.filter { exists(it.route) }?.takeIf { it.isNotEmpty() } ?: listOf(Place(tab.root, next++)))
        }
    }

    fun stack(tab: Tab): List<Place> = stacks.getValue(tab)

    /** What's shown, bottom to top: Home's history under the current tab's, so that Back
     *  (and its preview) leads from a tab's first page to Home. */
    val visible: List<Place>
        get() = if (tab == Tab.HOME) stack(Tab.HOME) else stack(Tab.HOME) + stack(tab)

    val canGoBack: Boolean get() = visible.size > 1

    fun open(route: Route) {
        // No way to jams or requests in a build where they don't exist.
        if (!exists(route)) return
        motion = Motion.PUSH
        stacks.getValue(tab) += Place(route, next++)
    }

    /** Choosing the tab that's showing goes back to its first page. */
    fun select(chosen: Tab) {
        val stack = stacks.getValue(chosen)
        if (chosen == tab) {
            if (stack.size > 1) {
                motion = Motion.POP
                stack.removeRange(1, stack.size)
            }
        } else {
            motion = Motion.SWITCH
            tab = chosen
        }
    }

    fun back() {
        val stack = stacks.getValue(tab)
        motion = Motion.POP
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            tab != Tab.HOME -> tab = Tab.HOME
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Whether this build has the page: jams' only with jams (jam/JamBuild), requests' only
         *  with requests (requests/RequestsBuild). */
        fun exists(route: Route): Boolean = when (route) {
            is Route.Jam -> org.medianexus.music.jam.JamBuild.on
            is Route.OutsideArtist, is Route.OutsideAlbum, Route.Requests -> org.medianexus.music.requests.RequestsBuild.on
            else -> true
        }

        /** Kept through the activity being rebuilt and the process being stopped. A saved
         *  history that no longer reads is dropped for a fresh one. */
        val saver: Saver<Navigator, String> = Saver(
            save = { navigator ->
                json.encodeToString(Saved.serializer(), Saved(navigator.tab, navigator.stacks.mapValues { it.value.toList() }, navigator.next))
            },
            restore = { text -> Navigator(runCatching { json.decodeFromString(Saved.serializer(), text) }.getOrNull()) },
        )

        fun fresh() = Navigator(null)
    }
}

@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = Navigator.saver) { Navigator.fresh() }
