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
 * metered, there is no route, at once, on the thread the system says so on. **Each time a
 * network is taken it is a new route**: the same network gone and back, or metered and
 * unmetered again, is not the route a transfer began on.
 *
 * What the phone's *default* network is does not come into it: with Wi-Fi and mobile data
 * both up, the default can flip to mobile data (a Wi-Fi with no internet beyond it, a weak
 * one) while the Wi-Fi is still there; a connection made on the default route then goes out
 * over mobile data with nothing to say so. A connection bound to the Wi-Fi cannot.
 *
 * **If Android won't take the request, or says it can't be met, it is asked again**, after a
 * pause that grows (five seconds, then twice as long each time, up to five minutes), for as
 * long as it is wanted: there is no route meanwhile, which is "Waiting for Wi-Fi", and is
 * true. (A request Android refused used to be asked again only when something next
 * happened to the downloads, and one it reported unavailable, which Android then drops,
 * never: Wi-Fi coming back later was not heard of.) **When it is no longer wanted the
 * request is given up**, so the phone is not kept holding a second network up for nothing.
 *
 * Whether Android has "validated" the network is not asked (see [NetworkWatch]).
 */
class UnmeteredNetwork(
    private val http: OkHttpClient,
    private val system: Asking,
    private val note: (String) -> Unit = {},
    /** A client whose connections all go by `network` (a test's own, where there is none). */
    private val bind: (OkHttpClient, Network) -> OkHttpClient = ::boundTo,
) : UnmeteredRoutes {
    constructor(context: Context, http: OkHttpClient, note: (String) -> Unit = {}) : this(http, OfTheSystem(context), note)

    /** Android's side of it: asking for a network, watching for one, and letting go. */
    interface Asking {
        /** Asks the system for a network that meets `request` (and to keep one up if it
         *  can). Throws what Android throws when it won't take the request. */
        fun request(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, on: Handler)

        /** Only watches for one that is already there. Throws likewise. */
        fun watch(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, on: Handler)

        fun release(callback: ConnectivityManager.NetworkCallback)
    }

    private class OfTheSystem(context: Context) : Asking {
        private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
        private val manager get() = connectivity ?: throw IllegalStateException("No connectivity service")

        override fun request(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, on: Handler) = manager.requestNetwork(request, callback, on)

        override fun watch(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, on: Handler) = manager.registerNetworkCallback(request, callback, on)

        override fun release(callback: ConnectivityManager.NetworkCallback) {
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val route = MutableStateFlow<BoundRoute?>(null)
    override val now: StateFlow<BoundRoute?> = route.asStateFlow()

    /** Whether a network is wanted, and whether the system is holding a request for one. */
    private var wanted = false
    private var held = false

    /** How long before the system is asked again, after it wouldn't or couldn't. */
    private var pause = FIRST_PAUSE_MILLIS
    private var said = false
    private val again = Runnable { if (wanted && !held) ask() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Asked of the capabilities themselves, every time they change: the same network
            // turning metered is a change of its capabilities.
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) take(network) else drop(network)
        }

        override fun onLost(network: Network) = drop(network)

        override fun onUnavailable() {
            // The system can't meet the request, **and has let go of it** (as if it had been
            // unregistered): nothing more will be heard on this callback. Asked again later.
            route.value?.let { drop(it.network) }
            if (!wanted) return
            held = false
            later("Android has no unmetered network to give")
        }
    }

    private fun take(network: Network) {
        if (!wanted) return
        if (route.value?.network == network) return
        route.value?.let { drop(it.network) }
        route.value = BoundRoute(network, bind(http, network))
        // (It works: the next time it doesn't, the asking starts from a short pause again.)
        pause = FIRST_PAUSE_MILLIS
        said = false
    }

    private fun drop(network: Any) {
        val was = route.value?.takeIf { it.network == network } ?: return
        route.value = null
        // Its connections are that network's: none is kept to be used again.
        runCatching { was.client.connectionPool.evictAll() }
    }

    override fun want(on: Boolean) = onMain {
        if (on == wanted) return@onMain
        wanted = on
        main.removeCallbacks(again)
        if (!on) {
            // Given up: the system holds no network for the app that nothing is waiting for.
            if (held) system.release(callback)
            held = false
            pause = FIRST_PAUSE_MILLIS
            said = false
            route.value?.let { drop(it.network) }
            return@onMain
        }
        ask()
    }

    private fun ask() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            // A VPN counts, as it does for the job (see DownloadKeeper.job).
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        held = try {
            system.request(request, callback, main)
            true
        } catch (e: RuntimeException) {
            // Not to be had that way (too many requests; a system that won't allow it):
            // watched for instead, which gives the same network when there is one.
            try {
                system.watch(request, callback, main)
                true
            } catch (e: RuntimeException) {
                false
            }
        }
        if (!held) later("Android would not say which network is unmetered")
    }

    /** Asked again after the pause, which then grows. Said once for each spell of it. */
    private fun later(why: String) {
        if (!said) note("downloads: $why; Wi-Fi only waits, and it is asked again after a pause that grows")
        said = true
        main.removeCallbacks(again)
        main.postDelayed(again, pause)
        pause = minOf(pause * 2, MOST_PAUSE_MILLIS)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        const val FIRST_PAUSE_MILLIS = 5_000L
        const val MOST_PAUSE_MILLIS = 5 * 60_000L

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
