package org.medianexus.music.link

import android.content.Context

/**
 * A release build has linking as [LinkBuild] says, and nothing can change that: the debug
 * build's version of this lets the test rig force it on, to walk it on an emulator.
 */
object DebugLink {
    @Suppress("UNUSED_PARAMETER")
    fun atStart(context: Context) {}
}
