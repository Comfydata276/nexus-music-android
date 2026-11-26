package org.medianexus.music.ui.parts

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl
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
import org.medianexus.music.requests.RequestsBuild

/**
 * Covers that are web addresses (albums and artists not in the library): **where the loader
 * will go** ([CoverRule]), each refusal on its own, and what the loader carries, keeps and
 * when it fetches at all ([WebCovers]). The stand-in server is on this machine: it plays a
 * public site (`where` lets its own address through, and nothing else past the rule), or a
 * place the rule must keep the loader away from.
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
        RequestsBuild.forTests = null
    }

    /** The rule, but the stand-in's own address passes for a public site; the music
     *  server's origin is judged first, as the rule judges it. */
    private fun where(url: HttpUrl, music: HttpUrl?): Boolean = when {
        music != null && CoverRule.sameOrigin(url, music) -> false
        url.host == "127.0.0.1" && url.port == server.port -> true
        else -> CoverRule.allows(url, music)
    }

    private fun covers(lookup: Dns = here) = WebCovers(folder.newFolder(), lookup, kotlinx.coroutines.Dispatchers.IO, where = ::where)

    private val site get() = "http://127.0.0.1:${server.port}"

    /** A picture, as far as its first bytes say (a PNG's). */
    private val picture = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(56) { it.toByte() }

    private fun pictureReply() = MockResponse.Builder().setHeader("Content-Type", "image/png").body(okio.Buffer().write(picture)).build()

    private fun redirect(to: String) = MockResponse.Builder().code(302).setHeader("Location", to).build()

    private fun read(snapshot: coil3.disk.DiskCache.Snapshot?): ByteArray? = snapshot?.use { FileSystem.SYSTEM.read(it.data) { readByteArray() } }

    // ---- the rule, address by address -------------------------------------------------------------

    private fun allows(address: String, music: String? = "https://music.example") = CoverRule.allows(address.toHttpUrl(), music?.toHttpUrl())

    @Test
    fun httpsOutOnTheInternetIsAllowed() {
        assertTrue(allows("https://coverartarchive.org/release-group/abc/front-250"))
        assertTrue(allows("https://93.184.216.34/cover.jpg"))
        assertTrue(allows("https://[2606:2800:220:1::1]/cover.jpg"))
    }

    @Test
    fun plainHttpIsRefusedLidarrsOwnAddressAsMuchAsAnyOther() {
        assertFalse(allows("http://assets.fanart.tv/fanart/music/abc.jpg"))
        // Lidarr's own pictures, at the address the person typed for it: no exception (the
        // library never takes a cover from there, so one there came from somewhere else).
        assertFalse(allows("http://192.168.1.20:8686/MediaCover/1/poster.jpg"))
        assertFalse(allows("https://192.168.1.20:8686/MediaCover/1/poster.jpg"))
    }

    @Test
    fun theMusicServersOriginIsRefusedWhateverElseIsTrue() {
        assertFalse(allows("https://music.example/rest/getCoverArt?id=1"))
        assertFalse(allows("https://music.example:443/x"))
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
            assertFalse(address, allows(address, music = null))
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
        val address = "$site/release-group/abc/front-250"
        assertArrayEquals(picture, read(covers.fetch(address, "https://music.example")))
        assertArrayEquals(picture, read(covers.fetch(address, "https://music.example")))
        assertEquals("kept, not fetched again", 1, server.requestCount)
        val asked = server.takeRequest()
        assertNull(asked.headers["Authorization"])
        assertNull(asked.headers["Cookie"])
        assertTrue(asked.headers["User-Agent"].orEmpty().startsWith("NexusMusic/"))
    }

    @Test
    fun offlineOrWithRequestsOffNothingIsFetchedButWhatIsKeptIsShown() = runBlocking {
        server.enqueue(pictureReply())
        val covers = covers()
        val address = "$site/front-250"
        assertNull(covers.fetch(address, null, fetching = false))
        assertEquals("nothing asked while it may not fetch", 0, server.requestCount)
        assertNotNull(read(covers.fetch(address, null, fetching = true)))
        assertArrayEquals("what is kept is shown", picture, read(covers.fetch(address, null, fetching = false)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun whatIsNotAPictureIsNeverKept() = runBlocking {
        val page = MockResponse.Builder().setHeader("Content-Type", "image/png").body("<html><body>Not a picture</body></html>").build()
        server.enqueue(page)
        server.enqueue(page)
        val covers = covers()
        assertNull(covers.fetch("$site/x.png", null))
        assertNull(covers.fetch("$site/x.png", null))
        assertEquals("nothing kept, so asked again", 2, server.requestCount)
    }

    @Test
    fun anythingFromTheMusicServersOriginIsNeverAskedFor() = runBlocking {
        server.enqueue(pictureReply())
        assertNull(covers().fetch("$site/rest/getCoverArt?id=1", site))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun plainHttpElsewhereIsNeverLookedUpOrAskedFor() = runBlocking {
        assertNull(covers().fetch("http://covers.example:${other.port}/x.jpg", null))
        assertTrue(looked.isEmpty())
        assertEquals(0, other.requestCount)
    }

    @Test
    fun anHttpsNameThatLooksUpToThisMachineIsRefusedBeforeAnythingIsSent() = runBlocking {
        assertNull(covers().fetch("https://covers.example:${other.port}/x.jpg", null))
        assertEquals(listOf("covers.example"), looked)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun aRedirectIsJudgedAsAnyAddressIs() = runBlocking {
        // To another port on the same machine: refused, and never asked.
        server.enqueue(redirect("http://127.0.0.1:${other.port}/x.jpg"))
        other.enqueue(pictureReply())
        assertNull(covers().fetch("$site/a", null))
        assertEquals(0, other.requestCount)
        // Nor to the music server.
        server.enqueue(redirect("https://music.example/rest/getCoverArt?id=1"))
        assertNull(covers().fetch("$site/b", "https://music.example"))
        assertTrue(looked.isEmpty())
    }

    @Test
    fun fiveRedirectsAreFollowedAndNoMore() = runBlocking {
        repeat(5) { server.enqueue(redirect("/hop$it")) }
        server.enqueue(pictureReply())
        assertNotNull(read(covers().fetch("$site/start", null)))
        assertEquals(6, server.requestCount)
        repeat(6) { server.enqueue(redirect("/again$it")) }
        server.enqueue(pictureReply())
        assertNull(covers().fetch("$site/start-again", null))
        assertEquals("the sixth redirect is not followed", 12, server.requestCount)
    }

    @Test
    fun aCoverLargerThanTheLimitIsGivenUpAndNothingOfItIsKept() = runBlocking {
        // (A picture by its first bytes, so only its size can turn it away.)
        val big = okio.Buffer().write(picture).write(ByteArray((WebCovers.LIMIT + 1).toInt()))
        server.enqueue(MockResponse.Builder().body(big.copy()).build())
        server.enqueue(MockResponse.Builder().chunkedBody(big, 64 * 1024).build())
        val covers = covers()
        assertNull(covers.fetch("$site/big.png", null))
        assertNull(covers.fetch("$site/big.png", null))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun withoutRequestsInTheBuildNothingOfWebCoversIsRegistered() {
        RequestsBuild.forTests = false
        val off = coil3.ComponentRegistry.Builder()
        assertFalse(off.addWebCovers(folder.newFolder(), { null }, { false }))
        assertEquals(0, off.build().fetcherFactories.size)
        RequestsBuild.forTests = true
        val on = coil3.ComponentRegistry.Builder()
        assertTrue(on.addWebCovers(folder.newFolder(), { null }, { false }))
        assertEquals(1, on.build().fetcherFactories.size)
    }
}
