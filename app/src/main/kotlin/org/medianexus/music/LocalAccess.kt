package org.medianexus.music

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.net.LocalNetwork
import org.medianexus.music.net.LocalNetwork.Nearness
import org.medianexus.music.requests.LocalGate
import org.medianexus.music.store.SettingsStore

/**
 * Android 17's local-network permission, for a server on the home network (see
 * [LocalNetwork]). The app targets Android 17, so without the permission a server at a home
 * address can't be reached at all: connections just time out.
 *
 * - A server certainly on the home network: the app explains and asks before signing in, and
 *   while signed in and not allowed, says so over every page.
 * - One that may be (a name, a VPN's or carrier's range): the app connects first; only if
 *   the server can't be reached and the permission isn't held does it say that this may be
 *   why, and offer to ask.
 *
 * The state of the question (asked, granted, refused, can't be asked again) is kept here, in
 * the app's objects, not in a screen: the system's question can outlive the activity that
 * asked it (turning the phone rebuilds it), and the sign-in that was waiting on the answer
 * carries on when it comes, whichever activity is there to hear it. Whether the system will
 * ask again is what it reports, not a guess: after a refusal, Android says whether it would
 * show its question again (`shouldShowRequestPermissionRationale`); when it wouldn't, the
 * app's page in the phone's settings is the only place left to allow it, and that is
 * remembered in the settings so a later "Allow" goes straight there.
 *
 * Nothing here stops a connection being tried: if the address turns out to be reachable
 * after all (through a VPN, say), so much the better.
 */
class LocalAccess(private val context: Context, private val session: Session, private val settings: SettingsStore, scope: CoroutineScope) {
    /** Where the question stands. */
    enum class Ask { NOT_ASKED, ASKING, GRANTED, REFUSED, CANNOT_ASK }

    private val nearness = MutableStateFlow(Nearness.NOT)
    private val granted = MutableStateFlow(isGranted())
    private val unreachable = MutableStateFlow(false)
    private val asked = MutableStateFlow(if (settings.value.localNetworkRefusedForGood) Ask.CANNOT_ASK else Ask.NOT_ASKED)
    private val allowedCount = MutableStateFlow(0)

    /** What the person has been asked, and answered. */
    val ask: StateFlow<Ask> = asked.asStateFlow()

    /** Signed in to a server the app may not be allowed to reach: certainly on the home
     *  network, or possibly, and it can't be reached. */
    val blocked: StateFlow<Boolean> = combine(nearness, granted, unreachable) { near, ok, down ->
        APPLIES && !ok && (near == Nearness.LOCAL || (near == Nearness.MAYBE && down))
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** As [blocked], and the server is certainly on the home network (not only possibly):
     *  how firmly the screens say it. */
    val certain: StateFlow<Boolean> = combine(blocked, nearness) { stuck, near -> stuck && near == Nearness.LOCAL }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Goes up each time the permission is given: pages and covers that failed try again. */
    val allowed: StateFlow<Int> = allowedCount.asStateFlow()

    /** What to do once the question is answered (a sign-in waiting on it). */
    private var waiting: ((Boolean) -> Unit)? = null

    init {
        // Allowed in the phone's settings while the app wasn't running: no longer refused.
        if (granted.value && settings.value.localNetworkRefusedForGood) {
            settings.update { it.copy(localNetworkRefusedForGood = false) }
            asked.value = Ask.GRANTED
        }
        if (APPLIES) {
            scope.launch {
                session.state.collect { state ->
                    nearness.value = if (state is SessionState.SignedIn) nearnessOf(state.api.server) else Nearness.NOT
                    unreachable.value = false
                }
            }
        }
    }

    /** Whether the server answered (true) or couldn't be reached at all (false), lately. */
    fun reached(ok: Boolean) {
        unreachable.value = !ok
    }

    /** Looks again at whether it's allowed (after the person has answered, or come back from
     *  the phone's settings). */
    fun recheck() {
        val was = granted.value
        granted.value = isGranted()
        if (granted.value && !was) given()
    }

    /**
     * About to ask; `then` hears the answer (whether it was granted). It must hold nothing of
     * a screen's: the screen may be rebuilt before the answer comes. False if a question is
     * already open (only one is, at a time): nothing is asked, and `then` is not kept.
     */
    fun asking(then: ((Boolean) -> Unit)? = null): Boolean {
        if (asked.value == Ask.ASKING) return false
        waiting = then
        asked.value = Ask.ASKING
        return true
    }

    /**
     * The question will never be answered (the app's screen closed for good while it was up,
     * or it couldn't be shown): it is no longer open, and whatever was waiting on it, a
     * sign-in with its password above all, is let go without being carried out.
     */
    fun abandoned() {
        waiting = null
        if (asked.value == Ask.ASKING) asked.value = if (settings.value.localNetworkRefusedForGood) Ask.CANNOT_ASK else Ask.NOT_ASKED
    }

    /** The system's answer: granted or not, and if not, whether it would ask again. */
    fun answered(yes: Boolean, canAskAgain: Boolean) {
        asked.value = when {
            yes -> Ask.GRANTED
            canAskAgain -> Ask.REFUSED
            else -> Ask.CANNOT_ASK
        }
        val forGood = asked.value == Ask.CANNOT_ASK
        if (settings.value.localNetworkRefusedForGood != forGood) settings.update { it.copy(localNetworkRefusedForGood = forGood) }
        recheck()
        val then = waiting
        waiting = null
        then?.invoke(yes)
    }

    /** The system won't ask again: only the app's page in the phone's settings can allow it. */
    val cannotAsk: Boolean get() = asked.value == Ask.CANNOT_ASK

    private fun given() {
        if (asked.value != Ask.GRANTED) asked.value = Ask.GRANTED
        if (settings.value.localNetworkRefusedForGood) settings.update { it.copy(localNetworkRefusedForGood = false) }
        allowedCount.value++
    }

    /** Whether signing in to `address` needs the permission asked for first: it is certainly
     *  on the home network, and the permission isn't held. */
    suspend fun neededFor(address: String): Boolean = APPLIES && !isGranted() && nearnessOf(address) == Nearness.LOCAL

    /**
     * **For the Nexus server's address, and the music server's the backend hands back** (the
     * backend design's 6.4): whether it needs the permission asked for before it is connected
     * to. **A name is judged by what it leads to on this network now**, as Lidarr's is
     * (`requests/LocalGate`): a public name that leads to an address inside the house, on
     * Wi-Fi or Ethernet, is certainly local. That is the owner's router, and the fault that cost
     * three releases.
     */
    suspend fun neededStrictlyFor(address: String): Boolean =
        APPLIES && !isGranted() && LocalGate.nearnessOf(LocalNetwork.hostOf(address), homeNetwork()) == Nearness.LOCAL

    /** As [neededStrictlyFor], after a failure in which no connection was opened: whether the
     *  permission may be why (the address is, or may be, inside). */
    suspend fun mayBeWhyStrictly(address: String): Boolean =
        APPLIES && !isGranted() && LocalGate.nearnessOf(LocalNetwork.hostOf(address), homeNetwork()).let { it != null && it != Nearness.NOT }

    /** Whether `host` leads inside the house on this network now; null if it couldn't be
     *  judged. Blocks for a look-up of a few seconds at most: never on the main thread. */
    fun insideNow(host: String): Boolean? = kotlinx.coroutines.runBlocking { LocalGate.nearnessOf(host, homeNetwork()) }?.let { it == Nearness.LOCAL }

    /** Whether the permission is held, where the phone has one (Android 17 on); null before. */
    val permissionNow: Boolean? get() = if (APPLIES) isGranted() else null

    private fun homeNetwork(): Boolean = transportNow(context).let { it == "Wi-Fi" || it == "Ethernet" }

    /** Whether a failure to reach `address` may be for want of the permission: it is or may
     *  be on the home network, and the permission isn't held. */
    suspend fun mayBeWhy(address: String): Boolean = APPLIES && !isGranted() && nearnessOf(address) != Nearness.NOT

    /** Whether the app may reach the home network now (always, before Android 17). For
     *  requests (`requests/LocalGate`), which ask about Lidarr's address, not the server's. */
    val held: Boolean get() = isGranted()

    private fun isGranted(): Boolean =
        !APPLIES || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** How near the server's address is: written out, or looked up (for a few seconds at
     *  most; a name that can't be looked up can't be connected to either, and isn't blamed
     *  on the permission). */
    private suspend fun nearnessOf(address: String): Nearness {
        val host = LocalNetwork.hostOf(address)
        LocalNetwork.judgeHost(host)?.let { return it }
        val found = CoroutineScope(Dispatchers.IO).async { runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() }
        return withTimeoutOrNull(LOOKUP_MILLIS) { found.await() }?.let(LocalNetwork::judgeFound) ?: Nearness.NOT
    }

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, added in Android 17 (API 37). */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Only Android 17 and later have (and enforce) it. */
        val APPLIES: Boolean = Build.VERSION.SDK_INT >= 37

        private const val LOOKUP_MILLIS = 3_000L
    }
}
