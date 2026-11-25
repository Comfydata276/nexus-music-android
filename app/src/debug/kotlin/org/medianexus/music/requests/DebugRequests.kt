package org.medianexus.music.requests

import android.content.Context
import java.io.File

/**
 * Debug builds only: requests, which no build has switched on yet ([RequestsBuild]),
 * forced on for the test rig, so that what is below the screens can be driven on an emulator
 * against a stand-in Lidarr and sign-in of the rig's own (docs/TESTING.md, "Requests").
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es requests on|off
 *
 * is a file in the app's own folder, read as the app next starts (what exists is decided
 * once, as the account's parts are made). With them on, [RequestsDebugActivity] is the
 * debug-only screen that stands in for the screens still to come.
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
