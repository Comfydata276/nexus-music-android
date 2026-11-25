package org.medianexus.music.ui.requests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.medianexus.music.AccountGraph
import org.medianexus.music.R
import org.medianexus.music.core.Asking
import org.medianexus.music.requests.Requests
import org.medianexus.music.requests.RequestsBuild
import org.medianexus.music.requests.RequestsPages
import org.medianexus.music.requests.RequestsSettings
import org.medianexus.music.requests.Said
import org.medianexus.music.requests.RequestsState
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Sheet

/**
 * Requests as a screen finds them, for the account signed in: its connection to Lidarr and
 * everything the pages ask for ([Requests] and [RequestsPages], the only two things of
 * requests a screen calls), where the connection stands, and whether offline mode is on.
 *
 * [key] is which account and which connection this is. **What a screen holds belongs to
 * it**: a look, a page, a row to act on. When it changes (another account; another address,
 * sign-in or person signed in there; requests switched off and on) the screen drops what it
 * had and asks again, and never acts with a row it was holding. (A key changed at the same
 * address is caught below the screens: an action from an older connection is refused, with
 * its sentence.) Nothing of it is ever saved with a screen's state.
 */
@Immutable
class RequestsHere(
    val account: AccountGraph,
    val requests: Requests,
    val pages: RequestsPages,
    val state: RequestsState,
    val settings: RequestsSettings,
    /** Offline mode is on (switched on by hand): nothing of requests is asked. */
    val offline: Boolean,
) {
    /** Which account and connection: see the class. */
    val key: Any = Connection(account, settings.on, settings.address.trim(), settings.service, settings.signedIn)

    /** Connected, and allowed to ask: what search's part, Home's row and Library's row
     *  wait for. */
    val connected: Boolean get() = state is RequestsState.Connected && !offline

    private data class Connection(val account: AccountGraph, val on: Boolean, val address: String, val service: String, val signedIn: String)
}

/** Requests for the account signed in, as they stand now; null in a build without them, or
 *  for a moment while nobody is signed in. */
@Composable
fun requestsHere(): RequestsHere? {
    if (!RequestsBuild.on) return null
    val ui = LocalUi.current
    val account = ui.account ?: return null
    val requests = account.requests ?: return null
    val pages = account.requestsPages ?: return null
    val state by requests.state.collectAsStateWithLifecycle()
    val settings by requests.settings.collectAsStateWithLifecycle()
    val reach by ui.graph.watch.state.collectAsStateWithLifecycle()
    return remember(account, state, settings, reach.manual) { RequestsHere(account, requests, pages, state, settings, reach.manual) }
}

/** What asking needs of requests (in the app, `RequestsPages`' own calls). */
interface AskOps {
    suspend fun asking(album: String, title: String, by: String, song: String, kind: String): Asking
    fun askingFor(title: String): String
    suspend fun request(album: String, title: String, song: String): Said
    suspend fun adding(name: String, open: Int): Pair<String, String>
    fun addingNow(name: String): String
    suspend fun addArtist(id: String, name: String): Said
}

/** What asking shows (in the app, the shell's sheet and passing notes). */
interface AskShow {
    /** A passing note; `wayIn`: with "Requests" on it, the way to see what was asked for. */
    fun say(words: String, wayIn: Boolean = false)

    /** The question, with its heading, its text and what its button says. */
    fun confirm(heading: String, text: String, button: Button, onYes: () -> Unit)

    /** Something was asked of Lidarr: pages that show music beyond the library ask again. */
    fun changed()

    enum class Button { REQUEST, ADD_ARTIST }
}

/**
 * Asking Lidarr for something, as every screen of requests does it (Windows'
 * `prompt_request` and `request_album`, `prompt_add_artist` and `add_artist`): the question
 * first, in the library's words; then the line said meanwhile; then how it went, with
 * "Requests" on the note where it went through (the way to see it).
 *
 * Done in the screen's own scope, and in nothing that outlives it: leaving the screen
 * cancels it, and nothing more is said. Once Lidarr has begun to be changed, `RequestsPages`
 * sees it through all the same and writes down what was asked for.
 */
class Asker(private val scope: CoroutineScope, private val ops: AskOps, private val show: AskShow) {
    /**
     * Asks for an album, by Lidarr's name for it. `by` is who it is by, `song` the song that
     * led here (if one did), `kind` "EP", "Single" or empty, from the offer in hand: the
     * question is put by them. `done` is told when it went through.
     */
    fun album(album: String, title: String, by: String, song: String = "", kind: String = "", done: () -> Unit = {}) {
        if (album.isEmpty()) return
        scope.launch {
            when (val asking = ops.asking(album, title, by, song, kind)) {
                is Asking.Already -> show.say(asking.said, wayIn = true)
                is Asking.Ask -> show.confirm(asking.heading, asking.text, AskShow.Button.REQUEST) {
                    scope.launch {
                        show.say(ops.askingFor(title))
                        val said = ops.request(album, title, song)
                        show.say(said.words, wayIn = said.done)
                        if (said.done) {
                            show.changed()
                            done()
                        }
                    }
                }
            }
        }
    }

    /** "Add artist": has Lidarr get everything by them. `open` is the page's own count of
     *  what could be asked for. */
    fun artist(id: String, name: String, open: Int, done: () -> Unit = {}) {
        if (id.isEmpty() || open <= 0) return
        scope.launch {
            val (heading, text) = ops.adding(name, open)
            show.confirm(heading, text, AskShow.Button.ADD_ARTIST) {
                scope.launch {
                    show.say(ops.addingNow(name))
                    val said = ops.addArtist(id, name)
                    // The library's sentence stops before where to see them; the note's
                    // "Requests" says that, so the sentence is only ended.
                    show.say(if (said.done) ended(said.words) else said.words, wayIn = said.done)
                    if (said.done) {
                        show.changed()
                        done()
                    }
                }
            }
        }
    }

    companion object {
        /** A sentence of the library's that stops short, ended. */
        fun ended(words: String): String = words.trimEnd().let { if (it.isEmpty() || it.endsWith('.')) it else "$it." }
    }
}

/** The way a screen asks for things, in its own scope. */
@Composable
fun rememberAsker(here: RequestsHere): Asker {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    return remember(here.key, scope, res) {
        val pages = here.pages
        Asker(
            scope,
            object : AskOps {
                override suspend fun asking(album: String, title: String, by: String, song: String, kind: String) = pages.asking(album, title, by, song, kind)
                override fun askingFor(title: String) = pages.askingFor(title)
                override suspend fun request(album: String, title: String, song: String) = pages.request(album, title, song)
                override suspend fun adding(name: String, open: Int) = pages.adding(name, open)
                override fun addingNow(name: String) = pages.addingNow(name)
                override suspend fun addArtist(id: String, name: String) = pages.addArtist(id, name)
            },
            object : AskShow {
                override fun say(words: String, wayIn: Boolean) {
                    if (wayIn) ui.graph.toasts.show(words, res.getString(R.string.requests)) { ui.open(Route.Requests) } else ui.toast(words)
                }

                override fun confirm(heading: String, text: String, button: AskShow.Button, onYes: () -> Unit) {
                    val word = res.getString(if (button == AskShow.Button.REQUEST) R.string.request else R.string.add_artist)
                    ui.sheet = Sheet.Confirm(heading, text, word, danger = false, onConfirm = onYes)
                }

                override fun changed() = ui.requestsChanged()
            },
        )
    }
}
