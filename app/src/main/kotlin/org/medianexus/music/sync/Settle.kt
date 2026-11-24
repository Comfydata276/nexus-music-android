package org.medianexus.music.sync

/** What came of putting this phone's record together with the server's. */
sealed interface Settled {
    /** The two combined. `text` is the record everyone should now hold; `write` says whether
     *  it adds anything to what the server has, and so is to be written back. */
    data class Merged(val text: String, val write: Boolean) : Settled

    /** The server's record holds more than this version could write back as it found it: a
     *  layout later than it knows, or a field it doesn't know inside a list's choices or a
     *  note. `text` is the server's own, to take in what is understood of it. It is never
     *  written. */
    data class ReadOnly(val text: String, val laterLayout: Boolean) : Settled

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
 *   it. Only the empty text is "nothing shared yet": a comment of spaces is somebody's.
 * - **The phone writes only if it can write back everything it read.** Fields it doesn't
 *   know at the top of the record are carried, exactly as they were written (Windows drops
 *   them). One inside a list's choices or a note can't be carried: then the record is read
 *   and not written, as a later layout is.
 */
object KotlinSettler : Settler {
    override fun settle(server: String, local: String, now: Long): Settled {
        val remote = if (server.isEmpty()) Shared() else SharedText.read(server) ?: return Settled.Unreadable
        // Written by a newer version of the app: read what's understood, change nothing.
        if (remote.layout > LAYOUT) return Settled.ReadOnly(server, laterLayout = true)
        if (remote.more) return Settled.ReadOnly(server, laterLayout = false)
        val mine = SharedText.read(local) ?: Shared()
        val merged = Merge.merge(mine, remote, now)
        val write = merged != remote.copy(unknown = emptyList(), more = false)
        return Settled.Merged(SharedText.write(merged.copy(unknown = remote.unknown)), write)
    }
}
