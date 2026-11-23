package org.medianexus.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/** A release build taps nothing: the debug build's version writes what is played to a file. */
@OptIn(UnstableApi::class)
object DebugAudio {
    @Suppress("UNUSED_PARAMETER")
    fun processors(context: Context): Array<AudioProcessor> = emptyArray()
}
