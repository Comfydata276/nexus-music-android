package org.medianexus.music.playback

import androidx.media3.common.Player

/** Who is at the other end of a connection to the player's session. */
enum class Caller {
    /** An app with no standing: not this app, and not one the system vouches for. It gets
     *  no connection at all. A private music library is not for any app on the phone to
     *  browse. */
    REFUSED,

    /** Someone the system vouches for: the system itself, or an app the person has given
     *  notification access or the right to control media. Android Auto, the Assistant, a
     *  car's own media screens, Bluetooth and a watch are all these. They browse, search and
     *  play as a car needs to. */
    TRUSTED,

    /** Media3's own controller inside this app, which draws the notification and stands for
     *  the system's media controls and for headset and car buttons. Anything that arrives
     *  under this name may have come from outside (a start intent to the exported service
     *  looks the same as a tap on the notification), so it may only do what the notification
     *  can: transport, and the like, shuffle and repeat buttons. */
    NOTIFICATION,

    /** This app's own screens. */
    OWN_INTERFACE,
}

/**
 * Who may connect to the session, and what each may ask of it. Decided twice: when a
 * controller connects, and again at the moment a command is carried out, because what was
 * granted at connection says nothing about who is really behind a command that Media3
 * forwards under another controller's name.
 */
object Access {
    /**
     * `trusted` is Media3's word for it (the system, this app's own uid, notification access,
     * or permission to control media). `sameUid` and `ownPackage` say the controller really
     * is this app: the uid is the system's word, and the package name is checked against it.
     * `legacy` is a platform controller rather than a Media3 one. `notification` is Media3's
     * media notification controller.
     */
    fun caller(trusted: Boolean, sameUid: Boolean, ownPackage: Boolean, legacy: Boolean, notification: Boolean): Caller = when {
        sameUid && notification -> Caller.NOTIFICATION
        sameUid && ownPackage && !legacy -> Caller.OWN_INTERFACE
        trusted -> Caller.TRUSTED
        else -> Caller.REFUSED
    }

    /** Whether this caller may send this custom command. */
    fun mayCustom(caller: Caller, action: String): Boolean = when {
        caller == Caller.REFUSED -> false
        action in BUTTONS -> true
        action in APP_ONLY -> caller == Caller.OWN_INTERFACE
        else -> false
    }

    /** Whether this caller may use this player command (`Player.COMMAND_…`). */
    fun mayPlayer(caller: Caller, command: Int): Boolean = when (caller) {
        Caller.REFUSED -> false
        Caller.NOTIFICATION -> command in TRANSPORT
        else -> true
    }

    /**
     * Whether a "play that entry of the queue" should be let drop. Everyone but the app's
     * own screens names a queue entry by its place (Media3 gives a car's queue items their
     * index as an id, and a controller sends an index too). Just after the queue has been
     * reordered, whoever is looking at it is still looking at the old order, and a tap made
     * then would play whatever now sits in that place: the wrong song. For a moment after a
     * reorder such a tap is dropped instead; the queue on their screen has caught up by the
     * time they tap again. This narrows the gap; it can't close it, as nothing in the
     * command says which order its sender was looking at.
     */
    fun staleQueueTap(caller: Caller, command: Int, sinceReorderMillis: Long): Boolean =
        caller != Caller.OWN_INTERFACE && command == Player.COMMAND_SEEK_TO_MEDIA_ITEM && sinceReorderMillis in 0 until STALE_QUEUE_MILLIS

    const val STALE_QUEUE_MILLIS = 1_000L

    /** The buttons on the car's player and the notification. */
    val BUTTONS = setOf("org.medianexus.music.LIKE", "org.medianexus.music.SHUFFLE", "org.medianexus.music.REPEAT")

    /** What only the app's own screens ask for (see [AppCommands]). */
    val APP_ONLY: Set<String> = AppActions.all.toSet()

    /**
     * What a notification, a lock screen or a headset button can do: play, pause, stop, the
     * next and previous song, and moving about in the one that's playing; shuffle and repeat,
     * which the system's controls can show as buttons. Not choosing what is played, changing
     * the queue or jumping to a place in it.
     */
    val TRANSPORT = setOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_SET_REPEAT_MODE,
    )
}

/** The names of the commands only the app's own screens send (see [AppCommands]). */
object AppActions {
    const val PLAY = "org.medianexus.music.app.PLAY"
    const val ENQUEUE = "org.medianexus.music.app.ENQUEUE"
    const val MOVE = "org.medianexus.music.app.MOVE"
    const val REMOVE = "org.medianexus.music.app.REMOVE"
    const val CLEAR = "org.medianexus.music.app.CLEAR"
    const val JUMP = "org.medianexus.music.app.JUMP"

    val all = listOf(PLAY, ENQUEUE, MOVE, REMOVE, CLEAR, JUMP)
}

/**
 * A spoken request as the phone's Assistant hands it over (`MEDIA_PLAY_FROM_SEARCH`): what
 * was said, and whichever of the fields the Assistant made out. Read from an intent that any
 * app can send, so only this one action is accepted, only these fields are read, each as
 * text and no longer than a name could be.
 */
data class VoiceAsk(val query: String, val fields: Map<String, String>) {
    companion object {
        const val ACTION = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
        const val QUERY = "query"

        /** The extras the Assistant fills in (android.provider.MediaStore's, and the two it
         *  has no constants for). */
        val FIELDS = listOf(
            "android.intent.extra.focus",
            "android.intent.extra.artist",
            "android.intent.extra.album",
            "android.intent.extra.title",
            "android.intent.extra.playlist",
            "android.intent.extra.genre",
        )

        /** Longer than any name; anything past it is cut off. */
        const val MOST = 200

        /**
         * `text` gives an extra of the intent as text, or null if it has none (or it isn't
         * text). Null unless the action is exactly play-from-search.
         */
        fun read(action: String?, text: (String) -> String?): VoiceAsk? {
            if (action != ACTION) return null
            val fields = HashMap<String, String>()
            for (key in FIELDS) text(key)?.let { tidy(it) }?.takeIf { it.isNotEmpty() }?.let { fields[key] = it }
            return VoiceAsk(tidy(text(QUERY).orEmpty()), fields)
        }

        /** As text a person could have said: no control characters, and not too long. */
        fun tidy(text: String): String = text.filterNot { it.isISOControl() }.trim().take(MOST)
    }
}
