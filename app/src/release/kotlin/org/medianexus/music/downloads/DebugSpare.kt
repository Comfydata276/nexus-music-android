package org.medianexus.music.downloads

import android.content.Context

/** A release build always leaves the usual room spare (see the debug build's version). */
object DebugSpare {
    @Suppress("UNUSED_PARAMETER")
    fun spare(context: Context): Long? = null
}
