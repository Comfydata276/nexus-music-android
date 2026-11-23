package org.medianexus.music.net

// Server addresses as typed, ported from src/subsonic.rs so both apps read them alike.

/** Tidies what the user typed into a base URL: adds a scheme, drops trailing slashes and any
 *  `/app/...` path copied from the browser's address bar. */
fun normalizeServerUrl(input: String): String {
    var url = input.trim()
    if (url.isEmpty()) return url
    if ("://" !in url) url = "http://$url"
    val app = url.indexOf("/app/")
    if (app >= 0) url = url.substring(0, app)
    for (suffix in listOf("/app", "/rest")) {
        if (url.endsWith(suffix)) url = url.dropLast(suffix.length)
    }
    while (url.endsWith('/') || url.endsWith('#')) url = url.dropLast(1)
    return url
}

/** The addresses to try for what was typed as the server's. One that says "http://" or
 *  "https://" is taken at its word. One that doesn't is tried over HTTPS first, and over
 *  plain HTTP only if that gets nowhere. */
fun addressesToTry(typed: String): List<String> {
    val address = typed.trim()
    return when {
        address.isEmpty() -> emptyList()
        "://" in address -> listOf(address)
        else -> listOf("https://$address", "http://$address")
    }
}

/** Whether an address is plain HTTP to somewhere that isn't on the home network (or this
 *  phone): the case where what's sent can be read by others on the way. */
fun unencryptedBeyondHome(address: String): Boolean {
    if (!address.startsWith("http://")) return false
    val rest = address.removePrefix("http://")
    val authority = rest.split('/', '?', '#').first()
    val hostAndPort = authority.substringAfterLast('@')
    // An IPv6 address is in brackets, with the port after them.
    val host = if (hostAndPort.startsWith('[')) {
        hostAndPort.removePrefix("[").substringBefore(']')
    } else {
        val colon = hostAndPort.lastIndexOf(':')
        if (colon >= 0) hostAndPort.substring(0, colon) else hostAndPort
    }.lowercase()
    parseIpv4(host)?.let { ip ->
        val (a, b) = ip[0] to ip[1]
        val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        val loopback = a == 127
        val linkLocal = a == 169 && b == 254
        // The range Tailscale and similar use.
        val carrier = a == 100 && b in 64 until 128
        return !(private || loopback || linkLocal || carrier)
    }
    parseIpv6(host)?.let { ip ->
        val loopback = ip.take(7).all { it == 0 } && ip[7] == 1
        return !(loopback || (ip[0] and 0xfe00) == 0xfc00 || (ip[0] and 0xffc0) == 0xfe80)
    }
    val local = listOf("localhost", ".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")
    return !(host.isEmpty() || '.' !in host || local.any { ending -> host == ending.trimStart('.') || host.endsWith(ending) })
}

/** The four parts of a dotted IPv4 address, or null if it isn't one. */
internal fun parseIpv4(text: String): IntArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        // As Rust reads them: "01" is not an octet.
        if (part.length > 1 && part[0] == '0') return null
        octets[index] = part.toInt().takeIf { it <= 255 } ?: return null
    }
    return octets
}

/** The eight 16-bit groups of an IPv6 address, or null if it isn't one. Reads `::` and a
 *  dotted IPv4 tail. */
internal fun parseIpv6(text: String): IntArray? {
    if (':' !in text) return null
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        val pieces = part.split(':')
        for ((index, piece) in pieces.withIndex()) {
            if (index == pieces.lastIndex && '.' in piece) {
                val v4 = parseIpv4(piece) ?: return null
                out += (v4[0] shl 8) or v4[1]
                out += (v4[2] shl 8) or v4[3]
            } else {
                if (piece.isEmpty() || piece.length > 4) return null
                out += piece.toIntOrNull(16) ?: return null
            }
        }
        return out
    }
    val halves = text.split("::")
    return when (halves.size) {
        1 -> groups(text)?.takeIf { it.size == 8 }?.toIntArray()
        2 -> {
            val head = groups(halves[0]) ?: return null
            val tail = groups(halves[1]) ?: return null
            if (head.size + tail.size > 7) return null
            (head + List(8 - head.size - tail.size) { 0 } + tail).toIntArray()
        }
        else -> null
    }
}

/** Percent-encodes a query value, leaving only the characters that never need it. */
fun encode(input: String): String {
    val out = StringBuilder(input.length + 8)
    for (byte in input.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xff
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
            out.append(c)
        } else {
            out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
