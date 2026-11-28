package org.medianexus.music.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether reaching a server needs Android 17's local-network permission
 * (`ACCESS_LOCAL_NETWORK`). From Android 17, an app that targets it can't reach addresses on
 * the network the phone is on (a home server at 192.168.1.10, say) without it: the connection
 * simply times out.
 *
 * Google's definition (developer.android.com/privacy-and-security/local-network-definition)
 * is of a *network*, not only of addresses: "an IP network that utilizes a broadcast-capable
 * network interface, such as Wi-Fi or Ethernet, but excludes cellular (WWAN) or VPN
 * connections". Its table lists 10/8, 172.16/12, 192.168/16, link-local 169.254/16, the
 * shared range 100.64/10, IPv6 link-local and directly connected routes, and multicast and
 * broadcast. So what an address is can't always settle it; the route to it does, and the
 * app can't see that before connecting. Three answers, then:
 *
 * - [Nearness.LOCAL]: certainly local. The private ranges, link-local, multicast and
 *   broadcast, and `.local` names. Here the app explains and asks *before* connecting.
 *   (`.local` isn't in Google's table; it is counted here on purpose: such a name is only
 *   ever answered by a device on the same link, so it always leads to a local address.)
 * - [Nearness.MAYBE]: it depends on the route. 100.64/10 is both Tailscale's range (through
 *   a VPN, so not local) and carriers' (over mobile data, not local either), and only local
 *   on a home network that happens to use it; the unique-local IPv6 range fc00::/7 is a
 *   home's own (local) or a VPN's (Tailscale's fd7a:115c:a1e0::/48, not); and a name is
 *   whatever it leads to, which may change from one network to the next. Here the app
 *   connects first, and only if that fails without the permission says this may be why.
 *   We lean towards "maybe" rather than "local" deliberately: asking for a permission that
 *   wasn't needed, before anything has gone wrong, is worse than saying "this may be why"
 *   once something has.
 * - [Nearness.NOT]: the phone itself (127.0.0.1, ::1, `localhost`) and public addresses.
 *
 * Plain Kotlin, so it can be tested on its own.
 */
object LocalNetwork {
    enum class Nearness { LOCAL, MAYBE, NOT }

    /** The host part of a server's address ("192.168.1.10" from "http://192.168.1.10:4533/"),
     *  without brackets for IPv6; empty if there's none. */
    fun hostOf(address: String): String {
        val (_, rest) = splitScheme(address.trim())
        val authority = rest.split('/', '?', '#').first().substringAfterLast('@')
        return if (authority.startsWith('[')) {
            authority.removePrefix("[").substringBefore(']')
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) authority.substring(0, colon) else authority
        }.lowercase()
    }

    /**
     * What can be said from the host alone: a [Nearness] for an address written out (or a
     * `.local` name), null for a name that has to be looked up first.
     */
    fun judgeHost(host: String): Nearness? {
        if (host.isEmpty() || host == "localhost") return Nearness.NOT
        if (host.endsWith(".local")) return Nearness.LOCAL
        parseIpv4(host)?.let { return v4(it) }
        parseIpv6(host)?.let { return v6(it) }
        return null
    }

    /** A name, by what it was found to stand for: maybe local if any of it could be (the
     *  route decides), else not. A name is never certainly local: see above. */
    fun judgeFound(addresses: List<InetAddress>): Nearness =
        if (addresses.any { judgeAddress(it) != Nearness.NOT }) Nearness.MAYBE else Nearness.NOT

    fun judgeAddress(address: InetAddress): Nearness = when (address) {
        is Inet4Address -> v4(address.address.map { it.toInt() and 0xff }.toIntArray())
        is Inet6Address -> {
            val bytes = address.address
            v6(IntArray(8) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) })
        }
        else -> Nearness.NOT
    }

    internal fun v4(ip: IntArray): Nearness {
        val (a, b) = ip[0] to ip[1]
        return when {
            a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) -> Nearness.LOCAL
            a in 224..239 || ip.all { it == 255 } -> Nearness.LOCAL
            a == 100 && b in 64..127 -> Nearness.MAYBE
            else -> Nearness.NOT
        }
    }

    internal fun v6(ip: IntArray): Nearness {
        // An IPv4 address written as IPv6 (::ffff:a.b.c.d) is judged as IPv4.
        if (ip.take(5).all { it == 0 } && ip[5] == 0xffff) {
            return v4(intArrayOf(ip[6] shr 8, ip[6] and 0xff, ip[7] shr 8, ip[7] and 0xff))
        }
        val first = ip[0]
        return when {
            (first and 0xffc0) == 0xfe80 || (first and 0xff00) == 0xff00 -> Nearness.LOCAL
            (first and 0xfe00) == 0xfc00 -> Nearness.MAYBE
            else -> Nearness.NOT
        }
    }
}

/**
 * **Android 17's local-network permission, judged at the address actually connected to, before
 * any socket is opened** (the backend design's 6.4): OkHttp asks this for a name's addresses at
 * each new connection, whatever asked (the music server, the Nexus server, the first request or
 * a renewal). Without the permission, an address inside the house is never tried: a connection to
 * it would be dropped by the system in silence, a timeout later. A name that leads both inside and
 * out is connected to only outside; one that leads only inside fails at once, as never connected
 * ([LocalNetworkBlocked]), and `blocked` is told the name. With the permission held (always,
 * before Android 17) the system's answer is used as it is.
 */
class LocalDns(private val held: () -> Boolean, private val blocked: (String) -> Unit = {}, private val system: okhttp3.Dns = okhttp3.Dns.SYSTEM) : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = system.lookup(hostname)
        if (held()) return all
        val outside = all.filter { LocalNetwork.judgeAddress(it) != LocalNetwork.Nearness.LOCAL }
        if (outside.isNotEmpty()) return outside
        blocked(hostname)
        throw LocalNetworkBlocked()
    }
}

/** An address inside the house, and the local-network permission not held: not connected to.
 *  Its message names nothing. */
class LocalNetworkBlocked : java.io.IOException("The app may not reach devices on the home network, so this wasn't sent.")
