package org.medianexus.standin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * FIXTURE. Makes the stand-in misbehave on purpose, so the rig's checks can be shown to
 * catch what they claim to catch. scripts/carcheck --selftest switches each fault on in
 * turn and expects the matching check to fail.
 *
 *     adb shell am broadcast -n org.medianexus.standin/.Faults --es set art_http,no_search
 *     adb shell am broadcast -n org.medianexus.standin/.Faults --es set ""        (all off)
 *
 * art_http     artwork addresses are web addresses, which a car cannot open
 * art_private  the artwork provider refuses other apps
 * no_search    the service says it cannot search
 * deaf         spoken requests ("play from search") are refused
 * slow         the Library tab takes twelve seconds to open
 * five_tabs    five things at the root, one of them a song
 * no_resume    "play" with nothing chosen does nothing after a restart
 * signed_out   nobody is signed in: nothing to browse, and an error in words
 */
class Faults : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wanted = intent.getStringExtra("set").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        context.getSharedPreferences("faults", Context.MODE_PRIVATE).edit().putStringSet("on", wanted).commit()
        resultData = "faults: " + wanted.joinToString(",").ifEmpty { "none" }
    }

    companion object {
        fun active(context: Context): Set<String> =
            context.getSharedPreferences("faults", Context.MODE_PRIVATE).getStringSet("on", emptySet()).orEmpty()
    }
}
