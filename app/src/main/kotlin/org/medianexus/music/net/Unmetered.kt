package org.medianexus.music.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * A way to the server that goes by one network and no other: a client whose every connection
 * is made on `network` (its sockets and its name lookups), whatever the phone's default route
 * is at that moment or becomes.
 */
class BoundRoute(val network: Any, val client: OkHttpClient)

/** The unmetered network transfers may use while "Download on Wi-Fi only" is on. */
interface UnmeteredRoutes {
    /** The route by an unmetered network, as it comes and goes; null when there is none. */
    val now: StateFlow<BoundRoute?>

    /** Whether anything wants one now (downloads waiting, with Wi-Fi only on): the network
     *  is asked of the system only while something does. */
    fun want(on: Boolean)
}

/**
 * **"Download on Wi-Fi only" as a promise, not a look at the default route.** While it is
 * wanted, the system is asked for a network that is unmetered (`requestNetwork`, so the
 * system also keeps one up for the app if it can), and transfers are bound to exactly the
 * network it gives ([BoundRoute]). When that network goes, or the system says it has turned
 * metered, there is no route, at once, on the thread the system says so on.
 *
 * What the phone's *default* network is does not come into it: with Wi-Fi and mobile data
 * both up, the default can flip to mobile data (a Wi-Fi with no internet beyond it, a weak
 * one) while the Wi-Fi is still there; a connection made on the default route then goes out
 * over mobile data with nothing to say so. A connection bound to the Wi-Fi cannot.
 *
 * Whether Android has "validated" the network is not asked (see [NetworkWatch]).
 */
class UnmeteredNetwork(context: Context, private val http: OkHttpClient, private val note: (String) -> Unit = {}) : UnmeteredRoutes {
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val route = MutableStateFlow<BoundRoute?>(null)
    override val now: StateFlow<BoundRoute?> = route.asStateFlow()

    private var asked = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Asked of the capabilities themselves, every time they change: the same network
            // turning metered is a change of its capabilities.
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) take(network) else drop(network)
        }

        override fun onLost(network: Network) = drop(network)

        override fun onUnavailable() {
            route.value?.let { drop(it.network) }
        }
    }

    private fun take(network: Network) {
        if (route.value?.network == network) return
        route.value?.let { drop(it.network) }
        route.value = BoundRoute(network, boundTo(http, network))
    }

    private fun drop(network: Any) {
        val was = route.value?.takeIf { it.network == network } ?: return
        route.value = null
        // Its connections are that network's: none is kept to be used again.
        runCatching { was.client.connectionPool.evictAll() }
    }

    override fun want(on: Boolean) = onMain {
        if (on == asked) return@onMain
        asked = on
        val manager = connectivity ?: return@onMain
        if (!on) {
            runCatching { manager.unregisterNetworkCallback(callback) }
            route.value?.let { drop(it.network) }
            return@onMain
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            // A VPN counts, as it does for the job (see DownloadKeeper.job).
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            manager.requestNetwork(request, callback, main)
        } catch (e: RuntimeException) {
            // Not to be had that way (too many requests; a system that won't allow it):
            // watched for instead, which gives the same network when there is one.
            try {
                manager.registerNetworkCallback(request, callback, main)
            } catch (e: RuntimeException) {
                asked = false
                note("downloads: Android would not say which network is unmetered (${e.javaClass.simpleName}); Wi-Fi only waits")
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        /** `http`, with every connection made on `network` and none shared with any other
         *  client's. */
        fun boundTo(http: OkHttpClient, network: Network): OkHttpClient = http.newBuilder()
            .socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }
            .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
            .build()
    }
}

/**
 * The phone's default route, offered as the unmetered one while the system reports it as
 * unmetered. For where there is no Android to ask for a network of its own (tests of other
 * things): the app itself uses [UnmeteredNetwork].
 */
class DefaultRouteWhileUnmetered(network: StateFlow<NetworkNow>, http: OkHttpClient, scope: kotlinx.coroutines.CoroutineScope) : UnmeteredRoutes {
    private val whole = BoundRoute("default", http)
    private val route = MutableStateFlow(whole.takeIf { network.value.connected && network.value.unmetered })
    override val now: StateFlow<BoundRoute?> = route.asStateFlow()

    init {
        scope.launchUndispatched { network.collect { route.value = whole.takeIf { _ -> it.connected && it.unmetered } } }
    }

    override fun want(on: Boolean) = Unit
}

private fun kotlinx.coroutines.CoroutineScope.launchUndispatched(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    launch(kotlinx.coroutines.Dispatchers.Unconfined, kotlinx.coroutines.CoroutineStart.UNDISPATCHED, block)
}
