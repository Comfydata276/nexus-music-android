package org.medianexus.music.playback

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **The person's other devices, as the new linking knows them**: what a list of them is
 * drawn from, and what the list calls (the Windows repository's `docs/linking-design.md`;
 * on Windows this is the "Linked devices" part of the Connect panel). It is in the graph
 * whether or not there is a linking, as [Steered] is, so that a screen has one thing to
 * collect: with no linking (the build has none, it is switched off, nobody is signed in,
 * no relay is set up) the state is the empty one and every call does nothing.
 *
 * What the player shows while a device is being steered is not here: it is [Steered], which
 * the screens already draw from.
 *
 * Main thread only.
 */
class Linked {
    /** One row of the list. */
    @Immutable
    data class Device(
        /** What picking it, or pausing it, names. Empty for one that is not here just now. */
        val place: String,
        val name: String,
        /** What it is doing, in a few words: the library's sentence, to show as it is. */
        val about: String,
        val playing: Boolean,
        /** It is the device the phone's player is steering. */
        val steered: Boolean,
        /** It is here to be picked. False for one seen before that is not here just now,
         *  and for one that does not speak as this phone does ([update]). */
        val pickable: Boolean,
        /** **It does not speak as this phone does**: which of the two to update, to show
         *  under its row as it is ("Update Nexus Music on Study PC to control it from
         *  here"). Such a device is listed with what it is playing, is not to be picked,
         *  and has no Pause. Empty for every other row. */
        val update: String,
    )

    @Immutable
    data class State(
        /** There is a linking: the rest is its word. False, and there is nothing to list. */
        val running: Boolean = false,
        /** The phone has its place among the person's devices. Until then the devices
         *  listed are the ones seen before, each "Looking for it…". */
        val entered: Boolean = false,
        val devices: List<Device> = emptyList(),
        /** What there is to say under the list: what became of the last thing asked, why
         *  nothing is linked, or (with [stopped]) that linking has stopped on this phone.
         *  The library's sentence. Empty for nothing. */
        val note: String = "",
        /** The device being steered, by name; empty for none. */
        val steering: String = "",
        /** Linking has stopped on this phone until the app is next opened (a fault). */
        val stopped: Boolean = false,
        /** **What this phone is called among the person's devices**: the name the others
         *  list it by (the one chosen in Settings, else the phone's own, else its model),
         *  as the linking was told it when it started. Empty with no linking. */
        val me: String = "",
    )

    private val now = MutableStateFlow(State())
    val state: StateFlow<State> = now.asStateFlow()

    /** The linking, while there is one. */
    internal interface Doing {
        fun choose(place: String)
        fun pause(place: String)
        fun playHere()
        fun letGo()
        fun reset()
        fun lookingUp(): Long?
        fun lookedUp(ticket: Long)
    }

    internal var doing: Doing? = null

    /** A device was picked: the phone starts steering it, and if music is playing here it
     *  carries on over there, the phone stopping once that device has started. What
     *  became of it is said by the library (a toast, and [State.note]). */
    fun choose(place: String) {
        doing?.choose(place)
    }

    /** Pause, beside a device that is playing. */
    fun pause(place: String) {
        doing?.pause(place)
    }

    /** "Play here": what the device being steered is playing carries on on the phone,
     *  and the phone stops steering it. Nothing, if none is being steered. */
    fun playHere() {
        doing?.playHere()
    }

    /** "Stop steering": the other device is left as it is, and the player is the phone's
     *  own again. */
    fun letGo() {
        doing?.letGo()
    }

    /** "Reset linking": a new secret for every device of the account. */
    fun reset() {
        doing?.reset()
    }

    /**
     * **Something the person chose to play is being looked up** (its songs fetched)
     * before it can be put in the player: from now until [lookedUp] with what this
     * returns, or a minute, the phone is busy to the person's other devices, so that
     * nothing one of them asks lands between the choice and its music. Null with no
     * linking, and then there is nothing to end.
     */
    fun lookingUp(): Long? = doing?.lookingUp()

    fun lookedUp(ticket: Long) {
        doing?.lookedUp(ticket)
    }

    /** What the library says the list shows (its `shown`), and what this phone is called. */
    internal fun shown(shown: JsonObject, me: String = "") {
        now.value = read(shown).copy(me = me)
    }

    /** The sign-in has ended, and the linking waits for the next: nothing is listed. */
    internal fun nobody() {
        now.value = State()
    }

    /** The linking is over: as the service goes, or for a fault, and then with the
     *  sentence the person was told ("Linking has stopped on this phone until the app is
     *  next opened."), which stays under the list. */
    internal fun over(stopped: Boolean, words: String = "") {
        doing = null
        now.value = State(stopped = stopped, note = if (stopped) words else "")
    }

    companion object {
        /** The library's `shown`, as the screens are handed it. */
        internal fun read(shown: JsonObject): State = State(
            running = true,
            entered = shown.getValue("entered").jsonPrimitive.boolean,
            devices = shown.getValue("rows").jsonArray.map { row ->
                val of = row.jsonObject
                Device(
                    place = of.getValue("place").jsonPrimitive.content,
                    name = of.getValue("name").jsonPrimitive.content,
                    about = of.getValue("about").jsonPrimitive.content,
                    playing = of.getValue("playing").jsonPrimitive.boolean,
                    steered = of.getValue("active").jsonPrimitive.boolean,
                    pickable = of.getValue("ready").jsonPrimitive.boolean,
                    update = of.getValue("update").jsonPrimitive.content,
                )
            },
            note = shown.getValue("note").jsonPrimitive.content,
            steering = shown.getValue("steering").jsonPrimitive.content,
        )
    }
}
