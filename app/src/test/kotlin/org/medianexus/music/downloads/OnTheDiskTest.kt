package org.medianexus.music.downloads

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song
import org.medianexus.music.store.folderSync
import org.medianexus.music.store.forceFolder

/**
 * What must be on the disk before it is trusted (offline hardening 2, item 3): a folder that
 * could not be synced means the names in it (a file just renamed into place, a log just
 * made) may not outlast a power cut, and whatever depends on them must then not be done.
 * Every caller used to ignore whether the sync had worked.
 */
class OnTheDiskTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = GatedServer()

    @After
    fun stop() {
        folderSync = ::forceFolder
        server.close()
    }

    private fun entry(dir: File, id: String): Downloaded {
        val file = File(dir, "$id.flac").also { it.writeBytes(ByteArray(64) { 1 }) }
        return Downloaded(Song(id = id, title = id, suffix = "flac", size = 64), file.name, 64, 1, "flac", 0, file.lastModified())
    }

    @Test
    fun theLogIsNotEmptiedWhenTheListThatReplacesItMayNotBeOnTheDisk() {
        val dir = folder.newFolder()
        val notes = Collections.synchronizedList(mutableListOf<String>())
        val catalogue = Catalogue(dir, note = { notes += it }, compactAfter = 5)
        for (i in 1..4) catalogue.add(entry(dir, "s$i"))
        // The folder can't be synced. The fifth mark makes the list due to be written whole.
        folderSync = { false }
        catalogue.add(entry(dir, "s5"))
        // The mark itself is in the log, as ever. The list was written, but its name may not
        // be on the disk: the log that it would replace is still whole.
        assertEquals(5, File(dir, Catalogue.LOG_FILE).readLines().size)
        assertTrue("$notes", notes.any { "couldn't be written whole" in it })
        // What a power cut then would leave: the list as it was before (there was none),
        // and the log. Nothing is lost.
        File(dir, Catalogue.SONGS_FILE).delete()
        File(dir, Catalogue.BACKUP_FILE).delete()
        assertEquals(setOf("s1", "s2", "s3", "s4", "s5"), Catalogue(dir).state.value.ids)
        // Once the folder can be synced again, the list is written whole and the log emptied.
        folderSync = ::forceFolder
        for (i in 6..12) catalogue.add(entry(dir, "s$i"))
        assertTrue(File(dir, Catalogue.LOG_FILE).readLines().size < 5)
        assertTrue("$notes", notes.any { "is being written whole again" in it })
        assertEquals(12, Catalogue(dir).state.value.songs.size)
    }

    @Test
    fun aMarkIsNotWrittenInALogWhoseNameMayNotBeOnTheDisk() {
        val dir = folder.newFolder()
        val catalogue = Catalogue(dir)
        folderSync = { false }
        try {
            catalogue.add(entry(dir, "s1"))
            fail("the log's name could not be put on the disk")
        } catch (_: IOException) {
        }
        assertFalse(catalogue.has("s1"))
        assertFalse(Catalogue(dir).has("s1"))
        // And it is marked once the folder can be synced.
        folderSync = ::forceFolder
        catalogue.add(entry(dir, "s1"))
        assertTrue(Catalogue(dir).has("s1"))
    }

    private val bytes = ByteArray(3_600_000) { (it * 7).toByte() }
    private val song = Song(id = "s1", title = "Song", suffix = "flac", size = bytes.size.toLong(), duration = 30)
    private val notAudio = AudioCheck { _, _, _ -> Inspected("flac", true, -1) }

    @Test
    fun aDownloadIsNotMarkedWhenItsNameMayNotBeOnTheDisk() {
        val catalogue = Catalogue(folder.newFolder())
        server.handler = { GatedServer.file(it, bytes, "Mon, 23 Nov 2026 07:57:08 GMT") }
        folderSync = { false }
        try {
            SongFetch(OkHttpClient(), catalogue, notAudio).fetch(song, SavedForm(0, "flac"), server.url("/rest/stream?id=s1"))
            fail("its name could not be put on the disk")
        } catch (_: DownloadFailure) {
        }
        assertFalse(catalogue.has("s1"))
        assertFalse(Catalogue(catalogue.dir).has("s1"))
        assertFalse(File(catalogue.dir, "s1.flac").exists())
        // With the folder syncing again, it downloads.
        folderSync = ::forceFolder
        SongFetch(OkHttpClient(), catalogue, notAudio).fetch(song, SavedForm(0, "flac"), server.url("/rest/stream?id=s1"))
        assertTrue(Catalogue(catalogue.dir).has("s1"))
    }

    @Test
    fun everyCheckpointOfAPartIsPutOnTheDiskAsARenameIs() {
        val catalogue = Catalogue(folder.newFolder())
        val gate = GatedServer.Gate()
        server.handler = { GatedServer.file(it, bytes, "Mon, 23 Nov 2026 07:57:08 GMT", hold = 2_700_000 to gate) }
        // Each sync of the folder, and what the part's record said at that moment.
        val meta = File(catalogue.dir, SongFetch.metaName("s1.flac"))
        val syncs = AtomicInteger()
        val saidAtSync = Collections.synchronizedList(mutableListOf<Long>())
        folderSync = { dir ->
            if (dir == catalogue.dir) {
                syncs.incrementAndGet()
                saidAtSync += Regex("\"synced\":(\\d+)").find(runCatching { meta.readText() }.getOrDefault(""))?.groupValues?.get(1)?.toLong() ?: -1
            }
            forceFolder(dir)
        }
        val fetch = SongFetch(OkHttpClient(), catalogue, notAudio)
        val worker = Thread {
            try {
                fetch.fetch(song, SavedForm(0, "flac"), server.url("/rest/stream?id=s1"))
            } catch (_: IOException) {
            }
        }
        worker.start()
        gate.awaitReached()
        val part = File(catalogue.dir, SongFetch.partName("s1.flac"))
        while (part.length() < 2_700_000) Thread.yield()
        // Two megabytes and more have arrived: two checkpoints, each put on the disk with
        // the folder synced after its rename. (Only the first record's was, before: a power
        // cut could give back a record several checkpoints old, or none.)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (saidAtSync.none { it >= 2 * SongFetch.SYNC_EVERY } && System.nanoTime() < deadline) Thread.yield()
        assertTrue("the folder was synced with the record saying $saidAtSync", saidAtSync.any { it >= 2 * SongFetch.SYNC_EVERY })
        assertTrue(saidAtSync.any { it in SongFetch.SYNC_EVERY until 2 * SongFetch.SYNC_EVERY })
        fetch.cancel()
        gate.dropHere()
        worker.join()
    }

    @Test
    fun aPartWhoseRecordMayNotBeOnTheDiskIsNotCarriedOnFrom() {
        val catalogue = Catalogue(folder.newFolder())
        val gate = GatedServer.Gate()
        server.handler = { GatedServer.file(it, bytes, "Mon, 23 Nov 2026 07:57:08 GMT", hold = 1_500_000 to gate) }
        folderSync = { false }
        val fetch = SongFetch(OkHttpClient(), catalogue, notAudio)
        val worker = Thread {
            try {
                fetch.fetch(song, SavedForm(0, "flac"), server.url("/rest/stream?id=s1"))
            } catch (_: IOException) {
            }
        }
        worker.start()
        gate.awaitReached()
        val part = File(catalogue.dir, SongFetch.partName("s1.flac"))
        while (part.length() < 1_500_000) Thread.yield()
        fetch.cancel()
        gate.dropHere()
        worker.join()
        // Nothing vouches for the part: the next fetch asks for the whole song.
        folderSync = ::forceFolder
        server.handler = { GatedServer.file(it, bytes, "Mon, 23 Nov 2026 07:57:08 GMT") }
        SongFetch(OkHttpClient(), catalogue, notAudio).fetch(song, SavedForm(0, "flac"), server.url("/rest/stream?id=s1"))
        assertEquals(null, server.asked.last().from)
        assertTrue(catalogue.has("s1"))
    }
}
