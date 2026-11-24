package org.medianexus.music.jam

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a jam this phone was in is over for it. */
enum class Gone {
    /** "The jam has ended." */
    ENDED,
    /** "The host removed you from the jam." */
    REMOVED,
    /** "The jam was restarted. Join it again from its link." */
    RESTARTED,
}

/** How joining went (Windows' `join_session`'s answers). */
sealed interface Joining {
    data class In(val token: String, val me: String, val host: String) : Joining
    /** "That link didn't lead to a jam." */
    data object NotAJam : Joining
    /** "That jam has ended. Ask its host for a new link." */
    data object Ended : Joining
    /** The relay's own words for why not (too many joining, the jam full), or else "The jam
     *  can't take anyone else just now." */
    data class Full(val said: String?) : Joining
    /** "Can't reach the jam relay. Check the link and your connection." */
    data object Unreachable : Joining
}

/** What the other side said to a request: done (with what came back), or not and why. */
sealed interface Asked {
    data class Done(val data: JsonElement?) : Asked
    /** The other side's words ("That song is no longer in the queue."), cut to a line. */
    data class Refused(val said: String?) : Asked
    /** No answer came: whether it was done isn't known. The jam is read again. */
    data object NoAnswer : Asked
}

/**
 * The guest's side of the relay (Windows' `join_session`, `guest_loop`, `jam_request`,
 * `jam_refresh` and the goodbye in `leave_jam_saying`), as plain suspending functions on a
 * [RelayLine]. What is done with the answers is the caller's.
 */
object GuestSide {
    suspend fun join(line: RelayLine, jam: String, name: String): Joining =
        when (val answer = line.post("api/jam/$jam/join", "", buildJsonObject { put("name", name) })) {
            RelayLine.Answer.Unreachable -> Joining.Unreachable
            is RelayLine.Answer.Got -> {
                val body = answer.body
                when {
                    answer.status == 200 -> {
                        val token = body.at("token").str()?.takeIf { it.length in 1..128 && plainId(it) }
                        val me = body.at("guest").at("id").str()?.takeIf { it.length <= 64 && plainId(it) }
                        if (token == null || me == null) Joining.NotAJam else Joining.In(token, me, shownText(body.at("host").str(), 24).ifEmpty { "Someone" })
                    }
                    answer.status == 404 && body.at("error").str() != null -> Joining.Ended
                    answer.status == 429 || answer.status == 503 -> Joining.Full(body.at("error").str()?.let { shownText(it) }?.ifEmpty { null })
                    else -> Joining.NotAJam
                }
            }
        }

    /**
     * Waits on the relay for the jam to change, for as long as the caller's coroutine runs:
     * each new state goes to `heard`; two failures in a row are said once (`trouble(true)`),
     * and a success after them unsays it. Ends with why the jam is over for this guest.
     * `pause` is how it waits between tries (1, 2, … 5 seconds), as Windows does.
     */
    suspend fun watch(
        line: RelayLine,
        jam: String,
        token: String,
        heard: suspend (JsonElement) -> Unit,
        trouble: suspend (Boolean) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Gone? {
        var version = 0L
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val answer = line.get("api/jam/$jam/state?v=$version", token)
            if (!currentCoroutineContext().isActive) return null
            when {
                answer is RelayLine.Answer.Got && answer.status == 200 -> {
                    if (failures >= 2) trouble(false)
                    failures = 0
                    version = answer.body.at("v").u64() ?: 0L
                    heard(answer.body)
                }
                answer is RelayLine.Answer.Got && answer.status == 404 -> return Gone.ENDED
                answer is RelayLine.Answer.Got && answer.status == 403 -> return Gone.REMOVED
                answer is RelayLine.Answer.Got && answer.status == 401 -> return Gone.RESTARTED
                else -> {
                    failures++
                    if (failures == 2) trouble(true)
                    pause(failures.coerceAtMost(5) * 1000L)
                }
            }
        }
        return null
    }

    /** The jam as it stands now, once (after something assumed here turned out not to be). */
    suspend fun look(line: RelayLine, jam: String, token: String): JsonElement? {
        val answer = line.get("api/jam/$jam/state?v=0", token, waitSeconds = 20)
        return (answer as? RelayLine.Answer.Got)?.takeIf { it.status == 200 }?.body
    }

    /**
     * Asks the jam's host for something, once (rule 6). `owner` is the word that shows the
     * other side this is one of its owner's own devices, when it is.
     */
    suspend fun ask(line: RelayLine, jam: String, token: String, action: String, data: JsonObject, owner: String?): Asked {
        val sent = if (owner == null) data else JsonObject(data + ("owner" to kotlinx.serialization.json.JsonPrimitive(owner)))
        val body = buildJsonObject {
            put("action", action)
            put("data", sent)
        }
        return when (val answer = line.post("api/jam/$jam/ask", token, body)) {
            RelayLine.Answer.Unreachable -> Asked.NoAnswer
            is RelayLine.Answer.Got -> when {
                answer.status == 200 && answer.body.at("ok").bool() == true -> Asked.Done(answer.body.at("data"))
                // The relay's 504: the host's app didn't answer, and the question was taken
                // back if it hadn't been collected; it may have been. Not known either way.
                answer.status == 504 || answer.status >= 599 -> Asked.NoAnswer
                else -> Asked.Refused(answer.body.at("error").str()?.let { shownText(it) }?.ifEmpty { null })
            }
        }
    }

    /** Says goodbye, so the host's list of who's there is put right at once. */
    suspend fun leave(line: RelayLine, jam: String, token: String) {
        line.send("api/jam/$jam/leave", token, "application/json", "{}".toByteArray(), waitSeconds = 5)
    }
}
