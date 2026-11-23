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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.net.LocalNetwork

/**
 * Android 17's local-network permission, for a server on the home network (see
 * [LocalNetwork]). The app targets Android 17, so without the permission a server at a home
 * address can't be reached at all: connections just time out. It is asked for when signing in
 * to such an address, and if it's missing later (refused, or taken away in the phone's
 * settings) the app says so and offers to ask again; the car is told in words.
 *
 * Nothing here stops a connection being tried: if the address turns out to be reachable after
 * all (through a VPN, say), so much the better.
 */
class LocalAccess(private val context: Context, session: Session, scope: CoroutineScope) {
    private val serverIsLocal = MutableStateFlow(false)
    private val granted = MutableStateFlow(isGranted())

    /** Signed in to a server on the home network that the app isn't allowed to reach. */
    val blocked: StateFlow<Boolean> = combine(serverIsLocal, granted) { local, ok -> local && !ok }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        if (APPLIES) {
            scope.launch {
                session.state.collect { state ->
                    serverIsLocal.value = state is SessionState.SignedIn && isLocal(state.api.server)
                }
            }
        }
    }

    /** Looks again at whether it's allowed (after the person has answered, or come back from
     *  the phone's settings). */
    fun recheck() {
        granted.value = isGranted()
    }

    /** Whether signing in to `address` needs the permission asked for first. */
    suspend fun neededFor(address: String): Boolean = APPLIES && !isGranted() && isLocal(address)

    private fun isGranted(): Boolean =
        !APPLIES || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether the server's address is on the local network: written out, or looked up (for
     *  a few seconds at most; a name that can't be looked up is taken as not local). */
    private suspend fun isLocal(address: String): Boolean {
        val host = LocalNetwork.hostOf(address)
        LocalNetwork.judgeHost(host)?.let { return it }
        val found = CoroutineScope(Dispatchers.IO).async { runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() }
        return withTimeoutOrNull(LOOKUP_MILLIS) { found.await() }?.let(LocalNetwork::anyLocal) ?: false
    }

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, added in Android 17 (API 37). */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Only Android 17 and later have (and enforce) it. */
        val APPLIES: Boolean = Build.VERSION.SDK_INT >= 37

        private const val LOOKUP_MILLIS = 3_000L
    }
}
