package org.medianexus.music.requests

import android.content.Context

/**
 * A release build has requests as [RequestsBuild] says, and nothing can change that: the
 * debug build's version of this lets the test rig force them on in a build made without
 * them.
 */
object DebugRequests {
    @Suppress("UNUSED_PARAMETER")
    fun atStart(context: Context) {}
}
