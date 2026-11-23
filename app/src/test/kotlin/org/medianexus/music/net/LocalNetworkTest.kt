package org.medianexus.music.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which servers need Android 17's local-network permission, by Google's definition. */
class LocalNetworkTest {
    @Test
    fun findsTheHostInWhatWasTyped() {
        assertEquals("192.168.1.10", LocalNetwork.hostOf("http://192.168.1.10:4533"))
        assertEquals("music.example.org", LocalNetwork.hostOf("https://me@Music.Example.org/navidrome/"))
        assertEquals("fe80::1", LocalNetwork.hostOf("http://[fe80::1]:4533"))
        assertEquals("nas.local", LocalNetwork.hostOf("nas.local:4533"))
    }

    @Test
    fun homeAddressesCertainlyNeedIt() {
        for (host in listOf("192.168.1.10", "10.0.2.2", "172.16.0.5", "172.31.255.1", "169.254.3.4", "224.0.0.251", "255.255.255.255", "fe80::1", "ff02::fb", "nas.local", "::ffff:192.168.0.2")) {
            assertEquals(host, LocalNetwork.Nearness.LOCAL, LocalNetwork.judgeHost(host))
        }
    }

    @Test
    fun vpnAndCarrierRangesDependOnTheRoute() {
        // Tailscale's IPv4 and IPv6 ranges, a carrier's shared range, a home's own IPv6.
        for (host in listOf("100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::1", "fd12:3456::1")) {
            assertEquals(host, LocalNetwork.Nearness.MAYBE, LocalNetwork.judgeHost(host))
        }
    }

    @Test
    fun thePhoneItselfAndTheInternetDont() {
        for (host in listOf("127.0.0.1", "localhost", "::1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255", "2001:db8::1", "")) {
            assertEquals(host, LocalNetwork.Nearness.NOT, LocalNetwork.judgeHost(host))
        }
    }

    @Test
    fun aNameIsNeverCertainOnlyMaybe() {
        assertNull(LocalNetwork.judgeHost("music.example.org"))
        val home = InetAddress.getByAddress("nas", byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val cloud = InetAddress.getByAddress("cloud", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        assertEquals(LocalNetwork.Nearness.MAYBE, LocalNetwork.judgeFound(listOf(home)))
        assertEquals(LocalNetwork.Nearness.MAYBE, LocalNetwork.judgeFound(listOf(cloud, home)))
        assertEquals(LocalNetwork.Nearness.NOT, LocalNetwork.judgeFound(listOf(cloud)))
        assertEquals(LocalNetwork.Nearness.NOT, LocalNetwork.judgeFound(emptyList()))
    }
}
