package org.medianexus.music.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.medianexus.music.store.PlaybackApp
import org.medianexus.music.store.SettingsStore

/**
 * "Apps that can control playback": the apps that ask to, which neither Android nor this app
 * vouches for, and what the person has decided about each.
 *
 * Most things that control the player need nothing from here. Android itself vouches for
 * the system, headsets, a car, and any app the person has given notification access; the
 * app lets Android Auto and the Google app in for who they are ([KnownHosts]). What is left
 * is an app like a watch's companion, which connects as an ordinary app would. Until 0.5.1
 * that was refused, and the only fix was a new release with its certificate in it.
 *
 * Now **the person decides, once, and the decision is bound to who signed the app**:
 *
 * - A caller is identified by its uid, which the system gives and no app can claim: the
 *   package Android names for that uid, and the certificate that package is signed with
 *   ([Callers.who]). The name a caller gives for itself is never trusted alone.
 * - One that is refused is remembered here, and shown in Settings by its name, its package
 *   and its certificate, with a switch. Nothing pops up and no notification is sent: a dot
 *   on that row of Settings says there is something new to look at.
 * - Switching it on stores the package and the certificate. Every later connection, and
 *   every command of a connection that is open, is let through only if the calling uid's
 *   package and certificate are still exactly those. The same name signed with another
 *   certificate is another app: refused, and listed as a new one.
 * - What it then gets is what a headset gets ([Caller.ALLOWED]): transport and what is
 *   playing, and the covers of what it is shown. Not the library, not the queue.
 *
 * An app Android will not name for its uid (one this app may not see: Android 11 and later
 * hide most apps from each other) cannot be offered at all, because there is nothing to bind
 * a decision to. Diagnostics says so, with the uid and what the app called itself, so that
 * its package can be named in the manifest (`<queries>`) in a later release: that one line
 * is how a new watch or controller app comes to be supported.
 */
class ControlApps(
    private val callers: Callers,
    private val settings: SettingsStore,
    /** Something for Diagnostics; `now`: written to the phone at once. */
    private val note: (text: String, now: Boolean) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** The apps that have asked, newest first, with the person's decision on each. */
    val apps: Flow<List<PlaybackApp>> = settings.state.map { it.playbackApps }.distinctUntilChanged()

    /** How many have asked that the person has not yet looked at: the dot in Settings. */
    val unseen: Flow<Int> = settings.state.map { all -> all.playbackApps.count { !it.seen && !it.allowed } }.distinctUntilChanged()

    /** Whether this caller is an app the person has allowed, **as it is signed now**. */
    fun allowed(claims: String?, uid: Int): Boolean {
        val listed = settings.state.value.playbackApps
        if (listed.none { it.allowed }) return false
        val who = callers.who(claims, uid) as? Callers.Who.App ?: return false
        return listed.any { it.allowed && it.packageName == who.packageName && it.certificate == who.certificate }
    }

    /**
     * A caller with no standing was refused: remembered for Settings if Android says who it
     * is, and said in Diagnostics either way, with everything a person would need to pass on.
     */
    fun refused(claims: String?, uid: Int) {
        when (val who = callers.who(claims, uid)) {
            // (Each line fits in one of Diagnostics', whatever the names are.)
            is Callers.Who.Unnamed -> note(
                ("refused caller with uid $uid" + (who.claims?.let { ", calling itself ${it.take(80)}" } ?: "") +
                    ": Android doesn't say which app that is (not one this version may see), so it can't be offered in Settings. " +
                    "Its package would have to be named in a later version."),
                true,
            )
            is Callers.Who.App -> {
                val at = clock()
                var fresh = false
                var again = false
                settings.update { before ->
                    val was = before.playbackApps.firstOrNull { it.packageName == who.packageName && it.certificate == who.certificate }
                    fresh = was == null
                    // (An app that keeps asking is not written down again each time.)
                    again = was != null && at - was.refusedAt in 0 until AGAIN_SECONDS
                    if (again) return@update before
                    val entry = (was ?: PlaybackApp(who.packageName, who.certificate)).copy(label = who.label, withThePhone = who.withThePhone, refusedAt = at)
                    // (Newest first; and no more than [MOST] kept, those the person allowed
                    // before any that were only seen.)
                    val rest = before.playbackApps.filterNot { it.packageName == who.packageName && it.certificate == who.certificate }
                    before.copy(playbackApps = (listOf(entry) + rest).let { all -> all.filter { it.allowed } + all.filterNot { it.allowed } }.take(MOST).sortedByDescending { it.refusedAt })
                }
                val another = fresh && settings.state.value.playbackApps.any { it.packageName == who.packageName && it.certificate != who.certificate }
                note(
                    "refused caller with uid $uid: Android says it is ${who.packageName.take(80)} (“${who.label.take(40)}”), " +
                        (if (who.withThePhone) "installed with the phone" else "installed by you") +
                        (if (who.others.isEmpty()) "" else ", one of ${who.others.size + 1} apps with that uid") +
                        ". It is listed in Settings, Apps that can control playback." +
                        if (another) " It is signed differently from the app of that name decided on before." else "",
                    true,
                )
                for (digest in who.certificate.split('+')) {
                    note("refused caller ${who.packageName.take(80)} is signed with SHA-256 ${digest.chunked(2).joinToString(":")}", true)
                }
            }
        }
    }

    /** The person switched an app on or off. */
    fun allow(app: PlaybackApp, on: Boolean) = settings.update { before ->
        before.copy(playbackApps = before.playbackApps.map { if (it.packageName == app.packageName && it.certificate == app.certificate) it.copy(allowed = on, seen = true) else it })
    }

    /** The person has looked at the list. */
    fun seen() = settings.update { before ->
        if (before.playbackApps.all { it.seen }) before else before.copy(playbackApps = before.playbackApps.map { it.copy(seen = true) })
    }

    /** The person has no more use for an entry: forgotten (and, if it was allowed, no longer). */
    fun forget(app: PlaybackApp) = settings.update { before ->
        before.copy(playbackApps = before.playbackApps.filterNot { it.packageName == app.packageName && it.certificate == app.certificate })
    }

    companion object {
        /** How many are remembered. (Each is a real app on the phone, identified by the
         *  system: an app cannot make the list grow by calling itself by many names.) */
        const val MOST = 24

        /** An app refused again within this long is not noted in the settings again. */
        const val AGAIN_SECONDS = 60L
    }
}
