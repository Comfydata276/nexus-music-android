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
 * Debug builds only: **a stand-in for the screens of requests, which are still to come.**
 * It shows where the connection to Lidarr stands, in the words the real screens will show,
 * and does what they will do, each as one call of the surface they will use
 * ([Requests]): nothing here reaches past that surface.
 *
 *     adb shell am start -n org.medianexus.music/.requests.RequestsDebugActivity
 *     ... --es do on|off|check|signin|cancel|signout
 *     ... --es do connect --es address http://10.0.2.2:8686 --es key <key>
 *
 * or the buttons. "Sign in" opens the browser over this screen, as the real one will
 * ([BrowserTab]); coming back to this screen with the browser's part unfinished gives the
 * sign-in up ([Requests.cameBack]). Needs requests forced on first ([DebugRequests]).
 */
class RequestsDebugActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var text: TextView
    private var showing: Job? = null
    private var last = ""

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
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val what = intent?.getStringExtra("do") ?: return
        act(what, intent.getStringExtra("address"), intent.getStringExtra("key"))
    }

    override fun onStart() {
        super.onStart()
        showing = scope.launch {
            // (The account may still be coming up.)
            graph.accounts.state.collectLatest { account ->
                val requests = account?.requests
                if (requests == null) {
                    show("no requests here: ${if (account == null) "nobody is signed in" else "they are not forced on (DebugRequests)"}")
                    return@collectLatest
                }
                requests.state.collectLatest { state -> show(describe(requests, state)) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        showing?.cancel()
    }

    override fun onResume() {
        super.onResume()
        // (As the real screen will: the browser's part may have been left unfinished.)
        requests?.cameBack()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun act(what: String, address: String?, key: String?) {
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
            "last: $last",
        ).joinToString("\n")
    }

    private fun show(what: String) {
        text.text = what
    }
}
