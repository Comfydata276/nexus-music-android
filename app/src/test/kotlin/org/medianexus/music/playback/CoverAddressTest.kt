package org.medianexus.music.playback

import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.medianexus.music.store.CoverProvider
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A cover's address, as a car keeps it, and who it is handed to. The app's real cover
 * provider and cover stores, signed in to a stand-in server as one account and then another:
 * two servers (or two accounts) can use the same cover id for two pictures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class CoverAddressTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = StandInServer()
    private lateinit var covers: CoverProvider

    private val firstPicture = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 1, 1, 1, 1, 1, 1, 1)
    private val secondPicture = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 2, 2, 2, 2, 2, 2, 2, 2)

    @Before
    fun start() {
        server.start()
        covers = Robolectric.setupContentProvider(CoverProvider::class.java, CoverProvider.AUTHORITY)
    }

    @After
    fun stop() = server.stop()

    private fun account() = app.graph.accounts.state.value!!

    /** Puts a picture on the phone as the signed-in account's cover with this id. */
    private fun keep(id: String, picture: ByteArray) {
        val file = File(File(account().account.dirs.cache, "covers"), "$id.640")
        file.parentFile!!.mkdirs()
        file.writeBytes(picture)
    }

    private fun read(address: Uri): ByteArray = covers.openFile(address, "r").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    private fun signOut() = runBlocking { app.graph.session.signOut().join() }

    @Test
    fun anAddressIsForOneAccountAndNoGoodUnderAnother() {
        server.signIn(app, "tester")
        val first = account().account.key
        keep("al-1", firstPicture)
        // What the car is given for the first account's album, and keeps.
        val kept = CarMedia.metadata(Song(id = "s1", coverArt = "al-1"), first).artworkUri!!
        assertArrayEquals(firstPicture, read(kept))
        // Someone else signs in, whose server also has an "al-1", already on the phone.
        signOut()
        server.signIn(app, "guest")
        assertNotEquals(first, account().account.key)
        keep("al-1", secondPicture)
        // The address the car kept names only... it used to name only the cover, so it showed
        // the second account's picture where the first's album had been, straight from the
        // phone's copy with no account asked about at all.
        try {
            val shown = read(kept)
            fail("an address made for another account was handed ${if (shown.contentEquals(secondPicture)) "this account's picture" else "a picture"}")
        } catch (_: FileNotFoundException) {
            // No such cover, as far as anyone holding that address is concerned.
        }
        // The same cover id, addressed to the account signed in, is theirs.
        val theirs = CarMedia.metadata(Song(id = "s1", coverArt = "al-1"), account().account.key).artworkUri!!
        assertNotEquals(kept, theirs)
        assertArrayEquals(secondPicture, read(theirs))
    }

    @Test
    fun aCoverNotYetOnThePhoneIsNotFetchedForAnotherAccountsAddress() {
        server.signIn(app, "tester")
        val kept = CoverProvider.uriFor(account().account.key, "al-9")!!
        signOut()
        server.signIn(app, "guest")
        server.asked.clear()
        // Not on the phone, so this is the path that waits and fetches: nothing is to be
        // asked of the second account's server on the strength of the first one's address.
        try {
            read(kept)
            fail("handed a cover for another account's address")
        } catch (_: FileNotFoundException) {
        }
        assertTrue("asked the server for it: ${server.asked}", server.asked.none { it.startsWith("getCoverArt") })
    }

    @Test
    fun onlyAnAccountsOwnKeyMakesAnAddress() {
        assertNull(CoverProvider.uriFor("", "al-1"))
        assertNull(CoverProvider.uriFor("not a key", "al-1"))
        assertNull(CoverProvider.uriFor("0123456789abcdef0123456789abcdef", "../../etc"))
        val address = CoverProvider.uriFor("0123456789abcdef0123456789abcdef", "al-1")!!
        assertEquals("content://${CoverProvider.AUTHORITY}/0123456789abcdef0123456789abcdef/al-1", address.toString())
        // An address of the old shape, or with anything else in it, is not a cover.
        for (odd in listOf("content://${CoverProvider.AUTHORITY}/al-1", "content://${CoverProvider.AUTHORITY}/0123456789abcdef0123456789abcdef/al-1/more")) {
            try {
                read(Uri.parse(odd))
                fail("$odd was read")
            } catch (_: FileNotFoundException) {
            }
        }
    }

    @Test
    fun diagnosticsSaysWhatBecameOfACoverNotOnlyThatItsAskerWasLetIn() {
        server.signIn(app, "tester")
        keep("al-1", firstPicture)
        // Asked by the system's own media controls, not by this app.
        org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.SYSTEM_UID)
        org.robolectric.Shadows.shadowOf(covers).setCallingPackage("com.android.systemui")
        fun noted() = app.graph.diagnostics.entries().map { it.text }.filter { it.startsWith("covers asked for by com.android.systemui") }
        try {
            // A cover there is none of: the server has no such picture.
            try {
                read(CoverProvider.uriFor(account().account.key, "al-nothing")!!)
                fail("handed a cover that does not exist")
            } catch (_: FileNotFoundException) {
            }
            // "given" was written as soon as the asker was let in, before the cover had even
            // been looked for: a drive's worth of "given" beside a car with no pictures.
            assertEquals(listOf("covers asked for by com.android.systemui: not given (no such cover). Android trusts it: yes; known host: no"), noted())
            // One that is on the phone.
            assertArrayEquals(firstPicture, read(CoverProvider.uriFor(account().account.key, "al-1")!!))
            assertTrue(noted().any { it.startsWith("covers asked for by com.android.systemui: given.") })
        } finally {
            org.robolectric.shadows.ShadowBinder.reset()
        }
    }
}
