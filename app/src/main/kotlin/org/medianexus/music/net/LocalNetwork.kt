package org.medianexus.music.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether reaching a server needs Android 17's local-network permission
 * (`ACCESS_LOCAL_NETWORK`). From Android 17, an app that targets it can't reach addresses on
 * the network the phone is on (a home server at 192.168.1.10, say) without it: the connection
 * simply times out. Google's definition of a local network address
 * (developer.android.com/privacy-and-security/local-network-definition) lists:
 *
 * - IPv4: 10/8, 172.16/12, 192.168/16, link-local 169.254/16, the shared range 100.64/10
 *   (which Tailscale's addresses are in), and multicast and broadcast;
 * - IPv6: link-local and other directly connected addresses (taken here as fe80::/10 and the
 *   unique-local fc00::/7) and multicast;
 * - names ending in `.local`, which are looked up on the local network itself.
 *
 * The phone itself (127.0.0.1, ::1) is not on it. A name is judged by what it stands for, as
 * the system judges each connection by its address, so a name that leads to 192.168.x.x
 * needs the permission too. Plain Kotlin, so it can be tested on its own.
 */
object LocalNetwork {
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
     * What can be said from the host alone: true or false for an address written out (or a
     * `.local` name), null for a name that has to be looked up first.
     */
    fun judgeHost(host: String): Boolean? {
        if (host.isEmpty()) return false
        if (host == "localhost") return false
        if (host.endsWith(".local")) return true
        parseIpv4(host)?.let { return isLocalV4(it) }
        parseIpv6(host)?.let { return isLocalV6(it) }
        return null
    }

    /** Whether any of the addresses a name stands for is on the local network. */
    fun anyLocal(addresses: List<InetAddress>): Boolean = addresses.any { address ->
        when (address) {
            is Inet4Address -> isLocalV4(address.address.map { it.toInt() and 0xff }.toIntArray())
            is Inet6Address -> {
                val bytes = address.address
                isLocalV6(IntArray(8) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) })
            }
            else -> false
        }
    }

    internal fun isLocalV4(ip: IntArray): Boolean {
        val (a, b) = ip[0] to ip[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) ||
            a in 224..239 ||
            ip.all { it == 255 }
    }

    internal fun isLocalV6(ip: IntArray): Boolean {
        // An IPv4 address written as IPv6 (::ffff:a.b.c.d) is judged as IPv4.
        if (ip.take(5).all { it == 0 } && ip[5] == 0xffff) {
            return isLocalV4(intArrayOf(ip[6] shr 8, ip[6] and 0xff, ip[7] shr 8, ip[7] and 0xff))
        }
        val first = ip[0]
        return (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00 || (first and 0xff00) == 0xff00
    }
}
