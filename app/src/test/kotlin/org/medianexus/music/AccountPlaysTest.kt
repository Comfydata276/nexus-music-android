package org.medianexus.music

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs

/** A play that counted goes to the log of plays and to the server's held changes together:
 *  signing out straight after can't leave it in one and not the other (M5b fixes, item 5). */
class AccountPlaysTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aPlayGoesToTheLogAndTheServersQueueTogether() = runBlocking {
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        // No server to be had: what is reported waits on the disk.
        val api = Subsonic("http://127.0.0.1:9", "ada", "sesame", OkHttpClient(), OfflineSwitch(true))
        val gate = Gate()
        val account = AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, work = gate)
        account.playLog.flushed()
        // Counted, and the account goes before anything of its own work runs again (the report
        // used to be started in the account's scope to run later, and the sign-out cancelled it
        // before it began: the play was in the log and the server was never told).
        gate.held = true
        account.played("s1", 1_000_000_000L, "playlist:p", report = true)
        account.close()
        gate.release()
        account.playLog.close().join()
        // Its line in the log, and its change on the disk for the server.
        assertEquals("[\"s1\",1000000,\"playlist:p\"]\n", File(dirs.data, "plays.log").readText())
        val held = File(dirs.data, "held.json")
        val until = System.currentTimeMillis() + 5_000
        while (!(held.isFile && "s1" in held.readText()) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("the play isn't held for the server", held.isFile && "\"s1\"" in held.readText())
        // Once the account has gone, neither.
        account.played("s2", 1_000_001_000L, "", report = true)
        Thread.sleep(200)
        assertFalse("s2" in File(dirs.data, "plays.log").readText())
        assertFalse("s2" in held.readText())
    }

    /** The account's dispatcher, which holds what is sent to it while [held]. */
    private class Gate : kotlinx.coroutines.CoroutineDispatcher() {
        @Volatile var held = false
        private val waiting = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (held) waiting += block else Dispatchers.IO.dispatch(context, block)
        }

        fun release() {
            held = false
            while (true) Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, waiting.poll() ?: break)
        }
    }
}
