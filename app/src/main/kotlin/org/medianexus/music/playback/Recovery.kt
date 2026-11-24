package org.medianexus.music.playback

import androidx.media3.common.Player

/** The rules for carrying on when something goes wrong, kept apart from the player so they
 *  can be tested on their own. */
object Recovery {
    /**
     * Whether playback carries on when the app is swiped away from the recent apps. Only a
     * pause by the person ends it. It carries on if it is playing; if it is buffering; if it
     * has given way for a moment to a navigation prompt or a call (the player still means
     * to play: Media3's own rule asks "is it playing right now?", which is no for all of
     * these, and stops the music for good); and if it is waiting for the server to come back.
     */
    fun carriesOnWhenSwipedAway(playWhenReady: Boolean, playbackState: Int, waitingForServer: Boolean): Boolean =
        playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING || waitingForServer)

    /**
     * Whether a playback error, met while a song's **downloaded file** was playing, can be
     * laid at that file: it couldn't be read (`local`: the read of the file itself failed),
     * it couldn't be parsed, or what is in it is something this phone has no way to play.
     * Then the download needs a fresh copy, and the song is streamed meanwhile if it can be.
     * Anything else (a decoder that fell
     * over or couldn't be had, the sound output failing) says nothing about the file, and
     * blames nothing: a codec taken away by another app must not cost a good download.
     */
    fun laidAtTheFile(errorCode: Int, local: Boolean): Boolean = local || errorCode in FILE_FAULTS

    /** Whether the error says the phone can't decode what the file holds (as against the
     *  file being unreadable): the same file fetched again would fail the same way, so what
     *  is fetched next is the server's conversion. */
    fun beyondThePhone(errorCode: Int): Boolean =
        errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES

    private val FILE_FAULTS = setOf(
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        // What the file holds, not how it was played: the same file fails the same way
        // every time on this phone.
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )

    /**
     * Whether the song at the cursor is to be **passed over at once** because the server is
     * out of reach: it is known to be out of reach (`known`: a check has failed, not merely
     * "not heard from yet"), the song is not on the phone, the player means to play it, and
     * it has not been heard since it came to the cursor. A song that was playing when the
     * server went is not this: it stays where it is and carries on when the server is back
     * ("Reconnecting…"). A song chosen when there is no server to send it has nothing to
     * reconnect to, and waiting on it kept the car silent for as long as the stream's own
     * tries took.
     */
    fun passesOverOutOfReach(known: Boolean, onPhone: Boolean, heard: Boolean, meansToPlay: Boolean): Boolean =
        known && !onPhone && !heard && meansToPlay

    /** The place of the next entry after `current`, of `count`, that plays with no server;
     *  null if none does. */
    fun nextThatPlays(current: Int, count: Int, plays: (Int) -> Boolean): Int? = (current + 1 until count).firstOrNull(plays)

    /**
     * Streaming quality was changed in Settings: whether the song at the cursor is to be
     * opened again, from where it is, so that it plays at the new quality straight away (as
     * Windows' `reload_current` does). Not if nothing is open (the next song opened is at the
     * new quality anyway), if it plays from its download (which is what it is whatever the
     * quality), if it is a radio stream, or if it is waiting for the server to come back
     * (the next try opens it at the new quality).
     */
    fun reopensForQuality(playbackState: Int, downloaded: Boolean, radio: Boolean, waitingForServer: Boolean): Boolean =
        (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) && !downloaded && !radio && !waitingForServer

    /**
     * Whether to stop waiting for the server: it has been out of reach for `waitedMs`, and
     * the next try would be `nextWaitMs` from now. Waiting ends with the last try that still
     * falls inside [GIVE_UP_MILLIS], which is how long the service keeps its foreground
     * standing with nothing playing (Media3's `DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS`): a try
     * after that could bring the music back in a service Android no longer lets be heard.
     */
    fun givesUp(waitedMs: Long, nextWaitMs: Long): Boolean = waitedMs + nextWaitMs > GIVE_UP_MILLIS

    const val GIVE_UP_MILLIS = 10 * 60 * 1000L

    /**
     * A try at the server that was put off until later: which account's turn at the player
     * it was for, and which entry of the queue. When its time comes it only goes ahead if
     * that is all still so: the same account, the same song still the one that's stuck. A
     * retry for a song the person has since skipped, or for an account that has signed out,
     * is dropped; it used to "prepare" whatever was there by then.
     */
    data class Retry(val generation: Long, val uid: Long) {
        fun stillStands(generation: Long?, uid: Long?, failed: Boolean): Boolean =
            failed && generation == this.generation && uid == this.uid
    }

    /**
     * How long the server has been out of reach, for one account's turn at the player and one
     * entry of its queue.
     *
     * Counted from when things were last known to be going well for that entry, not from the
     * first error: the player rides out a bad connection by itself for a while (four tries,
     * each of which may wait ten seconds for a read that has stalled) before it says anything
     * has failed, and all of that is time the server was out of reach. And it belongs to the
     * entry and the account: another song, or another account's server, starts from nothing.
     * It used to be one number for the life of the service, set at the first error and never
     * taken off when the song or the account changed, so the song after an outage could be
     * given up on at its first hiccup.
     */
    class Outage {
        private var generation = 0L
        private var uid = 0L
        private var since = -1L

        /**
         * Things are going well for this entry at `now` (it is playing, it has just become
         * the song playing, or Play has just been pressed for it): anything that goes wrong
         * from here is counted from here.
         */
        fun well(generation: Long, uid: Long, now: Long) {
            this.generation = generation
            this.uid = uid
            since = now
        }

        /**
         * The server couldn't be reached for this entry at `now`: how long it has been out of
         * reach for it. An entry or an account this has not been counting for starts afresh.
         */
        fun waited(generation: Long, uid: Long, now: Long): Long {
            if (since < 0 || generation != this.generation || uid != this.uid) well(generation, uid, now)
            return (now - since).coerceAtLeast(0)
        }

        /**
         * The song stopped playing at `now`, having played since `since`. If it had been
         * playing steadily, it was going well until this moment, and what follows is counted
         * from here. A moment of sound between two stalls is not that: a link that delivers
         * a little now and then would otherwise never be out of reach for long, and would
         * be tried for ever.
         */
        fun played(generation: Long, uid: Long, since: Long, now: Long) {
            if (now - since >= STEADY_MILLIS || this.since < 0 || generation != this.generation || uid != this.uid) well(generation, uid, now)
        }

        /** Nothing is being counted any more (the account has gone). */
        fun clear() {
            since = -1
        }
    }

    /** How long a song has to have played without a break to count as going well. */
    const val STEADY_MILLIS = 30_000L

    /** What to do when the time allowed for a start runs out (see [whenHoldRunsOut]). */
    enum class Held {
        /** Something asked for is still being looked up: look again when it has been. */
        WAIT,

        /** The player is playing, or about to, and Media3 has not taken the foreground over
         *  with its own notification: ask it to, and keep what is held meanwhile. */
        HAND_OVER,

        /** Nothing came of it: leave the foreground. */
        STAND_DOWN,
    }

    /**
     * The service entered the foreground with a notification of its own, for a start, and
     * the time allowed for that start has run out with Media3's notification still not in its
     * place. `looking` is how many requests are still being looked up; `busy` whether the
     * player means to play and is playing, getting ready, or waiting for the server.
     *
     * That the player looks busy is not taken to mean Media3 has the foreground: it used to
     * be, and the temporary notification was then simply forgotten, whoever held it.
     */
    fun whenHoldRunsOut(looking: Int, busy: Boolean): Held = when {
        looking > 0 -> Held.WAIT
        busy -> Held.HAND_OVER
        else -> Held.STAND_DOWN
    }
}
