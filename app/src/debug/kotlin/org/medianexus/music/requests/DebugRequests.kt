package org.medianexus.music.requests

import android.content.Context
import java.io.File

/**
 * Debug builds only: requests forced on for the test rig **in a build made without them**
 * ([RequestsBuild]; from 0.6.0 they are in the build, and this changes nothing there), so
 * that what is below the screens can be driven on an emulator against a stand-in Lidarr and
 * sign-in of the rig's own (docs/TESTING.md, "Requests").
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es requests on|off
 *
 * is a file in the app's own folder, read as the app next starts (what exists is decided
 * once, as the account's parts are made). [RequestsDebugActivity] is the debug-only screen
 * that drives what is below the screens, one call at a time.
 *
 * The release build's version of this does nothing and has none of it.
 */
object DebugRequests {
    private fun marker(context: Context) = File(context.filesDir, "debug-requests-on")

    /** As the app starts, before anything of it is made. */
    fun atStart(context: Context) {
        if (marker(context).exists()) RequestsBuild.forTests = true
    }

    fun asked(context: Context, what: String): String = when (what) {
        "on" -> {
            marker(context).writeText("")
            "requests: forced on, from the app's next start"
        }
        "off" -> {
            marker(context).delete()
            "requests: as the build has them, from the app's next start"
        }
        else -> "requests: ${if (RequestsBuild.on) "exist" else "do not exist"} in this run"
    }
}
