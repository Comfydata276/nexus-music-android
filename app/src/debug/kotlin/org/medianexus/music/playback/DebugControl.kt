package org.medianexus.music.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
 *
 * Downloads and offline mode (see docs/TESTING.md, "Downloads"): download a list by its route
 * key, say how the downloads stand, retry the failed ones, cancel or remove them all; leave
 * this much room spare in the free-space check (megabytes; nothing for the usual half a
 * gigabyte); and switch offline mode by hand:
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es download "album:<id>"
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es downloads say
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es downloads retry|cancel|remove
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es downloads "remove <id>,<id>"
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es spare 999999
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es offline on|off
 *
 * The offline engine's hard cases (docs/TESTING.md, "Hardening"): die, by SIGKILL, at a named
 * moment of a download's last steps (`CrashPoints` lists them; `never` to stop that); like or
 * unlike a song by id, as its heart does. (What each download did is logged by every debug
 * build, tag NexusDownloads.)
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es killat append-half
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es like <song id>
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es unlike <song id>
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es toggle "playlist:<id>"
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es inspect two-hours.flac
 *
 * (`toggle` is a list's download button pressed: it downloads the list, cancels it, or, all
 * of it being downloaded, removes it.)
 *
 * The copy of the library on the phone (see `DebugLibrary`): how it stands; what a copy costs
 * in memory; how fast it is searched, a keystroke at a time (the words given, separated by
 * `|`, or some of the rig's own); or taken off the phone, for timing a first fetch. Dying
 * just after a page of a fetch is kept is `killat library-page-<n>`.
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es library say
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es library memory
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es library search --es queries "masie|lantern"
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es library forget
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es library mixes
 *
 * Keeping in step, which no build has switched on, forced on for the rig; and the shared
 * library made to panic (see `sync/DebugStep.kt` for all of these):
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es instep on|off|say|now
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es core panic
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
        if (intent.getStringExtra("measure") == "availability") {
            // What knowing which songs play offline costs with 5,000 downloads (DebugMeasure).
            val done = goAsync()
            Thread {
                done.resultData = runCatching { org.medianexus.music.downloads.DebugMeasure.availability(context.cacheDir) }.getOrElse { "failed: $it" }
                done.finish()
            }.start()
            return
        }
        intent.getStringExtra("inspect")?.let { name ->
            // What the two looks a download gets cost (docs/TESTING.md, "What Done requires"):
            // the look at its beginning, which every whole original gets, and the reading
            // through, which a conversion gets; the time of each, and the most the app's heap
            // grew by while the second ran. The file is one in the app's cache folder.
            val done = goAsync()
            Thread {
                done.resultData = runCatching {
                    val file = java.io.File(context.cacheDir, name)
                    val runtime = Runtime.getRuntime()
                    System.gc()
                    Thread.sleep(200)
                    val before = runtime.totalMemory() - runtime.freeMemory()
                    val peak = java.util.concurrent.atomic.AtomicLong(before)
                    val watching = java.util.concurrent.atomic.AtomicBoolean(true)
                    val watcher = Thread { while (watching.get()) { peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory()) { a, b -> maxOf(a, b) }; Thread.sleep(5) } }.apply { start() }
                    val check = org.medianexus.music.downloads.Media3AudioCheck.forPhone(context)
                    val first = System.nanoTime()
                    val start = check.begins(file, name.substringAfterLast('.'))
                    val looked = (System.nanoTime() - first) / 1_000
                    val began = System.nanoTime()
                    val seen = check.inspect(file, name.substringAfterLast('.'))
                    val took = (System.nanoTime() - began) / 1_000_000
                    watching.set(false)
                    watcher.join()
                    "${file.length()} bytes: its beginning looked at in $looked µs (it is ${start.container}, sound ${start.audio}, decodable ${start.decodable}); read through in $took ms; the heap grew by at most ${(peak.get() - before) / 1024} KB; it is ${seen.container}, runs ${seen.ranUs / 1000} ms (says ${seen.durationUs / 1000} ms), broken ${seen.broken}, partial ${seen.partial}, decodable ${seen.decodable}"
                }.getOrElse { "failed: $it" }
                done.finish()
            }.start()
            return
        }
        intent.getStringExtra("library")?.let { what ->
            // The copy of the library on the phone (DebugLibrary): how it stands, what it
            // costs in memory, how fast it is searched; or taken away for a first fetch.
            val account = (context.applicationContext as org.medianexus.music.App).graph.accounts.state.value
            val done = goAsync()
            Thread {
                done.resultData = if (account == null) "nobody signed in" else runCatching {
                    when (what) {
                        "memory" -> org.medianexus.music.library.DebugLibrary.memory(account)
                        "search" -> org.medianexus.music.library.DebugLibrary.search(
                            account,
                            intent.getStringExtra("queries")?.split('|')?.filter { it.isNotBlank() }
                                ?: listOf("amber", "lantren", "silver harbour", "zoe brandstrom", "the velvet engines", "midnight station tonight", "mi"),
                        )
                        "forget" -> org.medianexus.music.library.DebugLibrary.forget(account)
                        // What the mixes and Your stats cost to work out (DebugMixes).
                        "mixes" -> org.medianexus.music.library.DebugMixes.run(account)
                        else -> org.medianexus.music.library.DebugLibrary.say(account)
                    }
                }.getOrElse { "failed: $it" }
                done.finish()
            }.start()
            return
        }
        intent.getStringExtra("spare")?.let { megabytes ->
            resultData = org.medianexus.music.downloads.DebugSpare.set(context.applicationContext, megabytes)
            return
        }
        // Keeping in step forced on, an exchange made now, a pin; and the shared library made
        // to panic (see sync/DebugStep.kt).
        (intent.getStringExtra("instep") ?: intent.getStringExtra("core"))?.let { what ->
            val core = intent.hasExtra("core")
            val done = goAsync()
            Thread {
                done.resultData = runCatching {
                    if (core) org.medianexus.music.sync.DebugStep.panic()
                    else org.medianexus.music.sync.DebugStep.asked(context.applicationContext, what, intent.getStringExtra("id"))
                }.getOrElse { "failed: $it" }
                done.finish()
            }.start()
            return
        }
        val graph = (context.applicationContext as org.medianexus.music.App).graph
        // Die (SIGKILL) at a named moment of a download's last steps: see CrashPoints.
        intent.getStringExtra("killat")?.let { point ->
            org.medianexus.music.downloads.CrashPoints.at = if (point.isEmpty() || point == "never") null else { reached ->
                if (reached == point) {
                    android.util.Log.d("NexusDownloads", "killed at $point, as asked")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            resultData = "killed at: $point"
            return
        }
        // A like or an unlike of a song, as its heart makes one.
        (intent.getStringExtra("like") ?: intent.getStringExtra("unlike"))?.let { id ->
            val liked = intent.hasExtra("like")
            graph.accounts.state.value?.likes?.set(org.medianexus.music.net.Song(id = id, title = id), liked)
            resultData = if (liked) "liked $id" else "unliked $id"
            return
        }
        intent.getStringExtra("offline")?.let { on ->
            graph.settings.update { it.copy(offline = on == "on") }
            resultData = "offline mode ${if (on == "on") "on" else "off"}"
            return
        }
        // A list's download button, pressed: downloads it, cancels it, or removes it.
        intent.getStringExtra("toggle")?.let { key ->
            val done = goAsync()
            graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val account = graph.accounts.restored()
                    done.resultData = account?.let { org.medianexus.music.downloads.DownloadPages.toggle(context, it, key) } ?: "nobody signed in"
                } finally {
                    done.finish()
                }
            }
            return
        }
        val download = intent.getStringExtra("download")
        val downloads = intent.getStringExtra("downloads")
        if (download != null || downloads != null) {
            val done = goAsync()
            graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val account = graph.accounts.restored()
                    val engine = account?.downloads
                    // Answered once Media3 has read its list (a cold start reads it first).
                    engine?.let { e -> kotlinx.coroutines.withTimeoutOrNull(10_000) { e.state.first { it.settled } } }
                    done.resultData = when {
                        account == null || engine == null -> "nobody signed in"
                        download != null -> org.medianexus.music.downloads.DownloadPages.listFor(account, download, "Liked songs")
                            ?.let { (list, songs) -> org.medianexus.music.downloads.DownloadPages.said(context, engine.download(songs, list)) } ?: "no such list"
                        downloads == "retry" -> engine.retryFailed()?.let { org.medianexus.music.downloads.DownloadPages.said(context, it) } ?: "nothing failed"
                        downloads == "cancel" -> { engine.cancelAll(); "cancelled" }
                        downloads == "remove" -> { engine.removeAll(); "removed" }
                        downloads?.startsWith("remove ") == true -> { engine.remove(downloads.removePrefix("remove ").split(',').map { it.trim() }); "removed those" }
                        else -> engine.state.value.let { state ->
                            "downloaded ${state.downloaded.size} (${state.bytes} bytes), on the way ${state.active.size} " +
                                "(fetching ${state.active.count { it.state is org.medianexus.music.downloads.SongState.Fetching }}), failed ${state.failed.size}" +
                                (state.failed.firstOrNull()?.let { " (${it.reason})" } ?: "") + ", held ${state.held}, batch ${state.batchDone}+${state.batchFailed}/${state.batchTotal}, " +
                                "lists ${state.collections.joinToString(",") { "${it.key}:${it.songs.size}" }}, settled ${state.settled}, reach ${graph.watch.state.value}, " +
                                "held changes ${account.held.waiting.value}"
                        }
                    }
                } finally {
                    done.finish()
                }
            }
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
