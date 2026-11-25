package org.medianexus.music.sync

import android.content.Context

/**
 * A release build has keeping in step as [InStepBuild] says, and nothing can change that:
 * the debug build's version of this lets the test rig force it on, to run the whole of it
 * on an emulator while it is switched off.
 */
object DebugStep {
    @Suppress("UNUSED_PARAMETER")
    fun atStart(context: Context) {}
}
