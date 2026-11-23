package org.medianexus.music.playback

import kotlinx.coroutines.CancellationException

/**
 * Answering something for whoever is signed in, when who that is can change while the
 * answer is being worked out.
 */
object ForAccount {
    /**
     * `now` gives the account signed in (waiting for the saved sign-in to be read), or null
     * for nobody; `isNow` says whether an account is still the one signed in; `work` does the
     * work in that account's own scope, and is cancelled if the account goes.
     *
     * What was found for an account that has since gone is never handed on. What is answered
     * instead depends on who is there now. Nobody: `signedOut`. Someone else: the question is
     * theirs, and is answered for them; and if they too go before it is answered, and the
     * next, it is given up as `superseded`, which must say nothing about who is signed in. It used to be `signedOut` either way, and to a car
     * that is "your sign-in has expired": Media3 keeps that on the car's screen until a
     * folder next opens, so a folder that was still loading when the person signed in put
     * "Sign in" back up over the library the sign-in had just brought.
     */
    suspend fun <A : Any, T> answer(now: suspend () -> A?, isNow: (A) -> Boolean, signedOut: () -> T, superseded: () -> T, work: suspend (A) -> T): T {
        repeat(MOST_ACCOUNTS) {
            val account = now() ?: return signedOut()
            val done = try {
                Result.success(work(account))
            } catch (e: CancellationException) {
                // The request itself was called off, not the account: nothing to answer.
                if (isNow(account)) throw e
                null
            }
            if (done != null && isNow(account)) return done.getOrThrow()
            // The account went while this was being answered: ask again, of whoever is there.
        }
        // Overtaken every time: given up, with an answer that says nothing about who is
        // signed in. ("Not signed in" would be untrue, someone is; and a car keeps it on its
        // screen.)
        return superseded()
    }

    /** Accounts one question is put to in turn before it is given up: nobody signs in and
     *  out that many times while one folder loads. */
    private const val MOST_ACCOUNTS = 3
}
