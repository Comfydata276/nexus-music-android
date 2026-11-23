package org.medianexus.music.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Debug builds only: lets the test rig do what a car or the app's screens would, from adb,
 * through a MediaController like theirs. For now, seeking anywhere in the song:
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --el seek 250000
 */
class DebugControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val seek = intent.getLongExtra("seek", -1)
        if (seek < 0) return
        val done = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { controller ->
                controller.seekTo(seek)
                controller.release()
            }
            done.finish()
        }, ContextCompat.getMainExecutor(app))
    }
}
