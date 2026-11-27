package org.medianexus.music.requests

import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.LocalAccess
import org.medianexus.music.net.LocalNetwork
import org.medianexus.music.net.LocalNetwork.Nearness

/**
 * **Android 17's local-network permission, for Lidarr and its sign-in** (see `LocalAccess`,
 * which has it for the music server). From Android 17 an app that targets it can't reach an
 * address on the network the phone is on without `ACCESS_LOCAL_NETWORK`: the connection
 * simply times out. A Lidarr published under a public name is often reached at home at a
 * private address all the same, because the home's own DNS answers the name with the
 * server's address on the home network (a router that can't send traffic out and back in
 * again). That is what happened in the first real use (0.6.0 to 0.6.2): on Wi-Fi the name
 * led to 192.168.x, the permission had never been asked for (the music server's name led
 * elsewhere), and every check timed out; on mobile data it worked.
 *
 * **A name is judged by what it leads to on this network now**, address by address, with
 * the same rules as an address written out ([LocalNetwork.judgeHost]): one that leads to
 * 192.168.x is local here, whatever it leads to elsewhere. (The music side judges a name
 * that leads to a private address as only "maybe", on purpose, and is not changed.)
 */
interface LocalGate {
    /** Where `address` is, from here and now: certainly local, maybe, or not. */
    suspend fun near(address: String): Nearness

    /** Whether the app may reach the home network (always, before Android 17). */
    val held: Boolean

    /** Goes up each time the permission is given, in the app or in Android's settings. */
    val allowed: Flow<Int>

    companion object {
        /** For a phone before Android 17, and the tests that don't look at this. */
        val NONE = object : LocalGate {
            override suspend fun near(address: String) = Nearness.NOT
            override val held = true
            override val allowed: Flow<Int> = emptyFlow()
        }

        private const val LOOKUP_MILLIS = 3_000L

        /** How near `host` is, its name looked up by `lookup` if it is one (a few seconds
         *  at the most: a name that can't be looked up can't be reached either, and isn't
         *  blamed on the permission). */
        suspend fun nearnessOf(host: String, lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): Nearness {
            LocalNetwork.judgeHost(host)?.let { return it }
            val found = CoroutineScope(Dispatchers.IO).async { runCatching { lookup(host) }.getOrNull() }
            val addresses = withTimeoutOrNull(LOOKUP_MILLIS) { found.await() } ?: return Nearness.NOT
            val each = addresses.mapNotNull { address -> address.hostAddress?.let { LocalNetwork.judgeHost(it.substringBefore('%')) } }
            return when {
                Nearness.LOCAL in each -> Nearness.LOCAL
                Nearness.MAYBE in each -> Nearness.MAYBE
                else -> Nearness.NOT
            }
        }
    }
}

/** The app's: [LocalAccess] for whether it is held and when it is given. */
class AppLocalGate(private val access: LocalAccess) : LocalGate {
    override suspend fun near(address: String): Nearness = LocalGate.nearnessOf(LocalNetwork.hostOf(address))

    override val held: Boolean get() = access.held

    override val allowed: Flow<Int> = access.allowed
}
