package org.medianexus.music.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Windows' `reads_host_links` and `reads_jam_links` (src/jam.rs), ported as they are. */
class LinksTest {
    private fun host(text: String): Pair<String, String>? = (Links.hostLink(text) as? Links.Read.Ok)?.value?.let { it.address to it.key }
    private fun jam(text: String): Pair<String, String>? = (Links.jamLink(text) as? Links.Read.Ok)?.value?.let { it.address to it.jam }

    @Test
    fun readsHostLinks() {
        assertEquals("https://jam.example.org" to "abcdefghijklmnop", host(" https://jam.example.org/#abcdefghijklmnop "))
        // The whole line from the relay's log, pasted as it stands.
        assertEquals("https://jam.example.org" to "abcdefghijklmnop", host("Host link, for Nexus Music's settings (keep it private): https://jam.example.org/#abcdefghijklmnop\n"))
        // Under a path on a shared address, and with no scheme typed.
        assertEquals("https://example.org/jam" to "abcdefghijklmnop", host("example.org/jam/#abcdefghijklmnop"))
        assertEquals("http://192.168.1.5:8080" to "abcdefghijklmnop", host("http://192.168.1.5:8080/#abcdefghijklmnop"))
        // No key, a key that's too short, and a key that would cross the internet in the clear.
        assertEquals(Links.Read.Wrong(LinkTrouble.NO_KEY), Links.hostLink("https://jam.example.org/"))
        assertEquals(Links.Read.Wrong(LinkTrouble.SHORT_KEY), Links.hostLink("https://jam.example.org/#short"))
        assertEquals(Links.Read.Wrong(LinkTrouble.PLAIN_HTTP), Links.hostLink("http://jam.example.org/#abcdefghijklmnop"))
        assertNull(host("#abcdefghijklmnop"))
    }

    @Test
    fun readsJamLinks() {
        assertEquals("https://jam.example.org" to "abc234", jam("https://jam.example.org/j/abc234"))
        assertEquals("https://jam.example.org" to "abc234", jam(" jam.example.org/j/abc234/ "))
        assertEquals("https://example.org/jam" to "abc234", jam("https://example.org/jam/j/abc234?from=chat#top"))
        assertEquals("http://localhost:8080" to "abc234", jam("http://localhost:8080/j/abc234"))
        // A host link isn't a jam link, and nor is anything else.
        assertNull(jam("https://jam.example.org/#abcdefghijklmnop"))
        assertNull(jam("https://jam.example.org/j/"))
        assertNull(jam("https://jam.example.org/j/../x"))
        assertEquals(Links.Read.Wrong(LinkTrouble.PLAIN_HTTP), Links.jamLink("http://jam.example.org/j/abc234"))
        assertNull(jam("abc234"))
        assertEquals("jam.example.org", Links.hostOf("https://jam.example.org/sub"))
    }

    @Test
    fun findsAJamLinkInAMessage() {
        assertEquals("https://jam.example.org/j/abc234", Links.findJamLink("Come and join: https://jam.example.org/j/abc234. See you!")?.text)
        assertEquals("https://jam.example.org/j/abc234", Links.findJamLink("(https://jam.example.org/j/abc234)")?.text)
        assertNull(Links.findJamLink("no link here"))
        assertNull(Links.findJamLink("http://jam.example.org/j/abc234"))
        assertNull(Links.findJamLink("x".repeat(5000) + " https://jam.example.org/j/abc234"))
    }

    @Test
    fun theKeyIsNeverWrittenOutByTheLinkItself() {
        val link = (Links.hostLink("https://jam.example.org/#abcdefghijklmnop") as Links.Read.Ok).value
        assertTrue("abcdefghijklmnop" !in link.toString())
    }
}
