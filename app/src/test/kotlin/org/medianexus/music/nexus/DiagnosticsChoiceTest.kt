package org.medianexus.music.nexus

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.store.folderSync

class DiagnosticsChoiceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun offIsPendingUntilDurableAndRetriedAfterAFailedWrite() {
        val root = files.root
        val file = File(root, "choice").apply { writeText("1") }
        val diagnostics = CleanDiagnostics(root, "test", System::currentTimeMillis, { JournalLoss() }) { }
        assertEquals(true, diagnostics.choice())
        val syncing = CountDownLatch(1)
        val fail = CountDownLatch(1)
        val retry = CountDownLatch(1)
        val realSync = folderSync
        folderSync = { dir ->
            when {
                dir != root -> realSync(dir)
                syncing.count != 0L -> {
                    syncing.countDown()
                    check(fail.await(10, TimeUnit.SECONDS))
                    false
                }
                else -> retry.await(15, TimeUnit.SECONDS) && realSync(dir)
            }
        }
        try {
            diagnostics.choose(false)
            assertTrue(syncing.await(10, TimeUnit.SECONDS))
            assertTrue(diagnostics.explicitlyOff)
            assertFalse(diagnostics.settings.value.switchedOn)
            assertEquals(ChoiceWrite.PENDING, diagnostics.settings.value.choiceWrite)
            fail.countDown()
            waitFor(diagnostics, ChoiceWrite.FAILED)
            assertTrue(diagnostics.explicitlyOff)
            retry.countDown()
            waitFor(diagnostics, ChoiceWrite.SAVED)
            assertEquals("0", file.readText())
            assertEquals(false, CleanDiagnostics(root, "test", System::currentTimeMillis, { JournalLoss() }) { }.choice())
        } finally {
            fail.countDown()
            retry.countDown()
            folderSync = realSync
        }
    }

    private fun waitFor(diagnostics: CleanDiagnostics, write: ChoiceWrite) = runBlocking {
        withTimeout(15_000) { diagnostics.settings.first { !it.switchedOn && it.choiceWrite == write } }
    }
}
