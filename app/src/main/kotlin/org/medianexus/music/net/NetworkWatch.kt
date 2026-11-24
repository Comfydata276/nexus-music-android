package org.medianexus.music.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The network the phone is on now, as far as the app's own decisions need it: whether there
 * is one, and whether Android counts it as unmetered (Wi-Fi that isn't marked as metered,
 * Ethernet; not mobile data).
 */
data class NetworkNow(val connected: Boolean = true, val unmetered: Boolean = true)

/**
 * Follows the phone's network as Android reports it, at every change: a network coming or
 * going, and the same network turning metered or unmetered (which is a change of its
 * capabilities, not of the network).
 *
 * **It never asks whether Android has "validated" the network** as reaching the internet. A
 * music server on the home Wi-Fi is there whether or not the Wi-Fi has any internet beyond
 * it, and a network Android hasn't finished probing is still a network. Whether the app's
 * own server answers is asked of the server (`ServerWatch`).
 */
class NetworkWatch(context: Context) {
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val now = MutableStateFlow(read())

    val state: StateFlow<NetworkNow> = now.asStateFlow()

    /** Called when a network arrives after there was none (or another one takes over). */
    @Volatile var onBack: () -> Unit = {}

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val was = now.value
                    now.value = connectivity.getNetworkCapabilities(network)?.let(::of) ?: was.copy(connected = true)
                    if (!was.connected) onBack()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val was = now.value
                    now.value = of(capabilities)
                    if (!was.connected) onBack()
                }

                override fun onLost(network: Network) {
                    now.value = NetworkNow(connected = false, unmetered = false)
                }
            })
        }
    }

    /** As it stands this instant (also the value at start-up, before any callback). */
    private fun read(): NetworkNow {
        val manager = connectivity ?: return NetworkNow()
        return try {
            val network = manager.activeNetwork ?: return NetworkNow(connected = false, unmetered = false)
            manager.getNetworkCapabilities(network)?.let(::of) ?: NetworkNow(connected = true, unmetered = !manager.isActiveNetworkMetered)
        } catch (_: RuntimeException) {
            // Not to be had (no permission in some corner of the system): assume a network,
            // and let the requests themselves say otherwise.
            NetworkNow()
        }
    }

    companion object {
        /** What a network's capabilities come to. Nothing here looks at
         *  `NET_CAPABILITY_VALIDATED` (see the class). */
        fun of(capabilities: NetworkCapabilities): NetworkNow =
            NetworkNow(connected = true, unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
