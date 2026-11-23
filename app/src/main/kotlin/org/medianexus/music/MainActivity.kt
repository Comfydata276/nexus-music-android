package org.medianexus.music

import android.graphics.Color
import android.os.Build
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No grey wash over the three-button bar: the bottom bar is under it.
            window.isNavigationBarContrastEnforced = false
        }
        val graph = graph
        setContent { Root(graph) }
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
                    SignInScreen(graph.session, settings.server, settings.username)
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
