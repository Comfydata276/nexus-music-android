package org.medianexus.music.downloads

/**
 * Named moments in the last steps of a download (and in a write to the catalogue's log) at
 * which the test rig can have the process killed, to see that whatever it was stopped in the
 * middle of, nothing is listed as downloaded that isn't whole (docs/TESTING.md, "Killed at
 * the worst moments"). Only a debug build's `DebugControl` ever sets [at]; in a release build
 * each of these is a read of a field that is null.
 *
 * The moments, in order: `last-write` (the last of the song has been written to its part
 * file, and nothing synced), `arrived` (the part synced, not yet verified), `verified`,
 * `renamed` (moved to its own name; the folder not yet synced), `placed` (the folder synced;
 * not yet marked), `append-half` (half of the mark's line written to the log),
 * `appended` (the mark on the disk, not yet in memory).
 */
object CrashPoints {
    @Volatile var at: ((String) -> Unit)? = null

    fun reached(point: String) {
        at?.invoke(point)
    }

    /** Words for the debug log (tag NexusDownloads): what each download did, by song id. */
    @Volatile var log: ((String) -> Unit)? = null

    fun say(words: () -> String) {
        log?.invoke(words())
    }
}
