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

/** A page the app can show. Later milestones add the album, artist, playlist… pages here. */
@Serializable
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Search : Route
    @Serializable data object Library : Route
    @Serializable data object Settings : Route
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
            addAll(saved?.stacks?.get(tab)?.takeIf { it.isNotEmpty() } ?: listOf(Place(tab.root, next++)))
        }
    }

    fun stack(tab: Tab): List<Place> = stacks.getValue(tab)

    /** What's shown, bottom to top: Home's history under the current tab's, so that Back
     *  (and its preview) leads from a tab's first page to Home. */
    val visible: List<Place>
        get() = if (tab == Tab.HOME) stack(Tab.HOME) else stack(Tab.HOME) + stack(tab)

    val canGoBack: Boolean get() = visible.size > 1

    fun open(route: Route) {
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
