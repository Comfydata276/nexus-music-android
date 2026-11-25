package org.medianexus.music.playback

import androidx.media3.common.Player

/** Who is at the other end of a connection to the player's session. */
enum class Caller {
    /** An app with no standing: not this app, and not one the system vouches for. It gets
     *  no connection at all. A private music library is not for any app on the phone to
     *  browse. */
    REFUSED,

    /** Someone vouched for. Either by the system: the system itself, or an app the person
     *  has given notification access or the right to control media (a car's own media
     *  screens, Bluetooth, a watch, and on most phones Android Auto and the Assistant). Or
     *  by being one of the known hosts of media apps, by package name and signing
     *  certificate both ([KnownHosts]): Android Auto and the Google app are let in for who
     *  they are, whatever a particular phone has or hasn't granted them. They browse, search
     *  and play as a car needs to. */
    TRUSTED,

    /** Media3's own controller inside this app, which draws the notification and stands for
     *  the system's media controls and for headset buttons. A player command that arrives
     *  under this name may have come from outside (a media-button intent sent to the
     *  exported service looks the same as a tap on the notification), so those are transport
     *  only. Like, shuffle and repeat under this name are the system's own media controls
     *  pressing them: the notification's own buttons for the three, and anything forged to
     *  look like them, are dealt with before they become anyone's command (`OwnButtons`). */
    NOTIFICATION,

    /** This app's own screens. */
    OWN_INTERFACE,

    /**
     * An app the person has allowed, in Settings, by who signed it ([ControlApps]): a
     * watch's companion, say, that Android itself does not vouch for. It gets what a
     * headset gets and no more: transport, and what is playing. It does not browse or
     * search the library, see or change the queue, or press like, shuffle or repeat.
     */
    ALLOWED,
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
     * or permission to control media). `knownHost` says the caller is one of the known hosts,
     * by package name and signing certificate both (never by name alone: see [KnownHosts]).
     * `sameUid` and `ownPackage` say the controller really is this app: the uid is the
     * system's word, and the package name is checked against it. `legacy` is a platform
     * controller rather than a Media3 one. `notification` is Media3's media notification
     * controller.
     */
    fun caller(trusted: Boolean, knownHost: Boolean, sameUid: Boolean, ownPackage: Boolean, legacy: Boolean, notification: Boolean, allowed: Boolean = false): Caller = when {
        sameUid && notification -> Caller.NOTIFICATION
        sameUid && ownPackage && !legacy -> Caller.OWN_INTERFACE
        trusted || knownHost -> Caller.TRUSTED
        // (`allowed`: the person's own decision about this very app, by the certificate
        // Android says the calling uid's package is signed with: see ControlApps.)
        allowed -> Caller.ALLOWED
        else -> Caller.REFUSED
    }

    /** Whether this caller may send this custom command. */
    fun mayCustom(caller: Caller, action: String): Boolean = when {
        caller == Caller.REFUSED || caller == Caller.ALLOWED -> false
        // (Under the notification controller's name too: that is how Media3 reports the
        // system's own media controls, on the lock screen and in Quick Settings, which show
        // these buttons. A start intent shaped like one of them never gets this far: see
        // OwnButtons.)
        action in BUTTONS -> true
        action in APP_ONLY -> caller == Caller.OWN_INTERFACE
        else -> false
    }

    /** Whether this caller may use this player command (`Player.COMMAND_…`). */
    fun mayPlayer(caller: Caller, command: Int): Boolean = when (caller) {
        Caller.REFUSED -> false
        Caller.NOTIFICATION, Caller.ALLOWED -> command in TRANSPORT
        else -> true
    }

    /**
     * Everything an app the person allowed may use of the player: [TRANSPORT], and reading
     * what is playing (the song and its details, and through them how far it has got). Not
     * the queue: without `COMMAND_GET_TIMELINE` Media3 shows such a controller the one
     * song that is playing and nothing before or after it.
     */
    val ALLOWED_PLAYER: Set<Int> get() = TRANSPORT + setOf(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)

    /** The buttons on the car's player and the notification. */
    val BUTTONS = setOf("org.medianexus.music.LIKE", "org.medianexus.music.SHUFFLE", "org.medianexus.music.REPEAT")

    /** What only the app's own screens ask for (see [AppCommands]). */
    val APP_ONLY: Set<String> = AppActions.all.toSet()

    /**
     * What a start intent to the service, a headset button or the notification's transport
     * buttons can do: play, pause, stop, the next and previous song, and moving about in the
     * one that's playing. Not choosing what is played, changing the queue or jumping to a
     * place in it; and not shuffle or repeat, which change how someone's queue plays (and,
     * with it, what the app remembers for that list).
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
