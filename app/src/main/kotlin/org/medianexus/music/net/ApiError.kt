package org.medianexus.music.net

/**
 * Why a call to the server failed. The message is what a person is shown, worded as the
 * Windows app words it.
 */
sealed class ApiError(message: String) : Exception(message) {
    /** The server could not be reached at all. */
    class Network(val detail: String) : ApiError("Can't reach the server. $detail")

    /** The server answered, but not with a Subsonic response (wrong URL, proxy page, …). */
    class NotSubsonic(val detail: String) :
        ApiError("That address didn't answer like a Navidrome server. $detail")

    /** The server understood the request and refused it. */
    class Server(val code: Long, val serverMessage: String) : ApiError(
        when (code) {
            40L -> "Wrong username or password."
            50L -> "This account isn't allowed to do that."
            70L -> "The server couldn't find that item."
            else -> "The server reported: $serverMessage"
        },
    )

    /** Whether the server turned the sign-in itself away (a wrong or unusable password or
     *  key), as against refusing one request or failing in some other way. */
    val signInRefused: Boolean get() = this is Server && code in 40L..44L

    override fun toString(): String = "${javaClass.simpleName}: $message"
}
