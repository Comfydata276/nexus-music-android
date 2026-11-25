package org.medianexus.music.sync

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.medianexus.music.App
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException

/**
 * Debug builds only: keeping in step, which no build has switched on yet
 * ([InStepBuild]), forced on for the test rig, so that the whole of it can be run on an
 * emulator against a server and PCs of the rig's own (docs/TESTING.md, "In step with other
 * devices"). Through `DebugControl`:
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es instep on|off
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es instep say
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es instep now
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es instep pin|unpin --es id <playlist>
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es core panic
 *
 * `on` and `off` are a file in the app's own folder, read as the app next starts (what
 * exists is decided once, as the account's parts are made). `now` makes one exchange and
 * says how it ended; `pin` is the pin button of a playlist. `core panic` makes the shared
 * library panic and says what reached this side, and what the library answered afterwards.
 * Dying just after the server has taken the phone's write, before the phone has its answer,
 * is `--es killat step-sent`.
 *
 * The release build's version of this does nothing and has none of it.
 */
object DebugStep {
    private fun marker(context: Context) = File(context.filesDir, "debug-instep-on")

    /** As the app starts, before anything of it is made. */
    fun atStart(context: Context) {
        if (marker(context).exists()) InStepBuild.forTests = true
    }

    fun asked(context: Context, what: String, id: String?): String {
        val graph = (context.applicationContext as App).graph
        val account = graph.accounts.state.value
        return when (what) {
            "on" -> {
                marker(context).writeText("")
                "keeping in step: forced on, from the app's next start"
            }
            "off" -> {
                marker(context).delete()
                "keeping in step: as the build has it, from the app's next start"
            }
            "now" -> when {
                account == null -> "nobody signed in"
                else -> account.step?.let { step -> runBlocking { withTimeout(8_000) { step.exchange() } }.name }
                    ?: "keeping in step doesn't exist in this run of the app"
            }
            "pin", "unpin" -> {
                graph.choose(Chosen.Did.Pin(id ?: return "which playlist? (--es id <id>)", on = what == "pin"))
                "asked"
            }
            else -> graph.settings.value.let { s ->
                "exists ${InStepBuild.on}, setting ${s.sync}, signed in ${account != null}, clock wrong ${account?.step?.clockWrong?.value}, " +
                    "pins ${s.pinnedPlaylists}, theme ${s.theme}, lists ${s.lists.map { (key, prefs) -> "$key:${prefs.sort}${if (prefs.descending) "v" else "^"}${if (prefs.shuffle) "s" else ""}" }}"
            }
        }
    }

    /** The library made to panic: what reached this side, and that it answers afterwards. */
    fun panic(): String {
        val words = "a panic the test rig asked for"
        val reached = try {
            Core.stopForTheTest(words)
            "nothing was thrown"
        } catch (stopped: CoreException) {
            "CoreException: ${stopped.message}"
        }
        return "$reached; and then the library answered \"${Core.echo("still here")}\""
    }
}
