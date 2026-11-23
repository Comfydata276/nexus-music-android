package org.medianexus.music.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import java.io.File
import org.medianexus.music.Graph
import org.medianexus.music.graph
import org.medianexus.music.store.accountDirs
import org.medianexus.music.store.accountKey

/**
 * A headset's or a car's Play button with the app not running. Media3's receiver starts the
 * playback service "in the foreground", which is a promise to the system that playback is
 * about to begin; an app that makes it and then plays nothing is killed for it. So the
 * promise is only made when there is something to resume: someone signed in, and a queue
 * saved for them. That is all a button resumes; it never goes looking for something to play.
 */
@OptIn(UnstableApi::class)
class PlayButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val settings = try {
            context.graph.settings.value
        } catch (_: UninitializedPropertyAccessException) {
            // The app's objects aren't made yet (they are, by the time a receiver runs; but
            // if not, the service works it out for itself and ends cleanly if need be).
            return true
        }
        val dirs = accountDirs(context.filesDir, context.cacheDir, accountKey(settings.server, settings.username))
        val worth = Resumption.possible(settings.server, settings.username, Graph.passwordFile(context), QueueFiles.queueFile(dirs.data))
        context.graph.diagnostics.note("a media button with the player not running: ${if (worth) "starting it" else "nothing to resume, so not started"}", now = true)
        if (org.medianexus.music.BuildConfig.DEBUG) android.util.Log.d("NexusPlayback", "play button with the app not running: ${if (worth) "starting the player" else "nothing to resume, not started"}")
        return worth
    }

    /** Android would not let the player be started from here (Android 12 and later). Media3
     *  catches it; it is noted, for Settings' Diagnostics, and nothing is started. */
    @androidx.annotation.RequiresApi(31)
    override fun onForegroundServiceStartNotAllowedException(context: Context, intent: Intent, e: android.app.ForegroundServiceStartNotAllowedException) {
        runCatching { context.graph.diagnostics.note("a media button with the player not running: Android would not let it be started from the background", now = true) }
    }
}

/** Whether "play" with nothing loaded has anything to pick up, from what is on disk alone. */
object Resumption {
    /**
     * Someone is signed in (an address, a name, and a saved password to go with them), and
     * a queue was saved for them. A few looks at the file system, quick enough for the main
     * thread in a broadcast receiver. It can be wrong in the other direction only (a queue
     * file that turns out to be damaged), and the service ends cleanly when it is.
     */
    fun possible(server: String, username: String, password: File, queue: File): Boolean =
        server.isNotEmpty() && username.isNotEmpty() && password.isFile && password.length() > 0 && queue.isFile && queue.length() > 2
}
