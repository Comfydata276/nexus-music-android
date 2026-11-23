package org.medianexus.music.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Debug builds only: lets the test rig do what a car or the app's screens would, from adb,
 * through a MediaController like theirs. Seeking anywhere in the song:
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --el seek 250000
 *
 * jumping to an entry of the queue, and saying where the player is:
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --ei item 4
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es say state
 *
 * and capturing what is played to a WAV file in the app's files (see [DebugAudio]):
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es capture start --es name sweep
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es capture stop
 *     adb exec-out run-as org.medianexus.music cat files/capture/sweep.wav > sweep.wav
 *
 * (`scripts/emu capture start|stop|pull` does these.)
 */
class DebugControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("capture")) {
            "start" -> {
                val name = intent.getStringExtra("name")?.filter { it.isLetterOrDigit() || it in "-_" }?.ifEmpty { null } ?: "capture"
                DebugAudio.start(File(context.filesDir, "capture"), name)
                resultData = "capturing to files/capture/$name.wav"
                return
            }
            "stop" -> {
                resultData = DebugAudio.stop().joinToString(" ") { "files/capture/${it.name}" }
                return
            }
        }
        val seek = intent.getLongExtra("seek", -1)
        val item = intent.getIntExtra("item", -1)
        val say = intent.getStringExtra("say")
        if (seek < 0 && item < 0 && say == null) return
        val done = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { controller ->
                when {
                    // "--ei item 4": to the fifth entry of the queue ("--ei item 9999": the last).
                    item >= 0 -> controller.seekTo(item.coerceAtMost(controller.mediaItemCount - 1).coerceAtLeast(0), seek.coerceAtLeast(0))
                    seek >= 0 -> controller.seekTo(seek)
                }
                // "--es say state": where the player is, as the result of the broadcast.
                if (say != null) {
                    done.resultData = "item ${controller.currentMediaItemIndex + 1} of ${controller.mediaItemCount}, " +
                        "\"${controller.mediaMetadata.title}\", at ${controller.currentPosition} of ${controller.duration} ms, " +
                        "state ${controller.playbackState}, playWhenReady ${controller.playWhenReady}, playing ${controller.isPlaying}, " +
                        "shuffle ${controller.shuffleModeEnabled}, repeat ${controller.repeatMode}, seekable ${controller.isCurrentMediaItemSeekable}, " +
                        "error ${controller.playerError?.message}"
                }
                controller.release()
            }
            done.finish()
        }, ContextCompat.getMainExecutor(app))
    }
}
