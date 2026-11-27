package org.medianexus.music.requests

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.medianexus.music.core.CoreHttp

/**
 * **The diagnostics line about the last attempt to connect requests or sign in for them**:
 * one line, replaced each time (`Diagnostics.replacing`), so that a report sent after
 * something went wrong says where it went wrong. Made only of fixed words, numbers of
 * milliseconds and seconds, and yes or no: **never an address, a key, a token, a code or a
 * state**, and never a sentence from Lidarr, the library or the sign-in service (those can
 * name a host or quote a service's own words).
 *
 *     requests: last connect or sign-in: finishing the sign-in, answered 400, 812 ms;
 *     browser came back after 74 s, app in front: no; kept running: yes; address: a name;
 *     connected over IPv4; last request: the name gave 2 IPv4 and 2 IPv6, IPv4 answering
 *
 * Every check writes it, whatever asked for it (Connect, "Try again", the app starting,
 * requests switched on), and so does every end of a sign-in.
 */
internal object Trace {
    const val PREFIX = "requests: last connect or sign-in:"

    /** Where an attempt ended. */
    enum class Step(val words: String) {
        CHECK("checking Lidarr"),
        FIND("finding the sign-in"),
        BROWSER("waiting for the browser"),
        FINISH("finishing the sign-in"),
        LOOK("first look signed in"),
    }

    /** What the browser did, for an attempt that sent the person to it. */
    class Browser(
        /** Seconds from the browser being sent to the sign-in page to its coming back; null
         *  if it never came back. */
        val cameBackAfter: Long?,
        /** Whether the app's own screen was in front when the browser came back. */
        val inFront: Boolean?,
        /** Whether the app was being kept running ([SignInService]) then, or at the end of
         *  the wait if nobody came back. */
        val keptRunning: Boolean,
    )

    /** A name, or which kind of number, Lidarr's address is on (its host, as the app's
     *  client reads the address as it is used). Never the address. */
    fun addressKind(address: String?): String = addressKindOf(address?.trim()?.toHttpUrlOrNull()?.host)

    private fun addressKindOf(host: String?): String = when {
        host.isNullOrEmpty() -> "none"
        host.contains(':') -> "an IPv6 number"
        host.all { it.isDigit() || it == '.' } -> "an IPv4 number"
        else -> "a name"
    }

    /** What became of the last request to Lidarr or its sign-in since `since` (a
     *  [System.nanoTime]), if one ended since then. */
    fun last(since: Long): CoreHttp.LastOnLidarr? = CoreHttp.lastOnLidarr?.takeIf { it.at - since >= 0 }

    fun line(step: Step, ended: String, millis: Long, browser: Browser?, addressKind: String, family: String?, way: String? = null): String = buildString {
        append(PREFIX).append(' ').append(step.words).append(", ").append(ended).append(", ").append(millis).append(" ms")
        if (browser != null) {
            append("; browser ")
            if (browser.cameBackAfter == null) {
                append("never came back")
            } else {
                append("came back after ").append(browser.cameBackAfter).append(" s, app in front: ").append(if (browser.inFront == true) "yes" else "no")
            }
            append("; kept running: ").append(if (browser.keptRunning) "yes" else "no")
        }
        append("; address: ").append(addressKind)
        append("; connected over ").append(family ?: "nothing yet")
        // (How far the last request's connection got: CoreHttp's `Way`.)
        if (!way.isNullOrEmpty()) append("; last request: ").append(way)
    }
}
