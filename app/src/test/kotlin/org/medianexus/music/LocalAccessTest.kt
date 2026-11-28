package org.medianexus.music

import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.accountDirs
import org.robolectric.RobolectricTestRunner

/** Where Android's local-network question stands, and what waits on it. */
@RunWith(RobolectricTestRunner::class)
class LocalAccessTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private fun made(): Pair<LocalAccess, Session> {
        val dir = folder.newFolder()
        val settings = SettingsStore.open(File(dir, "settings.json"), scope, Dispatchers.IO)
        val session = Session(
            settings, org.medianexus.music.nexus.NexusKept(File(dir, "nexus-signin.sealed"), File(dir, "nexus-device.sealed"), PlainBox),
            org.medianexus.music.nexus.Folders(File(dir, "carried.json")), OkHttpClient(), OfflineSwitch(),
            { key -> accountDirs(File(dir, "files"), File(dir, "cache"), key) }, Notices(), scope, Dispatchers.IO,
        )
        return LocalAccess(RuntimeEnvironment.getApplication(), session, settings, scope) to session
    }

    @Test
    fun onlyOneQuestionIsOpenAtATimeAndItsAnswerIsHeardOnce() {
        val (access, _) = made()
        val heard = mutableListOf<Boolean>()
        assertTrue(access.asking { heard += it })
        // A second while the first is up: nothing asked, nothing kept.
        assertFalse(access.asking { heard += !it })
        assertEquals(LocalAccess.Ask.ASKING, access.ask.value)
        access.answered(yes = true, canAskAgain = true)
        assertEquals(listOf(true), heard)
        access.answered(yes = true, canAskAgain = true)
        assertEquals(listOf(true), heard)
    }

    @Test
    fun aQuestionAbandonedLetsGoOfWhatWaitedOnIt() {
        val (access, _) = made()
        var carriedOut = false
        assertTrue(access.asking { carriedOut = true })
        access.abandoned()
        assertEquals(LocalAccess.Ask.NOT_ASKED, access.ask.value)
        // An answer that turns up after all finds nothing to carry out.
        access.answered(yes = true, canAskAgain = true)
        assertFalse(carriedOut)
        // And the next question can be put.
        assertTrue(access.asking(null))
    }
}
