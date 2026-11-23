package org.medianexus.music.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun homeAddressesNeedIt() {
        for (host in listOf("192.168.1.10", "10.0.2.2", "172.16.0.5", "172.31.255.1", "169.254.3.4", "100.64.0.1", "100.101.102.103", "224.0.0.251", "fe80::1", "fd12:3456::1", "nas.local", "::ffff:192.168.0.2")) {
            assertEquals(host, true, LocalNetwork.judgeHost(host))
        }
    }

    @Test
    fun thePhoneItselfAndTheInternetDont() {
        for (host in listOf("127.0.0.1", "localhost", "::1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "")) {
            assertEquals(host, false, LocalNetwork.judgeHost(host))
        }
    }

    @Test
    fun aNameIsJudgedByWhatItStandsFor() {
        assertNull(LocalNetwork.judgeHost("music.example.org"))
        assertTrue(LocalNetwork.anyLocal(listOf(InetAddress.getByAddress("nas", byteArrayOf(192.toByte(), 168.toByte(), 1, 20)))))
        assertFalse(LocalNetwork.anyLocal(listOf(InetAddress.getByAddress("cloud", byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))))
        assertFalse(LocalNetwork.anyLocal(emptyList()))
    }
}
