package org.medianexus.music.jam

import org.medianexus.music.net.unencryptedBeyondHome

/**
 * The two links a person pastes: a relay's host link (its address, then `#` and the host key)
 * and a jam's link (the relay's address, then `/j/` and the jam). A port of
 * `parse_host_link`, `parse_jam_link`, `address` and `host_of` in the Windows app's
 * src/jam.rs, with its tests. What's wrong with one is said by [LinkTrouble], in words the
 * screens look up (Windows' own).
 */
object Links {
    /** A host link's two parts. The key is a secret: it goes to [KeyVault] and the relay,
     *  nowhere else. */
    class HostLink(val address: String, val key: String) {
        override fun toString(): String = "HostLink($address, <key>)"
    }

    data class JamLink(val address: String, val jam: String) {
        /** The link as friends are given it. */
        val text: String get() = "$address/j/$jam"
    }

    /** One of the two, or what is wrong with it. */
    sealed interface Read<out T> {
        data class Ok<T>(val value: T) : Read<T>
        data class Wrong(val trouble: LinkTrouble) : Read<Nothing>
    }

    /** A relay's address from something pasted: `https://` is assumed, and plain HTTP is only
     *  taken for somewhere on the home network, since a key travels with every request. */
    fun address(text: String): Read<String> {
        val trimmed = text.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return Read.Wrong(LinkTrouble.NOT_A_LINK)
        val whole = if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) trimmed else "https://$trimmed"
        if (unencryptedBeyondHome(whole)) return Read.Wrong(LinkTrouble.PLAIN_HTTP)
        val host = whole.substringAfter("://", "").substringBefore('/')
        if (host.isEmpty()) return Read.Wrong(LinkTrouble.NOT_A_LINK)
        return Read.Ok(whole)
    }

    /** A host link is the relay's address with the host key after a `#`:
     *  `https://jam.example.org/#key`. */
    fun hostLink(text: String): Read<HostLink> {
        // The relay's whole log line may have been pasted: the link is the part with the # in it.
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val link = words.firstOrNull { '#' in it } ?: text.trim()
        val mark = link.indexOf('#')
        if (mark < 0) return Read.Wrong(LinkTrouble.NO_KEY)
        val key = link.substring(mark + 1).trim()
        if (key.length < 16 || key.any { it.isWhitespace() }) return Read.Wrong(LinkTrouble.SHORT_KEY)
        return when (val address = address(link.substring(0, mark))) {
            is Read.Ok -> Read.Ok(HostLink(address.value, key))
            is Read.Wrong -> address
        }
    }

    /** A jam's link is the relay's address, then `/j/` and the jam:
     *  `https://jam.example.org/j/abc`. */
    fun jamLink(text: String): Read<JamLink> {
        val trimmed = text.trim()
        val bare = trimmed.split('?', '#').first().trimEnd('/')
        val mark = bare.lastIndexOf("/j/")
        if (mark < 0) return Read.Wrong(LinkTrouble.NOT_A_JAM)
        val id = bare.substring(mark + 3)
        if (id.isEmpty() || id.length > 64 || !id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return Read.Wrong(LinkTrouble.NOT_A_JAM)
        return when (val address = address(bare.substring(0, mark))) {
            is Read.Ok -> Read.Ok(JamLink(address.value, id))
            is Read.Wrong -> address
        }
    }

    /** A jam link inside something longer (a message shared from a chat), or null. */
    fun findJamLink(text: String): JamLink? {
        if (text.length > 4096) return null
        for (word in text.split(WHITESPACE)) {
            if ("/j/" !in word) continue
            val read = jamLink(word.trim('<', '>', '(', ')', '"', '\'', ',', '.'))
            if (read is Read.Ok) return read.value
        }
        return null
    }

    /** The part of an address people recognise: `jam.example.org`. */
    fun hostOf(address: String): String {
        val rest = if ("://" in address) address.substringAfter("://") else address
        return rest.substringBefore('/')
    }

    private val WHITESPACE = Regex("\\s+")
}

/** What is wrong with a pasted link (Windows' words, in the string resources). */
enum class LinkTrouble {
    /** "That doesn't look like a link." */
    NOT_A_LINK,
    /** "That link starts with http://, which others on the way could read. Use the https:// one." */
    PLAIN_HTTP,
    /** "A host link ends with # and a key. Copy the whole link from your relay's log." */
    NO_KEY,
    /** "The key at the end of that link is too short. Copy the whole link from your relay's log." */
    SHORT_KEY,
    /** "That isn't a jam link. One looks like https://jam.example.org/j/abc123." */
    NOT_A_JAM,
}
