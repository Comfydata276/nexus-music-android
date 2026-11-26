package org.medianexus.music.requests

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.medianexus.music.core.Words
import org.medianexus.music.graph

/**
 * Debug builds only: **what is below the screens of requests, driven one call at a time.**
 * It shows where the connection to Lidarr stands, in the words the real screens show, and
 * does what they do, each as one call of the surface they use ([Requests]): nothing here
 * reaches past that surface.
 *
 *     adb shell am start -n org.medianexus.music/.requests.RequestsDebugActivity
 *     ... --es do on|off|check|signin|cancel|signout
 *     ... --es do connect --es address http://10.0.2.2:8686 --es key <key>
 *
 * or the buttons. "Sign in" opens the browser over this screen, as the real one will
 * ([BrowserTab]); coming back to this screen with the browser's part unfinished gives the
 * sign-in up ([Requests.cameBack]). (In a build made without requests: forced on first, [DebugRequests].)
 *
 * The pages and actions, each one call of [RequestsPages], with `--es what <text or id>`
 * (and `--es name`, `--es song` where they are taken):
 *
 *     ... --es do search --es what "quillon vane"      "Not in your library"
 *     ... --es do album --es what <album id>           an album's page
 *     ... --es do question --es what <album id> --es name <title>      the question before asking
 *                          (and --es by <artist>, --es song <the song that led here>, --es kind EP|Single)
 *     ... --es do ask --es what <album id> --es name <title>           asks Lidarr for it
 *     ... --es do artist --es what <artist id>         an artist's page, then their most played
 *     ... --es do add --es what <artist id> --es name <name>           "Add artist"
 *     ... --es do way                                  what is on its way
 *     ... --es do offlist --es what <Lidarr's number> --es name <title>
 *     ... --es do suggest                              artists the person might like
 *     ... --es do singles --es what on|off
 *
 * What each came to is shown, and written whole to `files/requests-debug.txt` for the rig
 * (`adb shell run-as org.medianexus.music cat files/requests-debug.txt`). While this screen
 * exists it is the one place that listens for [RequestsPages.news]: each is added to
 * `files/requests-news.txt`.
 */
class RequestsDebugActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var text: TextView
    private var showing: Job? = null
    private var last = ""

    /** What is on its way, as the last "way" listed it. */
    private var lastWay: List<org.medianexus.music.core.Pending> = emptyList()
    /** [org.medianexus.music.core.OutsideArtist.open] of the artist's page last shown here,
     *  as the real page will have it by it when "Add artist" is pressed. */
    private var open = 0

    /** The connection the page last shown here was found under: what goes back with
     *  anything done from it (see [Found.Got]). */
    private var under = 0L
    private var hearing: Job? = null

    private val requests: Requests? get() = graph.accounts.state.value?.requests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 96, 32, 32)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 17, 23))
            addView(text)
            for (what in listOf("on", "off", "check", "signin", "cancel", "signout")) {
                addView(Button(context).apply {
                    this.text = "requests: $what"
                    setOnClickListener { act(what, null, null) }
                })
            }
        }
        setContentView(ScrollView(this).apply { addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        hearing = scope.launch {
            graph.accounts.state.collectLatest { account ->
                account?.requestsNow?.collectLatest { now ->
                    (now as? RequestsNow.On)?.pages?.news?.collect { java.io.File(filesDir, "requests-news.txt").appendText(it + "\n") }
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val what = intent?.getStringExtra("do") ?: return
        if (what in PAGES) page(what, intent.getStringExtra("what").orEmpty(), intent.getStringExtra("name").orEmpty(), intent.getStringExtra("song").orEmpty(), intent.getStringExtra("by").orEmpty(), intent.getStringExtra("kind").orEmpty())
        else act(what, intent.getStringExtra("address"), intent.getStringExtra("key"))
    }

    /** One call of the pages' surface, and what it came to, in full. */
    private fun page(what: String, about: String, name: String, song: String, by: String, kind: String) {
        val pages = graph.accounts.state.value?.requestsPages ?: return show("no requests here")
        val requests = requests ?: return
        show("$what…")
        scope.launch {
            val lines = ArrayList<String>()
            fun found(found: Found<*>): Any? = when (found) {
                is Found.Got -> {
                    under = found.connection
                    found.value
                }
                is Found.Not -> "NOT: ${found.why}"
            }
            try {
                when (what) {
                    "search" -> {
                        val stages = java.util.Collections.synchronizedList(ArrayList<String>())
                        val all = pages.search(about) { stages += "${it.note} [${it.songs.size} songs, ${it.artists.size} artists, ${it.albums.size} albums]" }
                        lines += "stages:"
                        lines += stages.map { "  $it" }
                        when (all) {
                            is Found.Not -> lines += "NOT: ${all.why}"
                            is Found.Got -> {
                                under = all.connection
                                lines += "note: ${all.value.note}"
                                lines += all.value.songs.map { (song, asked) -> "song: ${song.title} | ${song.artist} | on ${song.albumTitle} (${song.kind.ifEmpty { "Album" }}) | album=${song.album} | artist=${song.artistId}${if (asked) " | asked for" else ""}" }
                                lines += all.value.artists.map { "artist: ${it.name} | ${it.about} | id=${it.id} | cover=${it.cover.isNotEmpty()}" }
                                lines += all.value.albums.map { "album: ${it.title} | ${it.artist} | ${it.year} | ${it.kind.ifEmpty { "Album" }} | id=${it.album}${if (it.requested) " | requested" else ""}" }
                            }
                        }
                    }
                    "album" -> lines += when (val page = found(pages.album(about))) {
                        is org.medianexus.music.core.AlbumPage.Found -> listOf(
                            "album: ${page.album.offer.title} | ${page.album.offer.artist} | id=${page.album.offer.album}", "about: ${page.album.about}", "action: ${page.album.action}",
                            "tracks: ${page.album.tracks.size}${if (page.album.tracksFailed.isEmpty()) "" else " (${page.album.tracksFailed})"}",
                        ) + page.album.tracks.map { "  ${it.disc}.${it.number} ${it.title} (${it.seconds} s)" }
                        is org.medianexus.music.core.AlbumPage.Unavailable -> listOf("unavailable: ${page.why}")
                        else -> listOf("$page")
                    }
                    "question" -> lines += when (val asking = pages.asking(about, name, by = by, song = song, kind = kind, connection = under)) {
                        is org.medianexus.music.core.Asking.Already -> listOf("already: ${asking.said}")
                        is org.medianexus.music.core.Asking.Refused -> listOf("refused${if (asking.stale) " (stale)" else ""}: ${asking.said}")
                        is org.medianexus.music.core.Asking.Ask -> listOf("heading: ${asking.heading}", "text: ${asking.text}")
                    }
                    "ask" -> {
                        lines += "saying: ${pages.askingFor(name)}"
                        lines += pages.request(about, name, song, under).let { "${if (it.done) "done" else "NOT done"}: ${it.words}" }
                    }
                    "artist" -> {
                        val page = found(pages.artist(about))
                        if (page is org.medianexus.music.core.OutsideArtist) {
                            open = page.open
                            lines += listOf("artist: ${page.name}", "about: ${page.about}", "open: ${page.open}", "empty: ${page.empty}${if (page.failed) " (failed)" else ""}")
                            lines += page.albums.map { "album: ${it.offer.title} | ${it.caption} | id=${it.offer.album}${if (it.library != null) " | library=${it.library?.id}" else ""}" }
                            lines += page.smaller.map { "smaller: ${it.offer.title} | ${it.caption} | id=${it.offer.album}" }
                            val popular = found(pages.popular(about, page.name))
                            if (popular is org.medianexus.music.core.Popular) {
                                lines += "popular: ${popular.heading.ifEmpty { "(none)" }}"
                                lines += popular.songs.map { (song, asked) -> "  ${song.title} | on ${song.albumTitle} | album=${song.album}${if (asked) " | asked for" else ""}" }
                            } else {
                                lines += "popular: $popular"
                            }
                        } else {
                            lines += "$page"
                        }
                    }
                    "add" -> {
                        val (heading, text) = pages.adding(name, open)
                        lines += listOf("heading: $heading", "text: $text", "saying: ${pages.addingNow(name)}")
                        lines += pages.addArtist(about, name, under).let { "${if (it.done) "done" else "NOT done"}: ${it.words}" }
                    }
                    "way" -> {
                        val waiting = found(pages.onTheirWay())
                        if (waiting is org.medianexus.music.core.Waiting) {
                            lines += waiting.arrived.map { (asked, line) -> "arrived: ${asked.title} | $line | library=${asked.libraryAlbum}" }
                            lines += "heading: ${waiting.heading.ifEmpty { "(none)" }}"
                            lastWay = waiting.pending.map { it.first }
                            lines += waiting.pending.map { (album, line) -> "pending: ${album.title} | $line | number=${album.id}" }
                            lines += "empty: ${waiting.empty}${if (waiting.failed) " (failed)" else ""}"
                        } else {
                            lines += "$waiting"
                        }
                        lines += "asked for through this phone: ${pages.asked.value.map { "${it.title}${if (it.arrived != 0L) " (arrived)" else ""}" }}"
                    }
                    // (The row as the last "way" showed it; one it never showed is refused, as the rule is.)
                    "offlist" -> lines += pages.cancel(lastWay.firstOrNull { it.id == about.toLongOrNull() && (name.isEmpty() || it.title == name) } ?: org.medianexus.music.core.Pending(about.toLongOrNull() ?: -1, name, ""), under).let { "${if (it.done) "done" else "NOT done"}: ${it.words}" }
                    "suggest" -> lines += pages.suggest().map { "suggested: ${it.name} | like ${it.like} | id=${it.id}" }.ifEmpty { listOf("(no suggestions)") }
                    "singles" -> {
                        requests.setSingles(about == "on")
                        lines += "singles and EPs: ${requests.settings.value.singles}"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lines += "THREW ${e.javaClass.simpleName}: ${e.message}"
            }
            val all = (listOf("$what ${listOf(about, name, song).filter { it.isNotEmpty() }}") + lines).joinToString("\n")
            java.io.File(filesDir, "requests-debug.txt").writeText(all + "\n")
            show(all)
        }
    }

    override fun onStart() {
        super.onStart()
        showing = scope.launch {
            // (The account may still be coming up.)
            graph.accounts.state.collectLatest { account ->
                if (account == null) {
                    show("no requests here: nobody is signed in")
                    return@collectLatest
                }
                account.requestsNow.collectLatest { now ->
                    when (now) {
                        RequestsNow.Off -> show("no requests here: the toggle is off (or the build was made without them)\nlast: $last")
                        RequestsNow.NotYet -> show("requests: not yet (${RequestsWords.NOT_YET})")
                        is RequestsNow.On -> now.requests.state.collectLatest { state -> show(describe(now.requests, state)) }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        showing?.cancel()
    }

    override fun onResume() {
        super.onResume()
        // The one in front, as the app's own screen says of itself (`Graph.resumed`): this
        // screen stands for it in the walks.
        graph.resumed.value = true
        // (As the real screen will: the browser's part may have been left unfinished.)
        requests?.cameBack()
    }

    override fun onPause() {
        graph.resumed.value = false
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun act(what: String, address: String?, key: String?) {
        // (The toggle turned on from nothing: the account's requests come into being.)
        if (what == "on" && requests == null) {
            val account = graph.accounts.state.value ?: return show("no requests here: nobody is signed in")
            scope.launch {
                account.turnRequestsOn()
                last = "switched on: ${account.requests?.state?.value}"
            }
            return
        }
        val requests = requests ?: return show("no requests here")
        scope.launch {
            last = when (what) {
                "on" -> "switched on: ${requests.switch(true)}"
                "off" -> "switched off: ${requests.switch(false)}"
                "check" -> "checked: ${requests.check()}"
                "connect" -> "connect: ${requests.connect(address.orEmpty(), key.orEmpty())}"
                "signin" -> {
                    val page = requests.signIn()
                    if (page == null) "sign-in: nothing to open" else if (BrowserTab.open(this@RequestsDebugActivity, page)) "sign-in: the browser was opened" else "sign-in: this phone has no browser"
                }
                "cancel" -> "sign-in given up".also { requests.cancelSignIn() }
                "signout" -> "signed out: ${requests.signOut()}"
                else -> "not something this screen does: $what"
            }
            show(describe(requests, requests.state.value))
        }
    }

    private suspend fun describe(requests: Requests, state: RequestsState): String {
        val settings = requests.settings.value
        // What the real screen will say for each state, in the library's own words.
        val says = when (state) {
            RequestsState.Off -> "(off)"
            RequestsState.NotConnected -> Words.notConnected
            RequestsState.KeyForgotten -> Words.keyForgotten
            RequestsState.Connecting -> Words.checking
            is RequestsState.Connected -> Words.connected(state.version) + (state.signedInAs?.let { " Signed in${if (it.isEmpty()) "" else " as $it"}." } ?: "")
            is RequestsState.NeedsSignIn -> when {
                state.signedOut -> Words.signedOut(state.host)
                state.why != null -> state.why
                state.interrupted -> Words.behindSignIn(state.host) + " (The sign-in was not finished: start it again.)"
                else -> Words.behindSignIn(state.host)
            }
            is RequestsState.SigningIn -> Words.finishInBrowser(state.host)
            is RequestsState.Unreachable -> state.why
            RequestsState.CantRead -> "(what is kept about requests can't be read)"
        }
        return listOf(
            "REQUESTS (debug stand-in for the screens)",
            "state: ${state::class.simpleName}",
            "says: $says",
            "on: ${settings.on}",
            "address: ${settings.address}",
            "key: ${requests.keyHint().ifEmpty { "(none)" }}",
            "sign-in service: ${settings.service.ifEmpty { "(none)" }}",
            "signed in as: ${settings.signedIn.ifEmpty { "(nobody)" }}",
            "singles and EPs: ${settings.singles}",
            "last: $last",
        ).joinToString("\n")
    }

    private fun show(what: String) {
        text.text = what
    }

    private companion object {
        val PAGES = setOf("search", "album", "question", "ask", "artist", "add", "way", "offlist", "suggest", "singles")
    }
}
