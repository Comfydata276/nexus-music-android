package org.medianexus.music.requests

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.AccountGraph
import org.medianexus.music.AccountWiring
import org.medianexus.music.Reach
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SecretBox

/**
 * Requests are switched off in this build ([RequestsBuild]): with them off, nothing of them
 * is made, read, kept or asked. Forced on for a test, an account has its connection, which
 * is itself off until the person switches it on (`RequestsTest` has what happens then).
 */
class SwitchedOffTest {
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val made = AtomicInteger()

    /** A box that seals nothing: enough to see whether anything is ever put in it. */
    private object Plain : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = plain
        override fun open(sealed: ByteArray): ByteArray = sealed
    }

    @After
    fun stop() {
        RequestsBuild.forTests = null
        server.close()
    }

    private fun account(secrets: File): AccountGraph {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
        }
        server.start()
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient(), OfflineSwitch())
        val dirs = AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))
        val wiring = AccountWiring(
            settings = MutableStateFlow(org.medianexus.music.store.Settings()), reach = MutableStateFlow(Reach()), suspect = {},
            requests = { account ->
                made.incrementAndGet()
                Requests(account.account, account.scope, RequestsSecrets(secrets, Plain, account.account.server, account.account.username), afterwards = {})
            },
        )
        return AccountGraph(Account(api.server, api.username, "key", dirs), api, Dispatchers.IO, wiring)
    }

    @Test
    fun `as the build has it, an account has nothing of requests at all`() = runBlocking {
        assertFalse("the build's own switch is on: this test is of it being off", RequestsBuild.EXISTS)
        val secrets = File(folder.root, "sealed")
        val account = account(secrets)
        try {
            delay(300)
            assertNull(account.requests)
            assertEquals("the connection was made though the build has no requests", 0, made.get())
            assertFalse(File(account.account.dirs.data, "requests.json").exists())
            assertFalse(secrets.exists())
        } finally {
            account.close()
        }
    }

    @Test
    fun `forced on for a test, the connection is there and is off until the person switches it on`() = runBlocking {
        RequestsBuild.forTests = true
        val secrets = File(folder.root, "sealed")
        val account = account(secrets)
        try {
            val requests = assertNotNull(account.requests).let { account.requests!! }
            delay(300)
            assertEquals(1, made.get())
            assertEquals(RequestsState.Off, requests.state.value)
            // Nothing is kept until something is set up.
            assertFalse(File(account.account.dirs.data, "requests.json").exists())
            assertFalse(File(secrets, "lidarr-key.sealed").exists())
        } finally {
            account.close()
        }
    }
}
