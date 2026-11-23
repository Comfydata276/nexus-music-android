package org.medianexus.music.playback

import org.medianexus.music.net.ApiError

/**
 * The kinds of thing that can go wrong answering a car, each of which the car is told in one
 * fixed sentence of the app's own. Which kind it is comes from what kind of error it was and
 * the server's error code, never from the error's message: for a refusal that message holds
 * what the server itself said, and a car's screen, the media session's error and so every
 * app with a controller are not where that goes (docs/DESIGN.md, "What a server says is the
 * person's").
 */
enum class CarTrouble {
    /** Android 17, a server on the home network, and no permission to reach it. */
    LOCAL_NETWORK,

    /** The server could not be reached. */
    UNREACHABLE,

    /** The server turned the sign-in away. */
    SIGN_IN,

    /** The server says the account may not do that. */
    NOT_ALLOWED,

    /** The server has no such thing (any more). */
    NOT_FOUND,

    /** The server refused, for a reason of its own. */
    REFUSED,

    /** What answered wasn't understood as a music server's answer. */
    NOT_UNDERSTOOD;

    companion object {
        /** `blocked`: the phone is not allowed to reach the home network, where the server is. */
        fun of(error: ApiError, blocked: Boolean): CarTrouble = when (error) {
            is ApiError.Network -> if (blocked) LOCAL_NETWORK else UNREACHABLE
            is ApiError.NotSubsonic -> NOT_UNDERSTOOD
            is ApiError.Server -> when {
                error.signInRefused -> SIGN_IN
                error.code == 50L -> NOT_ALLOWED
                error.code == 70L -> NOT_FOUND
                else -> REFUSED
            }
        }
    }
}
