package org.medianexus.music.playback

import org.medianexus.music.net.Song

/**
 * A list as a screen drew it: `songs`, the very list the rows were made from, and `shown`, the
 * places in it of the rows drawn, in the order drawn (null: all of it, in its own order).
 * Neither is changed after it is handed over.
 */
class DrawnList(val songs: List<Song>, val shown: IntArray?)

/**
 * Where a screen leaves the list it drew for the player to take, so that what plays is what
 * was on the screen and not the list read again a moment later (a refresh, or the first copy
 * of the library arriving, can have changed it in between). The screens and the playback
 * service run in one process (the service has no `android:process`), so nothing is copied:
 * the screen leaves the object here and sends its number with the PLAY; the service takes it
 * out. Songs queued by hand (an ENQUEUE) come the same way, so that a selection of thousands
 * isn't written out and read back on the main thread. A few at most, each taken once; nothing is written anywhere. A number that isn't here
 * (the app's process was started again in between) is played the way it was before: by
 * reading the list again.
 */
object Drawn {
    private const val MOST = 4
    private var last = 0L
    private val waiting = LinkedHashMap<Long, DrawnList>()

    /** Leaves a list for the player; its number goes with the PLAY. */
    @Synchronized
    fun leave(list: DrawnList): Long {
        val number = ++last
        waiting[number] = list
        while (waiting.size > MOST) waiting.remove(waiting.keys.first())
        return number
    }

    /** Takes the list left under this number, if it is still here. */
    @Synchronized
    fun take(number: Long): DrawnList? = waiting.remove(number)
}
