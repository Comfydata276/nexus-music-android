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
}
