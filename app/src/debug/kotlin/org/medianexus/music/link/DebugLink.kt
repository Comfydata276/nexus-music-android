package org.medianexus.music.link

import android.content.Context
import java.io.File

/**
 * Debug builds only: linking the person's devices, which no build has switched on yet
 * ([LinkBuild]), forced on for the test rig, so that it can be walked on an emulator against
 * a relay and a music server of the rig's own.
 *
 *     adb shell am broadcast -n org.medianexus.music/.playback.DebugControl --es link on|off
 *
 * is a file in the app's own folder, read as the app next starts (what exists is decided
 * once, as the playback service is made). With it on:
 *
 *     ... --es link say                  where linking has got to, and the list of devices
 *     ... --es link "choose <place>"     pick a device in the list
 *     ... --es link play_here            bring what the device being steered plays to the phone
 *     ... --es link let_go               stop steering
 *     ... --es link reset                "Reset linking"
 *     ... --es link "relay <host link>"  the relay and its key, as Settings, Jam would keep
 *                                        them (a build without jams has no such screen)
 *
 * stand in for the screens still to come. The release build's version of this does nothing
 * and has none of it.
 */
object DebugLink {
    private fun marker(context: Context) = File(context.filesDir, "debug-link-on")

    /** As the app starts, before anything of it is made. */
    fun atStart(context: Context) {
        if (marker(context).exists()) LinkBuild.forTests = true
    }

    /** `on` and `off`, for the next start. Null for anything else. */
    fun asked(context: Context, what: String): String? = when (what) {
        "on" -> {
            marker(context).writeText("")
            "linking: forced on, from the app's next start"
        }
        "off" -> {
            marker(context).delete()
            "linking: as the build has it, from the app's next start"
        }
        else -> if (what.startsWith("relay ")) relay(context, what.removePrefix("relay ").trim()) else null
    }

    /** Keeps a relay and its host key for the account signed in, from a host link
     *  (`<address>/#<key>`), and has "Link my devices" on. The key is sealed as any is. */
    private fun relay(context: Context, link: String): String {
        val graph = (context.applicationContext as org.medianexus.music.App).graph
        val account = graph.accounts.state.value?.account ?: return "linking: nobody is signed in"
        val base = link.substringBefore("/#").trimEnd('/')
        val key = link.substringAfter("#", "")
        if (key.isEmpty() || !base.startsWith("http")) return "linking: that is not a host link"
        if (!org.medianexus.music.playback.LinkHost.keepRelay(context, account.server, account.username, base, key)) return "linking: the key could not be kept"
        graph.settings.update { it.copy(linkDevices = true) }
        return "linking: the relay is kept for this account"
    }
}
