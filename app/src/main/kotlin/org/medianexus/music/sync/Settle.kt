package org.medianexus.music.sync

/** What came of putting this phone's record together with the server's. */
sealed interface Settled {
    /** The two combined. `text` is the record everyone should now hold; `write` says whether
     *  it adds anything to what the server has, and so is to be written back. */
    data class Merged(val text: String, val write: Boolean) : Settled

    /** The server's record is in a layout later than this version knows: `text` is what it
     *  holds, to take in what is understood of it. It is never written. */
    data class Newer(val text: String) : Settled

    /** The server's text is not empty and is not a record: it is left exactly as it is. */
    data object Unreadable : Settled
}

/**
 * The one place the phone's record and the server's are put together: the server's text and
 * this phone's record as text go in, the record to hold and whether to write it come out.
 * Nothing else in the app knows how it is done, so that it can be done by the Windows app's
 * own code compiled into the phone app instead, if that is what is decided (docs/DESIGN.md,
 * "In step with other devices").
 */
fun interface Settler {
    /** `now` in seconds since 1970. */
    fun settle(server: String, local: String, now: Long): Settled
}

/**
 * [Settler] in Kotlin: Windows' `exchange` between reading the comment and writing it
 * ([Merge], [SharedText]), with the two places the phone is more careful than Windows:
 *
 * - **Text that is there and can't be read is left alone.** Windows takes it for "nothing
 *   shared yet" and writes over it. Whatever it is (another app's note, a record damaged on
 *   its way, a layout so new its number doesn't read), the phone is not the one to destroy
 *   it. Only an empty comment is "nothing shared yet".
 * - **Fields this version doesn't know are written back as they were.** Windows drops them
 *   when it writes; the phone doesn't add to that.
 */
object KotlinSettler : Settler {
    override fun settle(server: String, local: String, now: Long): Settled {
        val remote = if (server.isBlank()) Shared() else SharedText.read(server) ?: return Settled.Unreadable
        // Written by a newer version of the app: read what's understood, change nothing.
        if (remote.layout > LAYOUT) return Settled.Newer(server)
        val mine = SharedText.read(local) ?: Shared()
        val merged = Merge.merge(mine, remote, now)
        val write = merged != remote.copy(unknown = emptyList())
        return Settled.Merged(SharedText.write(merged.copy(unknown = remote.unknown)), write)
    }
}
