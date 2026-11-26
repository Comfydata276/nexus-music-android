package org.medianexus.music.ui.link

import org.medianexus.music.playback.Linked

/**
 * **What the Connect sheet shows of the new linking**, decided apart from drawing it so that
 * it can be tested: whether the sheet can be reached, which kind each row is, where a Pause
 * goes, which rows are listed and which device is named at the top. Everything the rows
 * *say* is the library's, as handed ([Linked.State]); nothing here words anything.
 */
internal object ConnectView {
    /**
     * Whether Now Playing offers "Connect to a device" at all: in a build with jams (as
     * before), and in one with the new linking whether or not it has jams. With neither,
     * nothing is reachable, as in every released build.
     */
    fun reachable(jams: Boolean, link: Boolean): Boolean = jams || link

    /** The three kinds of row (Windows' `ConnectPanel`, "Linked devices (new)"). */
    enum class Kind {
        /** Here, and speaks as this phone does: pressing it picks it. */
        PICK,

        /** Seen before and not here just now: drawn back (half strength), not pressable. */
        AWAY,

        /** Of another generation of linking: full strength, its sentence under it, not
         *  pressable, no Pause. */
        UPDATE,
    }

    fun kind(device: Linked.Device): Kind = when {
        device.update.isNotEmpty() -> Kind.UPDATE
        device.pickable -> Kind.PICK
        else -> Kind.AWAY
    }

    /** A Pause beside a row: only one that is playing and can be picked. */
    fun pause(device: Linked.Device): Boolean = device.playing && kind(device) == Kind.PICK

    /**
     * The rows to list: every device but the one being steered, which is named at the top
     * (as Windows does); none once linking has stopped (it offers nothing).
     */
    fun rows(state: Linked.State): List<Linked.Device> = if (state.stopped) emptyList() else state.devices.filter { !it.steered }

    /** The device the phone steers the new way, to name at the top with "Play here" and
     *  "Stop steering"; empty for none, and once linking has stopped. */
    fun steering(state: Linked.State): String = if (state.stopped) "" else state.steering

    /**
     * This phone's name at the top of the sheet, over "This phone" (Windows: the PC's name over
     * "This PC"). In a build with jams the jam's name for it, as M6 has it; otherwise, or with
     * none there, the name the person's other devices list it by ([Linked.State.me]). Empty
     * for none: the sheet then says "This phone" once.
     */
    fun ownName(jamName: String?, me: String): String = jamName.orEmpty().ifEmpty { me }

    /** What the top says under a device steered the new way. */
    enum class There { PLAYING, PAUSED, NAME_ONLY }

    /**
     * **Which: "Playing there" only of a device known to be playing** (review Z3). Its view in
     * the player says, once there is one; before it (picked, its first full view not in yet),
     * what the list last said of the device steered; and if the list says nothing of it, only
     * its name.
     */
    fun there(state: Linked.State, shown: org.medianexus.music.playback.Steered.Shown?): There {
        val name = steering(state)
        if (shown != null && shown.where == name) return if (shown.playing) There.PLAYING else There.PAUSED
        val row = state.devices.firstOrNull { it.steered } ?: return There.NAME_ONLY
        return if (row.playing) There.PLAYING else There.PAUSED
    }
}
