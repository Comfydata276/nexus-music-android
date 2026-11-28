package org.medianexus.music.nexus

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.medianexus.music.core.Core
import org.medianexus.music.net.Song

class RecordingTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val limit: Timeout = Timeout.seconds(30)

    private val backend = "01".repeat(16)
    private val otherBackend = "02".repeat(16)
    private val now = AtomicLong(1_800_000_000_000L)
    private val events = LinkedBlockingQueue<JsonObject>()

    private fun recording(port: (JsonObject) -> kotlinx.serialization.json.JsonElement = Core::nexusJournal) =
        NexusRecording(folder.root, "0.6.3", now::get, port = port)

    private fun saved(account: Int, life: Int = 1, backendId: String = backend) = buildJsonObject {
        put("backend_id", backendId)
        put("account", account)
        put("life", life)
        put("session", "fixture")
    }

    private fun publish(recording: NexusRecording, account: Int, life: Int = 1, backendId: String = backend): JournalScope {
        val saved = saved(account, life, backendId)
        recording.publish(saved, false, events::add)
        return JournalScope.saved(saved)!!
    }

    private fun waitFor(kind: String, accepts: (JsonObject) -> Boolean = { true }): JsonObject {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < end) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val body = event[kind] as? JsonObject ?: continue
            if (accepts(body)) return body
        }
        error("Timed out waiting for " + kind)
    }

    private fun apply(recording: NexusRecording, kind: String, body: JsonObject): Boolean {
        val receipt = LinkedBlockingQueue<Boolean>()
        recording.journal(recordingEvent(kind, body), true, events::add, receipt::add)
        return receipt.poll(8, TimeUnit.SECONDS) ?: error("No receipt")
    }

    private fun read(recording: NexusRecording, scope: JournalScope, maximum: Int = 500): JsonArray {
        assertTrue(apply(recording, "read_events", buildJsonObject {
            put("read", 1)
            put("account", scope.account)
            put("life", scope.life)
            put("from_sequence", 1)
            put("max_events", maximum)
            put("max_bytes", 512 * 1024)
        }))
        return waitFor("events_read").getValue("events").jsonArray
    }

    private fun change(scope: JournalScope, id: EventId) = buildJsonObject {
        put("account", scope.account)
        put("life", scope.life)
        put("ids", buildJsonArray { add(JsonPrimitive(id.wire())) })
        put("at_ms", now.get())
    }

    private fun waitForPending(scope: JournalScope, minimum: Int) {
        waitFor("events_waiting") {
            it["account"] == scope.account && it["life"] == scope.life &&
                it.getValue("pending").jsonPrimitive.content.toInt() >= minimum
        }
    }

    @Test fun privateListeningTextIsAbsentFromEveryIntakeDebugString() {
        val title = "private song title"
        val artist = "private artist"
        val album = "private album"
        val words = "private search words"
        val start = Listening.Start(Song(id = "media", title = title, artist = artist, album = album),
            PlayOrigin.from("search:$words"), PlayBegan.HERE, PlaySound.STREAM, 0)
        val search = Listening.Search(words, 2, false)
        val scope = JournalScope.saved(saved(1))!!
        val id = EventId(0, 42)
        val captured = Captured(scope, id, now.get(), start)
        val observation = SearchObservation(scope, id, words, false, now.get())
        val text = listOf(start, start.origin, search, observation, captured, ListeningJournal.Job.Event(captured))
            .joinToString("\n")
        for (secret in listOf(title, artist, album, words)) assertFalse(secret in text)
        assertTrue(id.wire() in text)
    }

    @Test fun replacementDiagnosticsSinkReceivesOffAndTheChoiceSurvivesReopening() {
        val diag = recording().diagnostics
        val previous = LinkedBlockingQueue<JsonObject>()
        val current = LinkedBlockingQueue<JsonObject>()
        diag.attach { previous.add(it) }
        diag.attach { current.add(it) }
        diag.choose(false)
        val off = generateSequence { current.poll(2, TimeUnit.SECONDS) }
            .first { "diagnostics_switch" in it }
        assertEquals(JsonPrimitive(false), off["diagnostics_switch"])
        assertFalse(previous.any { "diagnostics_switch" in it })
        val path = File(folder.root, "nexus-diagnostics/choice")
        runBlocking { withTimeout(8_000) { while (runCatching { path.readText() }.getOrNull() != "0") kotlinx.coroutines.delay(10) } }
        assertEquals(false, recording().diagnostics.choice())
    }

    @Test fun anUnwritableOffChoiceIsShownAndRetriedOnTheWorker() {
        val service = recording()
        val diag = service.diagnostics
        val blocked = File(folder.root, "nexus-diagnostics/choice")
        assertTrue(blocked.mkdirs())
        // A nonempty directory cannot be replaced by the preference file.
        File(blocked, "held").writeText("fixture")
        diag.choose(false)
        waitForSetting(diag) { it.choiceWrite == ChoiceWrite.FAILED }
        assertFalse(diag.settings.value.switchedOn)
        assertTrue(blocked.deleteRecursively())
        waitForSetting(diag) { it.choiceWrite == ChoiceWrite.SAVED }
        assertEquals(false, recording().diagnostics.choice())
    }

    @Test fun anAudioFaultBurstIsKeptAsOneCountedJournalEvent() {
        val service = recording()
        val scope = publish(service, 1)
        waitForPending(scope, 1)
        repeat(12) { service.diagnostics.fault(DiagnosticFault(DiagnosticPlace.AUDIO_OUTPUT, DiagnosticShape.NO_SOUND)) }
        waitForPending(scope, 2)
        val faults = read(service, scope).map { it.jsonObject.getValue("event").jsonObject }
            .filter { it["kind"] == JsonPrimitive("fault") }
        assertEquals(JsonPrimitive(12), faults.single()["count"])
    }

    @Test fun crashClassificationUsesTheExceptionHierarchyAndClosedFrameClasses() {
        class PrivateSocketError : java.net.SocketTimeoutException("private payload")
        val crash = PrivateSocketError().apply {
            stackTrace = arrayOf(StackTraceElement(org.medianexus.music.downloads.DownloadsJob::class.java.name, "renamed", "SourceFile", 13))
        }
        assertEquals(DiagnosticFault(DiagnosticPlace.DOWNLOADS, DiagnosticShape.TIMEOUT, 13), DiagnosticFault.crash(crash))
        // A frame of another app class (named in debug, renamed out of the package in release)
        // above a placed one is passed over in both builds; with no placed frame it is the app.
        for (helper in listOf("org.medianexus.music.ui.Helper", "y5")) {
            val nested = IllegalStateException().apply {
                stackTrace = arrayOf(StackTraceElement(helper, "a", "SourceFile", 5),
                    StackTraceElement(org.medianexus.music.playback.PlaybackService::class.java.name + "\$Watcher", "b", "SourceFile", 40))
            }
            assertEquals(DiagnosticFault(DiagnosticPlace.PLAYBACK, DiagnosticShape.PANIC, 40), DiagnosticFault.crash(nested))
            val elsewhere = IllegalStateException().apply { stackTrace = arrayOf(StackTraceElement(helper, "a", "SourceFile", 5)) }
            assertEquals(DiagnosticFault(DiagnosticPlace.APP, DiagnosticShape.PANIC, null), DiagnosticFault.crash(elsewhere))
        }
    }

    @Test fun unsignedAndOutstandingNoticeRecordNothing() {
        val service = recording()
        service.publish(saved(1), true, events::add)
        assertNull(service.capture(Listening.Search("unsigned", 0, false)))
        assertFalse(File(folder.root, "nexus-listening").exists())
        val account = publish(service, 1)
        waitForPending(account, 1)
        assertEquals("open", read(service, account).single().jsonObject.getValue("event").jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun fullCaptureQueueDropsOneForEveryFailedOfferAndFlushesThatCount() {
        val hold = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val durable = CountDownLatch(1)
        val service = recording { request ->
            if ("activate" in request) {
                entered.countDown()
                check(hold.await(8, TimeUnit.SECONDS))
            }
            Core.nexusJournal(request).also { reply ->
                if (reply is JsonObject && reply["counted"] == JsonPrimitive(45)) durable.countDown()
            }
        }
        val scope = publish(service, 1)
        assertTrue(entered.await(8, TimeUnit.SECONDS))
        try {
            // One Open is already queued. Position observations fill the remaining slots
            // without requiring 255 fsyncs to prove what happens when the queue is full.
            repeat(255) { assertNotNull(service.capture(Listening.Position(EventId(0, 2), it.toLong()))) }
            val refused = (1..45).count { service.capture(Listening.Search("fixture", 0, false)) == null }
            assertEquals(45, refused)
            assertEquals(45uL, service.losses().line)
        } finally {
            hold.countDown()
        }
        assertTrue(durable.await(8, TimeUnit.SECONDS))
        waitForPending(scope, 1)
        assertEquals(45uL, service.losses().line)
        assertTrue(File(folder.root, "nexus-listening/state.json").readText().contains("\"line_full\":45"))
    }

    @Test fun failedWriterCountsTheAcceptedAndSubsequentEvents() {
        val failed = CountDownLatch(1)
        val die = CountDownLatch(1)
        val service = recording { request ->
            if ("activate" in request) {
                failed.countDown()
                check(die.await(8, TimeUnit.SECONDS))
                throw AssertionError("writer died")
            }
            Core.nexusJournal(request)
        }
        val scope = publish(service, 1)
        assertTrue(failed.await(8, TimeUnit.SECONDS))
        die.countDown()
        // A change can be completed with false only once the dead writer has abandoned it.
        assertFalse(apply(service, "life_moved", scope.wire()))
        assertNull(service.capture(Listening.Search("fixture", 0, false)))
        assertEquals(2uL, service.losses().line)
    }

    @Test fun unavailableStorageAndFailedAppendAreCounted() {
        File(folder.root, "nexus-listening").writeText("blocks a directory")
        val service = recording()
        val scope = publish(service, 1)
        assertNotNull(service.capture(Listening.Search("fixture", 2, false)))
        runBlocking {
            withTimeout(8_000) {
                while (service.losses().line != 2uL) kotlinx.coroutines.delay(10)
            }
        }
        File(folder.root, "nexus-listening").delete()
        now.addAndGet(6_000)
        waitForPending(scope, 0)
        assertEquals(2uL, service.losses().line)
    }

    @Test fun mutationsAcknowledgeAfterPersistenceAndAccountsNeverCross() {
        val entered = CountDownLatch(1)
        val persist = CountDownLatch(1)
        val service = recording { request ->
            val change = request["change"] as? JsonObject
            if (change?.get("effect")?.jsonObject?.get("taken") != null) {
                entered.countDown()
                check(persist.await(8, TimeUnit.SECONDS))
            }
            Core.nexusJournal(request)
        }
        val a = publish(service, 1)
        val start = service.capture(Listening.Search("a only", 3, false))!!
        waitForPending(a, 2)
        val receipt = LinkedBlockingQueue<Boolean>()
        service.journal(recordingEvent("taken", change(a, start)), true, events::add, receipt::add)
        assertTrue(entered.await(8, TimeUnit.SECONDS))
        try {
            assertNull(receipt.poll(150, TimeUnit.MILLISECONDS))
            assertEquals(2, read(service, a).size)
        } finally {
            persist.countDown()
        }
        assertTrue(receipt.poll(8, TimeUnit.SECONDS)!!)
        assertEquals(1, read(service, a).size)
        assertTrue(File(folder.root, "nexus-listening/state.json").readText().contains("\"outcome\":\"taken\""))
        val b = publish(service, 2)
        assertNull(service.scopeFor(a.person))
        assertNotNull(service.scopeFor(b.person))
        assertNotNull(service.capture(Listening.Search("b only", 1, false)))
        waitForPending(b, 1)
        assertTrue(read(service, a).isEmpty())
        assertTrue("b only" in read(service, b).toString())
        assertFalse("a only" in read(service, b).toString())
        assertTrue(apply(service, "resend_all", buildJsonObject { put("backend_id", backend) }))
        publish(service, 1)
        waitForPending(a, 2)
        assertTrue("a only" in read(service, a).toString())
        assertEquals(1, read(service, a, maximum = 1).size)
    }

    @Test fun refusalSurvivesReplayAndItsLossIsCountedExactlyOnce() {
        val service = recording()
        val a = publish(service, 1)
        val refused = service.capture(Listening.Search("refused line", 0, false))!!
        val taken = service.capture(Listening.Search("taken line", 0, false))!!
        val long = "🎶".repeat(600)
        assertNotNull(service.capture(Listening.Start(Song("id", long, long, long), PlayOrigin.from(""), PlayBegan.HERE, PlaySound.STREAM, 0)))
        waitForPending(a, 4)
        assertTrue(apply(service, "refused", change(a, refused)))
        assertTrue(apply(service, "refused", change(a, refused)))
        assertTrue(apply(service, "taken", change(a, taken)))
        assertEquals(1uL, service.losses().refused)
        assertTrue(apply(service, "resend_all", buildJsonObject { put("backend_id", backend) }))
        val replay = read(service, a)
        assertTrue("taken line" in replay.toString())
        assertFalse("refused line" in replay.toString())
        assertTrue(replay.any { it.jsonObject.getValue("event").jsonObject["kind"] == JsonPrimitive("start") })
        assertEquals(1uL, service.losses().refused)
    }

    @Test fun tornTailAndOpenStartRecoverOnceAndLifeMoveRetiresWithoutLoss() {
        val first = recording()
        val a = publish(first, 1)
        val start = first.capture(Listening.Start(Song(id = "audio", title = "first"), PlayOrigin.from("album:collection"),
            PlayBegan.HERE, PlaySound.DOWNLOAD, 0))!!
        first.capture(Listening.Position(start, 1234))
        assertTrue(apply(first, "resend_all", buildJsonObject { put("backend_id", backend) }))
        waitForPending(a, 2)
        val segment = File(folder.root, "nexus-listening/$backend/1/1").listFiles()!!.single { it.extension == "events" }
        segment.appendText("{\"torn\":")
        val reopened = recording()
        publish(reopened, 1)
        waitForPending(a, 3)
        val batch = read(reopened, a).toString()
        assertTrue("\"position_ms\":1234" in batch)
        assertTrue("\"kind\":\"unknown\"" in batch)
        assertEquals(1uL, reopened.losses().line)
        assertFalse("torn" in segment.readText())
        assertTrue(apply(reopened, "life_moved", a.copy(life = JsonPrimitive(2)).wire()))
        assertFalse(segment.exists())
        assertFalse(File(segment.parentFile, "position.json").exists())
        assertEquals(1uL, reopened.losses().line)
        assertEquals(0uL, reopened.losses().files)
        publish(reopened, 1, 2)
        assertTrue(read(reopened, a).isEmpty())
    }

    @Test fun everyPhoneNoteIsAcceptedByCoreAndReachesTheReport() {
        // Core's words at the pin. A wire name core does not know would refuse the whole batch.
        val words = listOf("Player started", "Foreground entered", "Audio output is moving", "Android Auto connected",
            "Android Auto connection refused", "Other controller connected", "Other controller connection refused",
            "Nexus sign-in done", "Nexus sign-in refused", "Nexus sign-in had too many tries", "Music login asked for again",
            "Nexus asked to start again", "Events sent", "Events refused", "App message shown", "Downloads folder changed",
            "Media button started the player", "Media button had nothing to resume",
            "Android refused to start the player from a media button", "Shared library could not be loaded")
        assertEquals(words.size, DiagnosticNote.entries.size)
        val diag = recording().diagnostics
        diag.state(signedIn = true, offline = false, serverDefault = true)
        DiagnosticNote.entries.forEach(diag::note)
        val page = runBlocking {
            withTimeout(8_000) { diag.page.first { it?.contains(words.last()) == true }!! }
        }
        for (word in words) assertTrue(word, word in page)
    }

    @Test fun canonicalPageAndExportAndMachineHaveIndependentlyProducedIdenticalBytes() {
        val service = recording()
        val diag = service.diagnostics
        val payload = "private@example.org https://host.invalid/secret \\local\\folder player–title\nSSID-X"
        val crash = NullPointerException(payload).apply {
            stackTrace = arrayOf(StackTraceElement(org.medianexus.music.playback.PlaybackService::class.java.name, "method-$payload", payload, 72))
        }
        diag.about(PhoneDiagnostics(androidMajor = 17, https = true, localPermission = true, auto = true))
        diag.state(signedIn = true, offline = false, serverDefault = true)
        diag.note(DiagnosticNote.AUTO_ADMITTED)
        diag.fault(DiagnosticFault.crash(crash))
        diag.foreground(850)
        diag.downloadStopped(7)
        diag.attempt(DiagnosticReach.CONNECTION, local = true, permission = false,
            network = DiagnosticNetwork.fromTransport(payload), failed = true)
        diag.sessionEnded(DiagnosticSessionEnd.DATA_PUT_BACK)
        val a = publish(service, 1)
        service.capture(Listening.Search(payload, 0, false))
        waitForPending(a, 2)
        val preview = runBlocking {
            withTimeout(8_000) { diag.page.first { it?.contains("Nexus session ended: data put back") == true }!! }
        }
        val written = LinkedBlockingQueue<ByteArray>()
        diag.export {
            object : ByteArrayOutputStream() {
                override fun close() { written.add(toByteArray()) }
            }
        }
        val export = written.poll(8, TimeUnit.SECONDS)!!
        assertTrue(apply(service, "read_diagnostics", buildJsonObject { put("read", 12) }))
        val sent = waitFor("diagnostics_read").getValue("text").jsonPrimitive.content
        assertArrayEquals(preview.encodeToByteArray(), export)
        assertArrayEquals(export, sent.encodeToByteArray())
        assertTrue("Fault in playback: missing value at other:72" in sent)
        assertTrue("Nexus last failure: connection opened, local address yes, local permission no, network unknown" in sent)
        assertTrue("Nexus session ended: data put back" in sent)
        for (word in listOf("private@example.org", "host.invalid", "secret", "folder", "player–title", "SSID-X")) {
            assertFalse(word in sent)
        }
        service.journal(recordingEvent("read_diagnostics", buildJsonObject { put("read", 13) }), false, events::add) {}
        assertEquals(JsonNull, waitFor("diagnostics_read")["text"])
    }

    @Test fun checkedHelloDistinguishesOmittedOffAndUnreadableDefaults() {
        val body = buildJsonObject {
            put("nexus", "0.41.0")
            put("id", backend)
            put("speaks", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1)) })
            put("has", buildJsonArray { add(JsonPrimitive("accounts")) })
        }
        val accepted = Core.nexusHello(200, body.toString().encodeToByteArray(), whole = true)
        assertTrue(accepted.why, accepted.backend)
        assertEquals(backend, accepted.backendId)
        assertEquals(true, accepted.diagnosticsDefault)
        val disabled = JsonObject(body + ("diagnostics" to JsonPrimitive(false)))
        assertEquals(false, Core.nexusHello(200, disabled.toString().encodeToByteArray(), whole = true).diagnosticsDefault)
        assertNull(Core.nexusHello(200, body.toString().encodeToByteArray(), whole = false).diagnosticsDefault)
    }

    @Test fun diagnosticSwitchFollowsHelloUntilTheExplicitChoiceAndSaysWhyItIsPaused() {
        val service = recording()
        val diag = service.diagnostics
        assertTrue(diag.settings.value.switchedOn)
        assertTrue(diag.settings.value.status!!.contains("Sign in"))
        publish(service, 1)
        diag.state(signedIn = true, offline = false, serverDefault = false)
        waitForSetting(diag) { it.signedIn && it.status == "Sending is off." }
        diag.choose(true)
        assertEquals(JsonPrimitive(true), generateSequence { events.poll(1, TimeUnit.SECONDS) }
            .firstOrNull { "diagnostics_switch" in it }
            ?.get("diagnostics_switch"))
        assertTrue(diag.settings.value.switchedOn)
        waitForSetting(diag) { it.signedIn && it.switchedOn && it.status == null }
        diag.state(signedIn = true, offline = true, serverDefault = false)
        waitForSetting(diag) { it.offline && it.switchedOn && it.status!!.contains("Offline") }
        diag.choose(false)
        waitForSetting(diag) { !it.switchedOn }
        assertFalse(diag.choice()!!)
    }

    private fun waitForSetting(diag: CleanDiagnostics, check: (DiagnosticsState) -> Boolean) = runBlocking {
        withTimeout(8_000) { diag.settings.first(check) }
    }
}
