package org.medianexus.music

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.medianexus.music.ui.shell.NoticeList
import org.medianexus.music.ui.shell.Shell
import org.medianexus.music.ui.signin.SignInScreen
import org.medianexus.music.ui.theme.NexusTheme
import org.medianexus.music.ui.theme.Theme
import org.medianexus.music.ui.theme.themeNamed

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app draws under the system bars, which are see-through, so they show the
        // theme's own colours. Dark only: light icons on them.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // No grey wash over the three-button bar: the bottom bar is under it.
        window.isNavigationBarContrastEnforced = false
        val graph = graph
        setContent { Root(graph) }
        // The screens talk to the player only while they can be seen, and only once someone
        // is signed in: before that there's nothing it could play, and binding to it would
        // start the service (and its player) for nothing. It plays on either way.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                graph.accounts.state.collect { account -> if (account != null) graph.player.connect() else graph.player.release() }
            }
        }
        // (While in front, a server that couldn't be reached is looked for now and then, so
        // what failed comes back by itself when it's there again: ServerWatch does that
        // itself, the app being in front being one of its reasons to know, and asks at once
        // when the screens come to the front: see onStart.)
    }

    override fun onStart() {
        super.onStart()
        // On screen: downloads waiting may now be carried on the way Android allows only an
        // app on screen to ask for (see DownloadKeeper).
        org.medianexus.music.downloads.DownloadKeeper.visible = true
        // In front: a server out of reach is asked after now, not at its next turn; and
        // downloads Android paused with a job it stopped may run again (an app on screen may
        // download, and is asked for a job of the kind only it may have).
        graph.checkIfDown()
        // And what the person's other devices have changed meanwhile is picked up.
        graph.cameToFront()
        graph.accounts.state.value?.downloads?.let { downloads ->
            downloads.stoppedByAndroid(false)
            downloads.refresh()
        }
    }

    override fun onStop() {
        org.medianexus.music.downloads.DownloadKeeper.visible = false
        graph.player.release()
        super.onStop()
    }

    override fun onDestroy() {
        // Closed for good (not rebuilt) with Android's local-network question up: it will
        // never be answered here, and a sign-in waiting on it lets go of its password.
        if (isFinishing) graph.localAccess.abandoned()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // The local-network permission may have been given or taken away in the phone's
        // settings meanwhile.
        graph.localAccess.recheck()
    }
}

@Composable
private fun Root(graph: Graph) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val colors = remember(settings.theme) { themeNamed(settings.theme) }
    NexusTheme(colors) {
        val session by graph.session.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(Theme.colors.base)) {
            when (session) {
                // A moment at start-up while the saved sign-in is read: the bare background,
                // as the window had.
                SessionState.Restoring -> Unit
                SessionState.SignedOut -> {
                    SignInScreen(graph.session, graph.localAccess, settings.server, settings.username)
                    // Such as the settings having been found damaged, which is likely why
                    // the sign-in is being asked for.
                    Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)) {
                        NoticeList(graph)
                    }
                }
                is SessionState.SignedIn -> Shell(graph)
            }
        }
    }
}
