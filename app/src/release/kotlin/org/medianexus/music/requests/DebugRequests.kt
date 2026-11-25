package org.medianexus.music.requests

import android.content.Context

/**
 * A release build has requests as [RequestsBuild] says, and nothing can change that: the
 * debug build's version of this lets the test rig force them on, to drive what is below the
 * screens on an emulator while there are no screens.
 */
object DebugRequests {
    @Suppress("UNUSED_PARAMETER")
    fun atStart(context: Context) {}
}
