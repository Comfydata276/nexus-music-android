package org.medianexus.music.playback

import android.content.Context

/**
 * A release build knows the hosts on Google's list and no others: the debug build's version
 * of this lets the test rig name one more, its car probe, to prove that a caller is let in
 * by its certificate.
 */
object DebugHosts {
    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context) {}

    fun listed(): Map<String, Set<String>> = emptyMap()
}
