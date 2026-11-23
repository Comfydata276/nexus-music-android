package org.medianexus.music.playback

/**
 * The requests that are meant to end in playing, from the moment each arrives: a song chosen
 * in the car, a spoken request, Play with nothing loaded. Each has the number its start was
 * given as it arrived (`QueuePlayer.beginStart`), and that one ticket goes with it all the
 * way: through whatever has to be looked up, the hand-over to the session, and the "make
 * ready" and "play" that Media3 sends after it.
 *
 * Two things are kept here, both plain bookkeeping so they can be tested on their own.
 *
 * **Which requests are still being worked on.** The service is in the foreground from the
 * moment a request arrives (Android gives a command from a car only a short allowance to
 * enter it, and looking things up can use all of it), and stays there while any request is
 * still on its way: see the service's `deadline`.
 *
 * **Which sender's request came to nothing.** Media3 follows a hand-over with "make ready"
 * and "play", whatever became of the hand-over: a car's are sent in the same breath as its
 * items reach the player, and a Media3 controller's (the phone's own voice request) are three
 * commands, of which the last two arrive even when the first failed. So when a request turns
 * out to have been overtaken, or fails, its sender is noted, and what that sender sends next
 * as part of the same request does nothing. Media3 says when a sender's run of commands is
 * over (`onPlayerInteractionFinished`), and the note is taken off then: the sender's next
 * Play is a Play. `C` is Media3's `ControllerInfo` in the service.
 */
class PlayRequests<C : Any> {
    /** One request: who sent it, and the number of the start it is (null for "carry on",
     *  which is not a start: it overtakes nothing, and gives way to any). */
    class Request<C : Any>(val from: C, val start: Long?)

    private val working = HashSet<Request<C>>()
    private val dropped = HashSet<C>()

    /** A request has arrived from `from`. */
    fun begin(from: C, start: Long?): Request<C> {
        // Whatever this sender asked for before is over: this is what it wants now.
        dropped.remove(from)
        return Request(from, start).also { working += it }
    }

    /** The request has been handed to the session, or has failed: nothing more is being
     *  looked up for it. */
    fun end(request: Request<C>) {
        working.remove(request)
    }

    /** How many requests are still being looked up. */
    val waiting: Int get() = working.size

    /** `from`'s request was overtaken or failed, and Media3 will go on to send "make ready"
     *  and "play" for it: they are to do nothing. */
    fun drop(from: C) {
        dropped += from
    }

    /** Whether what `from` is sending now belongs to a request of its that came to nothing. */
    fun isDropped(from: C?): Boolean = from != null && from in dropped

    /** Media3 has carried out everything `from` sent in one go. */
    fun finished(from: C) {
        dropped.remove(from)
    }

    /** `from` has gone. */
    fun gone(from: C) {
        dropped.remove(from)
        working.removeAll { it.from == from }
    }
}
