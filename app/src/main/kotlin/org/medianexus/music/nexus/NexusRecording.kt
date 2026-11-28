package org.medianexus.music.nexus

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.BuildConfig
import org.medianexus.music.core.Core

internal typealias RecordingEvent = (JsonObject) -> Unit

internal fun recordingEvent(name: String, value: JsonElement) = buildJsonObject { put(name, value) }

internal class NexusRecording(
    root: File,
    private val version: String = BuildConfig.VERSION_NAME,
    private val wall: () -> Long = System::currentTimeMillis,
    private val folderFor: (String) -> String = { it },
    port: (JsonObject) -> JsonElement = Core::nexusJournal,
) {
    private val journal = ListeningJournal(File(root, "nexus-listening"), wall, port)
    val diagnostics = CleanDiagnostics(File(root, "nexus-diagnostics"), version, wall, journal::losses, ::fault)
    private var opened = false
    @Volatile private var folder: Pair<JournalScope, String>? = null

    fun publish(saved: JsonObject?, noticeOutstanding: Boolean, emit: RecordingEvent) {
        diagnostics.attach(emit)
        val next = saved?.takeUnless { noticeOutstanding || it["session"] == null || it["session"] == JsonNull }
            ?.let(JournalScope::saved)
        if (next?.backend != folder?.first?.backend || next?.number != folder?.first?.number) folder = next?.let { it to folderFor(it.person) }
        journal.publish(next, emit)
        if (next != null && !opened) {
            opened = true
            capture(Listening.Open(version))
        }
    }

    fun journal(thing: JsonObject, scheduleKept: Boolean, emit: RecordingEvent, complete: (Boolean) -> Unit) {
        val (kind, value) = thing.entries.singleOrNull() ?: return complete(false)
        val body = value as? JsonObject ?: return complete(false)
        when (kind) {
            "read_events" -> {
                journal.read(body, emit)
                complete(true)
            }
            "read_diagnostics" -> {
                val read = body["read"] ?: return complete(false)
                diagnostics.readForMachine(scheduleKept) { text ->
                    emit(recordingEvent("diagnostics_read", buildJsonObject {
                        put("read", read)
                        put("text", text?.let(::JsonPrimitive) ?: JsonNull)
                    }))
                }
                complete(true)
            }
            "taken", "refused", "life_moved", "resend_all" -> journal.change(thing, complete)
            else -> complete(false)
        }
    }

    fun scope(): JournalScope? = journal.current
    internal fun losses(): JournalLoss = journal.losses()

    fun scopeFor(accountFolder: String): JournalScope? {
        val active = scope() ?: return null
        val expected = folder ?: return null
        return active.takeIf { it.backend == expected.first.backend && it.number == expected.first.number && expected.second == accountFolder }
    }

    /** The first sign-in can carry over the old folder just after the driver publishes. */
    fun refreshFolder() {
        val active = scope() ?: return
        val resolved = folderFor(active.person)
        if (scope() == active) folder = active to resolved
    }

    fun capture(data: Listening, scope: JournalScope? = scope(), at: Long = wall()): EventId? {
        if (scope == null) return null
        val id = EventId.make()
        return id.takeIf { journal.offer(Captured(scope, id, at, data)) }
    }

    fun search(words: String, accountFolder: String, requests: Boolean = false): SearchObservation? =
        scopeFor(accountFolder)?.let { SearchObservation(it, words, requests, wall()) }

    fun found(search: SearchObservation?, count: Int) {
        if (search != null) capture(Listening.Search(search.words, count, search.requests), search.scope, search.at)
    }

    private fun fault(fault: DiagnosticFault) {
        capture(Listening.Fault(fault))
    }
}

internal data class SearchObservation(val scope: JournalScope, val words: String, val requests: Boolean, val at: Long)

internal data class JournalScope(val backend: JsonPrimitive, val number: JsonPrimitive, val life: JsonPrimitive) {
    val person get() = Folders.personOf(KeptAccount("", backend.content, number.content))

    val account: JsonObject get() = buildJsonObject {
        put("backend_id", backend)
        put("account", number)
    }

    fun wire(): JsonObject = buildJsonObject {
        put("account", account)
        put("life", life)
    }

    companion object {
        fun saved(body: JsonObject): JournalScope? = of(body, body["life"])
        fun read(body: JsonObject): JournalScope? {
            return of(body["account"] as? JsonObject ?: return null, body["life"])
        }

        private fun of(account: JsonObject, life: JsonElement?): JournalScope? {
            return JournalScope(
                account["backend_id"] as? JsonPrimitive ?: return null,
                account["account"] as? JsonPrimitive ?: return null,
                life as? JsonPrimitive ?: return null,
            )
        }
    }
}

/** Two random words captured at the observation, encoded by the writer. */
internal data class EventId(private val high: Long, private val low: Long) {
    fun wire() = high.toULong().toString(16).padStart(16, '0') + low.toULong().toString(16).padStart(16, '0')

    companion object {
        fun make(): EventId = ThreadLocalRandom.current().let { EventId(it.nextLong(), it.nextLong()) }
    }
}

private data class Captured(val scope: JournalScope, val id: EventId, val at: Long, val data: Listening) {
    fun wire(): JsonObject = buildJsonObject {
        put("scope", scope.wire())
        put("id", id.wire())
        put("at", at)
        put("data", data.wire())
    }
}

internal data class JournalLoss(val line: ULong = 0u, val files: ULong = 0u, val refused: ULong = 0u) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun wire() = buildJsonObject {
        put("line_full", JsonUnquotedLiteral(line.toString()))
        put("files_full", JsonUnquotedLiteral(files.toString()))
        put("refused", JsonUnquotedLiteral(refused.toString()))
    }

    companion object {
        fun read(value: JsonObject) = JournalLoss(value.total("line_full"), value.total("files_full"), value.total("refused"))
        private fun JsonObject.total(key: String) = (get(key) as? JsonPrimitive)?.content?.toULongOrNull() ?: 0u
    }
}

/**
 * The producer has a fixed budget and shares no monitor with the disk or the client.
 * Control messages are independent of that budget. A single writer is the only caller
 * allowed to change the journal. The reader uses a separate thread and committed sequence.
 */
private class ListeningJournal(
    private val root: File,
    private val wall: () -> Long,
    private val port: (JsonObject) -> JsonElement,
) {
    private sealed interface Job {
        data class Event(val captured: Captured) : Job
        data class Publish(val scope: JournalScope?, val emit: RecordingEvent) : Job
        data class Change(val effect: JsonObject, val complete: (Boolean) -> Unit) : Job
    }

    @Volatile var current: JournalScope? = null
        private set

    private val queued = AtomicInteger()
    private val lost = AtomicLong()
    private val jobs = ConcurrentLinkedQueue<Job>()
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "nexus-journal-reader").apply { isDaemon = true } }
    private val writer = Thread(::run, "nexus-journal-writer").apply { isDaemon = true }
    private val started = AtomicBoolean()
    @Volatile private var alive = true
    @Volatile private var visible = Visibility()

    private data class Visibility(val through: JsonElement = JsonPrimitive(0), val loss: JournalLoss = JournalLoss())

    // Writer's own fields. None is read by the player or the separate reader.
    private var active: JournalScope? = null
    private var emit: RecordingEvent? = null
    private var waiting: JsonObject? = null
    private var lastSent: JsonObject? = null
    private var lastSentAt = 0L
    private var retryWrites = false
    private var retryActivation = false

    /** Called by the driver, before publishing eligibility to any producer. */
    private fun startWriter() {
        if (started.compareAndSet(false, true)) {
            try {
                writer.start()
            } catch (_: RuntimeException) {
                alive = false
            }
        }
    }

    fun offer(event: Captured): Boolean {
        if (!alive) {
            countLoss(event)
            return false
        }
        if (queued.getAndIncrement() >= CAPACITY) {
            queued.decrementAndGet()
            countLoss(event)
            LockSupport.unpark(writer)
            return false
        }
        val job = Job.Event(event)
        jobs.offer(job)
        LockSupport.unpark(writer)
        if (!alive && jobs.remove(job)) {
            queued.decrementAndGet()
            countLoss(event)
            return false
        }
        return true
    }

    private fun countLoss(event: Captured) {
        if (event.data !is Listening.Position) lost.incrementAndGet()
    }

    fun publish(next: JournalScope?, emit: RecordingEvent) {
        if (current == next) return
        startWriter()
        // Publish job precedes all captures that can observe the new scope.
        jobs.offer(Job.Publish(next, emit))
        current = next
        LockSupport.unpark(writer)
    }

    fun change(effect: JsonObject, complete: (Boolean) -> Unit) {
        startWriter()
        val job = Job.Change(effect, complete)
        jobs.offer(job)
        LockSupport.unpark(writer)
        if (!alive && jobs.remove(job)) complete(false)
    }

    fun read(request: JsonObject, emit: RecordingEvent) {
        reader.execute {
            val target = JournalScope.read(request)
            val through = visible.through
            val found = if (target == null || current != target) null else
                safe { call("read", JsonObject(request + ("through" to through))) as? JsonArray }
            emit(recordingEvent("events_read", buildJsonObject {
                put("read", request.getValue("read"))
                put("account", request.getValue("account"))
                put("life", request.getValue("life"))
                put("events", if (current == target) found ?: EMPTY else EMPTY)
            }))
        }
    }

    fun losses(): JournalLoss {
        val loss = visible.loss
        val extra = lost.get().coerceAtLeast(0).toULong()
        return loss.copy(line = loss.line + extra.coerceAtMost(ULong.MAX_VALUE - loss.line))
    }

    private fun abandon() {
        while (true) when (val job = jobs.poll() ?: return) {
            is Job.Change -> job.complete(false)
            is Job.Event -> {
                queued.decrementAndGet()
                if (job.captured.data !is Listening.Position) lost.incrementAndGet()
            }
            is Job.Publish -> Unit
        }
    }

    private fun run() {
        try {
            loop()
        } finally {
            alive = false
            abandon()
        }
    }

    private fun loop() {
        var retryAt = 0L
        while (true) {
            val job = jobs.poll()
            val now = wall()
            when (job) {
                is Job.Publish -> {
                    active = job.scope
                    emit = job.emit
                    waiting = null
                    lastSent = null
                    lastSentAt = 0
                    activate(now)
                    retryAt = now + LOSS_RETRY_MILLIS
                }
                is Job.Event -> {
                    // Collect already queued records, preserving every control boundary.
                    val records = ArrayList<Captured>()
                    records += job.captured
                    while (jobs.peek() is Job.Event && records.size < CAPACITY) {
                        records += (jobs.poll() as Job.Event).captured
                    }
                    queued.addAndGet(-records.size)
                    try {
                        flush(records, now)
                    } catch (error: Error) {
                        records.forEach(::countLoss)
                        throw error
                    }
                }
                is Job.Change -> {
                    val done = safe {
                        val answer = call("change", buildJsonObject {
                            put("effect", job.effect)
                            put("active", active?.wire() ?: JsonNull)
                            put("now", now)
                        })
                        accept(answer)
                        true
                    } ?: false
                    job.complete(done)
                    if (done) lastSent = null
                }
                null -> if ((retryWrites || retryActivation || lost.get() != 0L) && now >= retryAt) {
                    if (retryActivation) activate(now)
                    flush(emptyList(), now)
                    retryAt = now + LOSS_RETRY_MILLIS
                }
            }
            publishWaiting()
            if (job == null && jobs.isEmpty()) {
                val hasWaiting = waiting != null && waiting != lastSent && active == current
                val notifyIn = if (hasWaiting) (REPORT_NANOS - (System.nanoTime() - lastSentAt)).coerceAtLeast(1) else Long.MAX_VALUE
                val retryIn = if (retryWrites || retryActivation || lost.get() != 0L) (retryAt - wall()).coerceAtLeast(1) * 1_000_000 else Long.MAX_VALUE
                val wait = minOf(notifyIn, retryIn)
                if (wait == Long.MAX_VALUE) LockSupport.park() else LockSupport.parkNanos(wait)
            }
        }
    }

    private fun activate(now: Long) {
        val scope = active
        retryActivation = scope != null && safe {
            accept(call("activate", buildJsonObject { put("scope", scope.wire()); put("now", now) }))
        } == null
    }

    private fun flush(records: List<Captured>, now: Long) {
        val drops = lost.get()
        val answer = safe {
            call("append", buildJsonObject {
                put("active", active?.wire() ?: JsonNull)
                put("records", JsonArray(records.map(Captured::wire)))
                put("lost", drops)
                put("now", now)
            })
        }
        if (answer == null) {
            retryWrites = true
            lost.addAndGet(records.count { it.data !is Listening.Position }.toLong())
        } else {
            val objectAnswer = answer as JsonObject
            val counted = (objectAnswer["counted"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
            if (counted > 0) lost.addAndGet(-counted)
            accept(answer)
        }
    }

    private fun accept(value: JsonElement) {
        val answer = value as JsonObject
        visible = Visibility(answer.getValue("through"), JournalLoss.read(answer.getValue("loss") as JsonObject))
        waiting = answer["waiting"] as? JsonObject
        retryWrites = (answer["retry"] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun publishWaiting() {
        val summary = waiting ?: return
        if (active != current || summary == lastSent) return
        val now = System.nanoTime()
        if (lastSentAt != 0L && now - lastSentAt < REPORT_NANOS) return
        lastSentAt = now
        lastSent = summary
        emit?.invoke(recordingEvent("events_waiting", summary))
    }

    private fun call(name: String, body: JsonObject): JsonElement = port(buildJsonObject {
        put("root", root.path)
        put(name, body)
    })

    companion object {
        private const val CAPACITY = 256
        private const val REPORT_NANOS = 1_000_000_000L
        private const val LOSS_RETRY_MILLIS = 5_000L
        private val EMPTY = JsonArray(emptyList())
    }
}

internal inline fun <T> safe(work: () -> T): T? = try {
    work()
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}
