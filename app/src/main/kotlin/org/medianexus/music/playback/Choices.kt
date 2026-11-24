package org.medianexus.music.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * The one order of the person's choices (M3b fixes 2, B1). Each choice of music (a list
 * played, songs queued, a car's or a spoken start) takes its place in it at the moment it is
 * made, on the phone or in the car; so do a pause, a stop, the sleep timer's pause, the sound
 * taken away, and offline mode switched on. Whatever acts later than it was asked for (a
 * lookup's result, a start being prepared, a station's rejoin, autoplay's songs) notes its
 * place when asked and acts only if nothing has taken a later one: the newest choice wins, and
 * a stop beats everything before it. The screens and the playback service run in one process
 * (the service has no `android:process`), so this is the same order for both.
 */
object Choices {
    private val last = AtomicLong()

    /** A choice takes its place: its number. */
    fun take(): Long = last.incrementAndGet()

    /** The place of the newest choice. */
    val latest: Long get() = last.get()

    /** Whether nothing has taken a place after `place`. */
    fun stands(place: Long): Boolean = last.get() == place
}
