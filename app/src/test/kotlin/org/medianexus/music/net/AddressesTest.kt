package org.medianexus.music.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from the tests in the Windows app's src/subsonic.rs.
class AddressesTest {
    @Test
    fun triesHttpsFirstAndKnowsWhenPlainHttpMatters() {
        // Unlike Windows: with no scheme typed, plain HTTP is tried only on the home network.
        assertEquals(listOf("https://music.example.org"), addressesToTry(" music.example.org "))
        assertEquals(listOf("https://192.168.1.5:4533", "http://192.168.1.5:4533"), addressesToTry("192.168.1.5:4533"))
        assertEquals(listOf("https://nas:4533", "http://nas:4533"), addressesToTry("nas:4533"))
        assertEquals(listOf("http://192.168.1.5:4533"), addressesToTry("http://192.168.1.5:4533"))
        // Typed with "http://" it is taken at its word, in whatever case.
        assertEquals(listOf("http://music.example.org"), addressesToTry("http://music.example.org"))
        assertEquals(listOf("HTTP://music.example.org"), addressesToTry("HTTP://music.example.org"))
        assertEquals(listOf("https://music.example.org/"), addressesToTry("https://music.example.org/"))
        assertTrue(addressesToTry("  ").isEmpty())
        // Plain HTTP only matters where someone else could be listening.
        for (home in listOf(
            "http://192.168.1.5:4533", "http://10.0.0.2", "http://localhost:4533", "http://nas:4533", "http://nas.local",
            "http://music.lan/", "http://100.96.1.2:4533", "http://127.0.0.1:4533", "http://[::1]:4533", "http://[fd00::5]:4533",
        )) {
            assertFalse(home, unencryptedBeyondHome(home))
        }
        for (away in listOf(
            "http://music.example.org", "http://music.example.org:4533/x", "http://8.8.8.8:4533", "http://100.200.1.2",
            "http://[2001:db8::1]:4533",
        )) {
            assertTrue(away, unencryptedBeyondHome(away))
        }
        assertFalse(unencryptedBeyondHome("https://music.example.org"))
        // The scheme is read, not compared as text.
        assertTrue(unencryptedBeyondHome("HTTP://music.example.org"))
        assertTrue(unencryptedBeyondHome(" Http://music.example.org"))
        assertFalse(unencryptedBeyondHome("HTTPS://music.example.org"))
        assertFalse(unencryptedBeyondHome("HTTP://192.168.1.5"))
        assertFalse(unencryptedBeyondHome("music.example.org"))
    }

    @Test
    fun readsTheSchemeInAnyCase() {
        assertEquals("http" to "music.example.org", splitScheme("HTTP://music.example.org"))
        assertEquals("https" to "x/y", splitScheme("hTtPs://x/y"))
        assertEquals(null to "music.example.org:4533", splitScheme("music.example.org:4533"))
        assertEquals(null to "://x", splitScheme("://x"))
        assertEquals("http://tower:4533", normalizeServerUrl("HTTP://tower:4533"))
    }

    @Test
    fun normalizesTypedAddresses() {
        assertEquals("http://192.168.1.10:4533", normalizeServerUrl("192.168.1.10:4533"))
        assertEquals("https://music.example.com", normalizeServerUrl(" https://music.example.com/ "))
        assertEquals("http://tower:4533", normalizeServerUrl("http://tower:4533/app/#/album"))
        assertEquals("http://tower/music", normalizeServerUrl("http://tower/music/"))
    }

    @Test
    fun encodesQueryValues() {
        assertEquals("Marrow%20%26%20Pine", encode("Marrow & Pine"))
        assertEquals("%C3%89tudes", encode("Études"))
    }

    // Beyond the Windows tests: the address readers that stand in for Rust's.

    @Test
    fun readsIpAddressesAsRustDoes() {
        assertEquals(listOf(192, 168, 1, 5), parseIpv4("192.168.1.5")?.toList())
        for (not in listOf("192.168.1", "192.168.1.256", "1.2.3.04", "a.b.c.d", "1..2.3", "")) assertNull(not, parseIpv4(not))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), parseIpv6("::1")?.toList())
        assertEquals(listOf(0xfd00, 0, 0, 0, 0, 0, 0, 5), parseIpv6("fd00::5")?.toList())
        assertEquals(listOf(0, 0, 0, 0, 0, 0xffff, 0xc0a8, 0x0105), parseIpv6("::ffff:192.168.1.5")?.toList())
        for (not in listOf("nas", "1:2:3", "1::2::3", "12345::1", "g::1")) assertNull(not, parseIpv6(not))
    }

    @Test
    fun aSignInInTheAddressDoesNotHideTheHost() {
        assertTrue(unencryptedBeyondHome("http://someone@music.example.org"))
        assertFalse(unencryptedBeyondHome("http://someone@192.168.1.5:4533"))
    }
}
