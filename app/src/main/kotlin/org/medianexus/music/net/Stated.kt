package org.medianexus.music.net

/**
 * A playlist as the server stated it, for the one use where "the server didn't say" must not
 * be taken for "there is none": keeping in step with the person's other devices
 * (sync/InStep.kt). What the answer left out is null. Navidrome leaves a playlist's comment
 * and owner out of its answers for clients it has been told to treat as minimal; read as
 * empty, a comment that wasn't sent would look like a record with nothing in it. It also
 * leaves a comment out of every answer when the comment is empty, so a null comment means
 * "not said" only where the owner is null too.
 */
data class StatedPlaylist(val id: String, val name: String, val owner: String?, val comment: String?)

/** An answer, and the server's own clock as it gave it (seconds since 1970, from the
 *  answer's `Date`); null if it gave none that can be read. */
data class Timed<T>(val value: T, val serverTime: Long?)
