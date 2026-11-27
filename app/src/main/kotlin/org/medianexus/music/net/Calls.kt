package org.medianexus.music.net

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * The HTTP calls made through it, all of which can be cancelled at once: a read blocked on a
 * stalled connection ends there and then (OkHttp's `Call.cancel`, which is safe from any
 * thread). For fetching ahead, which must let go of a song at once when the network changes
 * the form it is to be fetched in.
 */
class CancellableCalls {
    private val live: MutableSet<Call> = Collections.newSetFromMap(WeakHashMap())

    /** `calls`, with every call made through it counted in. */
    fun through(calls: Call.Factory): Call.Factory = Call.Factory { request ->
        calls.newCall(request).also { call -> synchronized(live) { live += call } }
    }

    fun cancelAll() {
        val now = synchronized(live) { live.toList().also { live.clear() } }
        now.forEach(Call::cancel)
    }
}

/**
 * Clients for audio bound to one network each (see `StreamQuality`: a form above mobile
 * data's limit goes out only on the unmetered network it was chosen for). The last network's
 * is kept: it is the same network song after song.
 */
class BoundClients(private val http: OkHttpClient) {
    private var last: Pair<Any, OkHttpClient>? = null

    @Synchronized
    fun on(network: Any): OkHttpClient {
        last?.let { (was, client) -> if (was == network) return client }
        val client = if (network is android.net.Network) {
            http.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
                .build()
        } else {
            http
        }
        last = network to client
        return client
    }
}
