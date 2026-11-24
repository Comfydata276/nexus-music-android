package org.medianexus.music.jam

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where the person's devices find each other at the relay (Windows' `account_words`,
 * `room_loop`, `say_status`, `stop_linking`).
 */
object RoomSide {
    /**
     * What only copies of the app signed in to this account can work out: the name of the room
     * they meet in, and the word that shows one of them is asking. Both come from the account's
     * name and password, exactly as Windows works them out, and neither gives the password
     * away; it is never sent.
     */
    fun words(username: String, password: String): Pair<String, String> {
        fun word(purpose: String): String =
            MessageDigest.getInstance("SHA-256").digest("nexus-music $purpose\n$username\n$password".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return word("room") to word("owner").substring(0, 32)
    }

    /** What this device says of itself in the room: the session others steer it through
     *  (none while it is itself steering another, or in a jam), and what it is playing. */
    fun status(session: String?, title: String?, artist: String?, playing: Boolean, steering: String?): JsonObject = buildJsonObject {
        put("session", session?.let(::JsonPrimitiveOf) ?: JsonNull)
        put("title", title?.let(::JsonPrimitiveOf) ?: JsonNull)
        put("artist", artist?.let(::JsonPrimitiveOf) ?: JsonNull)
        put("playing", playing)
        put("steering", steering?.let(::JsonPrimitiveOf) ?: JsonNull)
    }

    private fun JsonPrimitiveOf(text: String) = kotlinx.serialization.json.JsonPrimitive(text)

    suspend fun enter(line: RelayLine, key: String, room: String, id: String, name: String, status: JsonObject): RelayLine.Answer =
        line.post("api/room/$room/enter", key, buildJsonObject {
            put("id", id)
            put("name", name)
            put("status", status)
        })

    suspend fun leave(line: RelayLine, key: String, room: String, id: String) {
        line.send("api/room/$room/leave", key, "application/json", buildJsonObject { put("id", id) }.toString().toByteArray(), waitSeconds = 5)
    }

    /**
     * Waits on the relay for news of the person's other devices, for as long as the caller's
     * coroutine runs: each answer to `seen`; when the relay has forgotten this device (it
     * restarted), `forgot`, to say hello again.
     */
    suspend fun watch(
        line: RelayLine,
        key: String,
        room: String,
        device: String,
        seen: suspend (JsonElement) -> Unit,
        forgot: suspend () -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
    ) {
        var version = 0L
        while (currentCoroutineContext().isActive) {
            val answer = line.get("api/room/$room/poll?device=$device&v=$version", key)
            if (!currentCoroutineContext().isActive) return
            when {
                answer is RelayLine.Answer.Got && answer.status == 200 -> {
                    version = answer.body.at("v").u64() ?: 0L
                    seen(answer.body)
                }
                answer is RelayLine.Answer.Got && answer.status == 404 -> {
                    forgot()
                    pause(3_000)
                }
                answer is RelayLine.Answer.Got && (answer.status == 401 || answer.status == 429) -> pause(30_000)
                else -> pause(5_000)
            }
        }
    }

    /** Whether a relay is there and takes this key (Windows' `check_jam`). */
    sealed interface Hello {
        data object Connected : Hello
        data object KeyRefused : Hello
        data class Refusing(val said: String?) : Hello
        data object NotARelay : Hello
        data object Unreachable : Hello
    }

    suspend fun hello(line: RelayLine, key: String): Hello = when (val answer = line.get("api/hello", key, waitSeconds = 15)) {
        RelayLine.Answer.Unreachable -> Hello.Unreachable
        is RelayLine.Answer.Got -> when {
            answer.status == 200 && answer.body.at("nexus_jam").u64() == 1L && answer.body.at("host").bool() == true -> Hello.Connected
            answer.status == 401 -> Hello.KeyRefused
            answer.status == 429 -> Hello.Refusing(answer.body.at("error").str()?.let { shownText(it) }?.ifEmpty { null })
            else -> Hello.NotARelay
        }
    }
}

/**
 * When the phone holds a connection to the relay at all (rule 1). On Windows every linked PC
 * keeps a request open at the relay all the time; a phone doing that from the background would
 * keep its radio awake all day. So the phone is in the room, with a session the others can
 * steer it through, only while one of these is so:
 *
 * - the app's screens are in front;
 * - music is playing on the phone, or was until less than [PAUSED_GRACE_MILLIS] ago (the
 *   playback service is in the foreground for that long after a pause, and a PC that paused
 *   the phone can still play it again);
 * - the phone is in a jam, hosting one, or steering another device (with the notification
 *   of [JamService] saying so).
 *
 * Otherwise its session is ended, it leaves the room, and the other devices simply do not
 * list it.
 */
object Reasons {
    const val PAUSED_GRACE_MILLIS = 10 * 60 * 1000L

    fun open(inFront: Boolean, playing: Boolean, stoppedAt: Long?, now: Long, inJam: Boolean): Boolean =
        inFront || playing || inJam || (stoppedAt != null && now - stoppedAt < PAUSED_GRACE_MILLIS)
}
