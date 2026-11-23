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
 * pressing Play (or Pause) from wherever the app is, which with the app in the background is
 * a Play Android may refuse (docs/TESTING.md, "A Play that Android refuses"):
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es press play
 *
 * logging whenever the main thread is held up for more than two frames (tag NexusStall):
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es stalls start
 *
 * and capturing what is played to a WAV file in the app's files (see [DebugAudio]):
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es capture start --es name sweep
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es capture stop
 *     adb exec-out run-as org.medianexus.music cat files/capture/sweep.wav > sweep.wav
 *
 * (`scripts/emu capture start|stop|pull` does these.)
 *
 * And naming a caller to be let in as a known host, by its package name and the SHA-256 of
 * its signing certificate (see [DebugHosts]; `scripts/carcheck --known-host` does this for
 * the car probe), or with nothing, forgetting them:
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es host "org.medianexus.carprobe <sha256>"
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es host ""
 */
class DebugControl : BroadcastReceiver() {
    private companion object {
        /** Two frames at 60 a second. */
        const val STALL_MILLIS = 33L
    }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("host")?.let { entry ->
            resultData = DebugHosts.set(context.applicationContext, entry)
            return
        }
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
        when (intent.getStringExtra("stalls")) {
            // "--es stalls start": from now on, log every time the main thread is held for
            // longer than two frames (tag NexusStall), with what held it.
            "start" -> {
                var began = 0L
                var what = ""
                android.os.Looper.getMainLooper().setMessageLogging { line ->
                    if (line.startsWith(">")) {
                        began = android.os.SystemClock.uptimeMillis()
                        what = line
                    } else {
                        val took = android.os.SystemClock.uptimeMillis() - began
                        if (took > STALL_MILLIS) android.util.Log.w("NexusStall", "main thread held for $took ms by ${what.substringAfter("Dispatching to ").take(160)}")
                    }
                }
                resultData = "logging main-thread stalls over $STALL_MILLIS ms"
                return
            }
            "stop" -> {
                android.os.Looper.getMainLooper().setMessageLogging(null)
                return
            }
        }
        val seek = intent.getLongExtra("seek", -1)
        val item = intent.getIntExtra("item", -1)
        val say = intent.getStringExtra("say")
        val press = intent.getStringExtra("press")
        if (seek < 0 && item < 0 && say == null && press == null) return
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
                // "--es press play": Play, as the app's screens press it. Sent while the app
                // is in the background and its service out of the foreground, it is a Play
                // with nothing of the person's behind it: the one Android refuses.
                when (press) {
                    "play" -> controller.play()
                    "pause" -> controller.pause()
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
