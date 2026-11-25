package org.medianexus.music.ui.parts

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

/**
 * Covers that are web addresses (albums and artists not in the library): **where the loader
 * will go** ([CoverRule]), each refusal on its own, and what the loader carries and keeps
 * ([WebCovers]). The stand-in server is on this machine, so it is Lidarr's own address in
 * these tests (the one place the rule lets a local address through), or a place the rule
 * must keep the loader away from.
 */
class WebCoversTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val limit: Timeout = Timeout.seconds(60)

    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val looked = CopyOnWriteArrayList<String>()

    /** Every name looks up to this machine, as a hostile name could be made to. */
    private val here = Dns { host ->
        looked += host
        listOf(InetAddress.getByName("127.0.0.1"))
    }

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.close()
        other.close()
    }

    private fun covers(lookup: Dns = here) = WebCovers(folder.newFolder(), lookup, kotlinx.coroutines.Dispatchers.IO)

    private val lidarr get() = "http://127.0.0.1:${server.port}"
    private val picture = ByteArray(64) { it.toByte() }

    private fun pictureReply() = MockResponse.Builder().setHeader("Content-Type", "image/jpeg").body(okio.Buffer().write(picture)).build()

    private fun redirect(to: String) = MockResponse.Builder().code(302).setHeader("Location", to).build()

    private fun read(snapshot: coil3.disk.DiskCache.Snapshot?): ByteArray? = snapshot?.use { FileSystem.SYSTEM.read(it.data) { readByteArray() } }

    // ---- the rule, address by address -------------------------------------------------------------

    private fun allows(address: String, lidarr: String? = "http://192.168.1.20:8686", music: String? = "https://music.example") =
        CoverRule.allows(address.toHttpUrl(), lidarr?.toHttpUrl(), music?.toHttpUrl())

    @Test
    fun httpsOutOnTheInternetIsAllowed() {
        assertTrue(allows("https://coverartarchive.org/release-group/abc/front-250"))
        assertTrue(allows("https://93.184.216.34/cover.jpg"))
        assertTrue(allows("https://[2606:2800:220:1::1]/cover.jpg"))
    }

    @Test
    fun plainHttpIsRefusedUnlessItIsLidarrsOwnAddress() {
        assertFalse(allows("http://assets.fanart.tv/fanart/music/abc.jpg"))
        assertTrue(allows("http://192.168.1.20:8686/MediaCover/1/poster.jpg"))
        // Lidarr's host, but not the scheme and port the person typed.
        assertFalse(allows("http://192.168.1.20:8080/MediaCover/1/poster.jpg"))
        assertFalse(allows("https://192.168.1.20:8686/MediaCover/1/poster.jpg"))
        assertFalse(allows("http://192.168.1.20/MediaCover/1/poster.jpg"))
    }

    @Test
    fun theMusicServersOriginIsRefusedWhateverElseIsTrue() {
        assertFalse(allows("https://music.example/rest/getCoverArt?id=1"))
        assertFalse(allows("https://music.example:443/x"))
        // Even if Lidarr were given the same address as the music server.
        assertFalse(allows("https://music.example/x", lidarr = "https://music.example"))
        // Another port of the same name is somewhere else, and judged as anywhere is.
        assertTrue(allows("https://music.example:8443/x"))
    }

    @Test
    fun loopbackLinkLocalAndPrivateAddressesAreRefused() {
        for (address in listOf(
            "https://127.0.0.1/x", "https://127.9.9.9/x", "https://[::1]/x", "https://0.0.0.0/x",
            "https://169.254.169.254/latest/meta-data", "https://[fe80::1]/x",
            "https://10.0.0.5/x", "https://172.16.4.4/x", "https://172.31.255.255/x", "https://192.168.1.1/x",
            "https://100.64.0.1/x", "https://100.127.255.254/x", "https://[fd12:3456::1]/x", "https://[fc00::1]/x",
            "https://[::ffff:127.0.0.1]/x", "https://[::ffff:192.168.1.1]/x", "https://224.0.0.1/x", "https://255.255.255.255/x",
        )) {
            assertFalse(address, allows(address, lidarr = null))
        }
        // Public ones on either side of the private ranges are not.
        assertTrue(allows("https://172.32.0.1/x"))
        assertTrue(allows("https://100.128.0.1/x"))
    }

    @Test
    fun aNameThatStandsForALocalAddressIsRefusedWhenItIsLookedUp() {
        val dns = CoverRule.publicOnly { host ->
            when (host) {
                "home.example" -> listOf(InetAddress.getByName("192.168.1.10"))
                "mixed.example" -> listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
                else -> listOf(InetAddress.getByName("93.184.216.34"))
            }
        }
        for (host in listOf("home.example", "mixed.example")) {
            try {
                dns.lookup(host)
                fail("$host should have been refused")
            } catch (_: UnknownHostException) {
            }
        }
        assertEquals(1, dns.lookup("covers.example").size)
    }

    // ---- the loader ----------------------------------------------------------------------------------

    @Test
    fun aCoverIsFetchedOnceAndKeptCarryingNothingButTheAppsName() = runBlocking {
        server.enqueue(pictureReply())
        val covers = covers()
        val address = "$lidarr/MediaCover/1/poster.jpg"
        assertArrayEquals(picture, read(covers.fetch(address, lidarr, "https://music.example")))
        assertArrayEquals(picture, read(covers.fetch(address, lidarr, "https://music.example")))
        assertEquals("kept, not fetched again", 1, server.requestCount)
        val asked = server.takeRequest()
        assertNull(asked.headers["Authorization"])
        assertNull(asked.headers["Cookie"])
        assertTrue(asked.headers["User-Agent"].orEmpty().startsWith("NexusMusic/"))
    }

    @Test
    fun anythingFromTheMusicServersOriginIsNeverAskedFor() = runBlocking {
        server.enqueue(pictureReply())
        // The music server and Lidarr at the same place: the music server wins.
        assertNull(covers().fetch("$lidarr/rest/getCoverArt?id=1", lidarr, lidarr))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun plainHttpElsewhereIsNeverLookedUpOrAskedFor() = runBlocking {
        assertNull(covers().fetch("http://covers.example:${other.port}/x.jpg", lidarr, null))
        assertTrue(looked.isEmpty())
        assertEquals(0, other.requestCount)
    }

    @Test
    fun anHttpsNameThatLooksUpToThisMachineIsRefusedBeforeAnythingIsSent() = runBlocking {
        assertNull(covers().fetch("https://covers.example:${other.port}/x.jpg", lidarr, null))
        assertEquals(listOf("covers.example"), looked)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun aRedirectIsJudgedAsAnyAddressIs() = runBlocking {
        // From Lidarr's own address to another port on the same machine (loopback, and not
        // Lidarr's): refused, and never asked.
        server.enqueue(redirect("http://127.0.0.1:${other.port}/x.jpg"))
        other.enqueue(pictureReply())
        assertNull(covers().fetch("$lidarr/MediaCover/1/poster.jpg", lidarr, null))
        assertEquals(0, other.requestCount)
        // Nor to the music server.
        server.enqueue(redirect("https://music.example/rest/getCoverArt?id=1"))
        assertNull(covers().fetch("$lidarr/MediaCover/2/poster.jpg", lidarr, "https://music.example"))
        assertTrue(looked.isEmpty())
    }

    @Test
    fun fiveRedirectsAreFollowedAndNoMore() = runBlocking {
        repeat(5) { server.enqueue(redirect("/hop$it")) }
        server.enqueue(pictureReply())
        assertNotNull(read(covers().fetch("$lidarr/start", lidarr, null)))
        assertEquals(6, server.requestCount)
        repeat(6) { server.enqueue(redirect("/again$it")) }
        server.enqueue(pictureReply())
        assertNull(covers().fetch("$lidarr/start-again", lidarr, null))
        assertEquals("the sixth redirect is not followed", 12, server.requestCount)
    }

    @Test
    fun aCoverLargerThanTheLimitIsGivenUpAndNothingOfItIsKept() = runBlocking {
        val big = okio.Buffer().write(ByteArray((WebCovers.LIMIT + 1).toInt()))
        // (Said to be its size, and not said: both given up.)
        server.enqueue(MockResponse.Builder().body(big.copy()).build())
        server.enqueue(MockResponse.Builder().chunkedBody(big, 64 * 1024).build())
        val covers = covers()
        assertNull(covers.fetch("$lidarr/big.jpg", lidarr, null))
        assertNull(covers.fetch("$lidarr/big.jpg", lidarr, null))
        assertEquals(2, server.requestCount)
    }
}
