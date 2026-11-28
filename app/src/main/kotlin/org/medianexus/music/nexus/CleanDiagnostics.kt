package org.medianexus.music.nexus

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.medianexus.music.downloads.DownloadsJob
import org.medianexus.music.playback.PlaybackService
import org.medianexus.music.playback.QueuePlayer
import org.medianexus.music.core.Core
import org.medianexus.music.store.writeAtomically

internal enum class DiagnosticPlace(val wire: String) {
    PLAYBACK("playback"), AUDIO_OUTPUT("audio_output"), LIBRARY("library"), DOWNLOADS("downloads"),
    FOREGROUND("foreground"), MUSIC_SERVER("music_server"), NEXUS_SERVER("nexus_server"), APP("app"),
}

internal enum class DiagnosticShape(val wire: String) {
    NAME_LOOKUP("name_lookup"), CONNECTION("connection"), SECURE("secure_connection"),
    TIMEOUT("timeout"), PERMISSION("permission"), REFUSED("refused"), UNAVAILABLE("unavailable"),
    ANSWER("unexpected_answer"), READ("read"), WRITE("write"), DECODE("decode"), NO_SOUND("no_sound"),
    BOUNDS("bounds"), ASSERTION("assertion"), MISSING_VALUE("missing_value"), OTHER("other"), PANIC("panic"),
}

internal data class DiagnosticFault(val place: DiagnosticPlace, val shape: DiagnosticShape, val sourceLine: Int? = null) {
    fun eventWire() = buildJsonObject {
        put("place", place.wire)
        put("shape", shape.wire)
    }

    fun reportWire() = recordingEvent("fault", buildJsonObject {
        put("place", place.wire)
        put("shape", shape.wire)
        put("source_line", sourceLine?.let(::JsonPrimitive) ?: JsonNull)
    })

    companion object {
        fun crash(error: Throwable): DiagnosticFault {
            val shape = when (error) {
                is SecurityException -> DiagnosticShape.PERMISSION
                is UnknownHostException -> DiagnosticShape.NAME_LOOKUP
                is SSLException -> DiagnosticShape.SECURE
                is SocketTimeoutException -> DiagnosticShape.TIMEOUT
                is ConnectException -> DiagnosticShape.CONNECTION
                is IOException -> DiagnosticShape.READ
                is IndexOutOfBoundsException -> DiagnosticShape.BOUNDS
                is NullPointerException, is NoSuchElementException -> DiagnosticShape.MISSING_VALUE
                is AssertionError -> DiagnosticShape.ASSERTION
                else -> DiagnosticShape.PANIC
            }
            // Compare against the closed app classes, with their nested classes kept by R8.
            // The exception type above is checked by class hierarchy.
            val frame = error.stackTrace.firstOrNull { it.className.startsWith("org.medianexus.music.") }
            val place = when (frame?.className?.substringBefore('$')) {
                PlaybackService::class.java.name, QueuePlayer::class.java.name -> DiagnosticPlace.PLAYBACK
                DownloadsJob::class.java.name -> DiagnosticPlace.DOWNLOADS
                Nexus::class.java.name -> DiagnosticPlace.NEXUS_SERVER
                else -> DiagnosticPlace.APP
            }
            return DiagnosticFault(place, shape, frame?.lineNumber?.takeIf { it > 0 })
        }
    }
}

internal enum class DiagnosticNote(val label: String) {
    PLAYER_STARTED("Player started"),
    FOREGROUND_ENTERED("Foreground entered"),
    SOUND_MOVED("Audio output is moving"),
    AUTO_ADMITTED("Android Auto connected"),
    AUTO_REFUSED("Android Auto connection refused"),
    CONTROLLER_ADMITTED("Other controller connected"),
    CONTROLLER_REFUSED("Other controller connection refused"),
    SIGN_IN_DONE("Nexus sign-in done"),
    SIGN_IN_REFUSED("Nexus sign-in refused"),
    SIGN_IN_BUSY("Nexus sign-in had too many tries"),
    MUSIC_LOGIN_AGAIN("Music login asked for again"),
    START_AGAIN("Nexus asked to start again"),
    EVENTS_SENT("Events sent"),
    EVENTS_REFUSED("Events refused"),
    APP_MESSAGE("App message shown"),
    DOWNLOADS_FOLDER_CHANGED("Downloads folder changed"),
    MEDIA_BUTTON_STARTED("Media button started the player"),
    MEDIA_BUTTON_EMPTY("Media button had nothing to resume"),
    MEDIA_BUTTON_REFUSED("Android refused to start the player from a media button"),
    LIBRARY_UNAVAILABLE("Shared library could not be loaded"),
}

internal enum class DiagnosticReach(val wire: String) {
    NONE("none"), NAME("name"), CONNECTION("connection"), SECURE("secure"), ANSWERED("answered"),
}

internal enum class DiagnosticNetwork(val wire: String) {
    WIFI("wifi"), MOBILE("mobile"), ETHERNET("ethernet"), VPN("vpn"), OTHER("other"), UNKNOWN("unknown");

    companion object {
        fun fromTransport(transport: String): DiagnosticNetwork = when {
            transport.startsWith("VPN") -> VPN
            transport == "Wi-Fi" -> WIFI
            transport == "mobile data" -> MOBILE
            transport == "Ethernet" -> ETHERNET
            transport == "other" -> OTHER
            else -> UNKNOWN
        }
    }
}

internal enum class DiagnosticSessionEnd(val wire: String) {
    SIGNED_OUT("signed_out"), FORCED_OUT("forced_out"), EXPIRED("expired"),
    PASSWORD_CHANGED("password_changed"), PASSWORD_RESET("password_reset"), DATA_PUT_BACK("data_put_back"),
    SWITCHED_OFF("account_switched_off"), DELETED("account_deleted"), OTHER("other"),
}

internal data class DiagnosticsState(
    val switchedOn: Boolean = true,
    val signedIn: Boolean = false,
    val offline: Boolean = false,
    val knowsDefault: Boolean = false,
    val choiceSaved: Boolean = true,
) {
    val status: String? get() = when {
        !choiceSaved -> "The diagnostics choice couldn’t be saved. Trying again."
        !signedIn -> "Sign in to a Nexus server to send diagnostics."
        offline -> "Diagnostics are paused in Offline mode."
        !switchedOn -> "Sending is off."
        !knowsDefault -> "Waiting to hear from your Nexus server."
        else -> null
    }
}

internal data class PhoneDiagnostics(
    val androidMajor: Int = 0,
    val https: Boolean = false,
    val localPermission: Boolean? = null,
    val auto: Boolean? = null,
)

internal class CleanDiagnostics(
    private val root: File,
    private val version: String,
    private val wall: () -> Long,
    private val loss: () -> JournalLoss,
    private val journalFault: (DiagnosticFault) -> Unit,
) {
    private val work = ConcurrentLinkedQueue<() -> Unit>()
    private val started = AtomicBoolean()
    private val worker = Thread(::run, "nexus-diagnostics").apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
    }
    private val changes = ConcurrentLinkedQueue<Observation>()
    private val queued = AtomicInteger()
    private val checkpointQueued = AtomicBoolean()
    private val use = AtomicInteger()
    private var reportedUse: Boolean? = null
    private var reportedSink: RecordingEvent? = null
    private var failedChoice: Choice? = null
    private var choiceRetryAt = 0L
    private val earliestFault = java.util.concurrent.atomic.AtomicLong()
    private val choiceFile = File(root, "choice")
    private val crashFile = File(root, "crash")
    private val state = MutableStateFlow(DiagnosticsState())
    val settings = state.asStateFlow()
    private val preview = MutableStateFlow<String?>(null)
    val page = preview.asStateFlow()

    @Volatile private var about = PhoneDiagnostics()
    @Volatile private var send: RecordingEvent? = null
    private data class Choice(val enabled: Boolean?)
    private val preferred = AtomicReference<Choice?>()
    private data class Mode(val signedIn: Boolean, val offline: Boolean, val default: Boolean?)
    @Volatile private var mode = Mode(signedIn = false, offline = false, default = null)
    private var crashLoaded = false
    private var previousCrash: kotlinx.serialization.json.JsonElement? = null
    private var clearPending = false
    private val pending = ArrayList<Observation>()

    private sealed interface Line {
        data class Note(val note: DiagnosticNote) : Line
        data class Fault(val fault: DiagnosticFault) : Line
        data class Foreground(val millis: Long) : Line
        data class JobStop(val reason: Int) : Line
        data class SessionEnd(val reason: DiagnosticSessionEnd) : Line
        data class Attempt(
            val reach: DiagnosticReach,
            val local: Boolean?,
            val permission: Boolean?,
            val network: DiagnosticNetwork,
            val failed: Boolean,
        ) : Line
    }

    private data class Observation(val at: Long, val line: Line) {
        fun wire() = buildJsonObject {
            put("at", at)
            put("line", when (val value = line) {
                is Line.Note -> recordingEvent("note", buildJsonObject { put("label", value.note.label) })
                is Line.Fault -> value.fault.reportWire()
                is Line.Foreground -> recordingEvent("foreground", buildJsonObject { put("after_ms", value.millis.coerceIn(0, 3_600_000)) })
                is Line.JobStop -> recordingEvent("download_job_stopped", buildJsonObject { put("reason", value.reason.coerceIn(0, 255)) })
                is Line.SessionEnd -> recordingEvent("session_end", JsonPrimitive(value.reason.wire))
                is Line.Attempt -> recordingEvent("attempt", buildJsonObject {
                    put("reach", value.reach.wire)
                    put("local", value.local?.let(::JsonPrimitive) ?: JsonNull)
                    put("permission", value.permission?.let(::JsonPrimitive) ?: JsonNull)
                    put("network", value.network.wire)
                    put("failed", value.failed)
                })
            })
        }
    }

    fun attach(emit: RecordingEvent) {
        val changed = send != emit
        send = emit
        if (changed) refresh()
    }

    private fun startWorker() {
        if (started.compareAndSet(false, true)) worker.start()
    }

    private fun enqueue(task: () -> Unit) {
        work.offer(task)
        LockSupport.unpark(worker)
    }

    private fun run() {
        var nextCheckpoint = System.nanoTime()
        while (true) {
            val task = work.poll()
            if (task != null) safe(task)
            val now = System.nanoTime()
            failedChoice?.takeIf { now - choiceRetryAt >= 0 }?.let(::keepChoice)
            if (now - nextCheckpoint >= 0 && checkpointQueued.getAndSet(false)) {
                safe { publishCheckpoint() }
                nextCheckpoint = now + CHECKPOINT_NANOS
            }
            if (task == null && work.isEmpty()) {
                val checkpointDelay = if (checkpointQueued.get()) nextCheckpoint - System.nanoTime() else Long.MAX_VALUE
                val choiceDelay = if (failedChoice != null) choiceRetryAt - System.nanoTime() else Long.MAX_VALUE
                val delay = minOf(checkpointDelay, choiceDelay)
                if (delay == Long.MAX_VALUE) LockSupport.park() else if (delay > 0) LockSupport.parkNanos(delay)
            }
        }
    }

    fun recordingNotice(): String? = safe {
        (Core.nexusDiagnostics(buildJsonObject {
            put("root", root.path)
            put("notice", buildJsonObject {})
        }) as JsonPrimitive).contentOrNull
    }

    fun about(value: PhoneDiagnostics) {
        if (about != value) {
            about = value
            refresh()
        }
    }

    fun state(signedIn: Boolean, offline: Boolean, serverDefault: Boolean?) {
        val next = Mode(signedIn, offline, serverDefault)
        if (mode != next) {
            mode = next
            refresh()
        }
    }

    /** Called on the driver thread before start, which restores the same explicit choice. */
    fun choice(): Boolean? {
        if (preferred.get() == null) {
            val kept = safe {
                choiceFile.inputStream().use { file ->
                    when (file.read()) {
                        '1'.code -> true.takeIf { file.read() == -1 }
                        '0'.code -> false.takeIf { file.read() == -1 }
                        else -> null
                    }
                }
            }
            preferred.compareAndSet(null, Choice(kept))
        }
        return preferred.get()?.enabled
    }

    fun choose(value: Boolean) {
        startWorker()
        val choice = Choice(value)
        preferred.set(choice)
        state.value = state.value.copy(switchedOn = value)
        safe { send?.invoke(recordingEvent("diagnostics_switch", JsonPrimitive(value))) }
        enqueue { keepChoice(choice) }
    }

    fun forgetChoice() {
        startWorker()
        val choice = Choice(null)
        preferred.set(choice)
        enqueue { keepChoice(choice) }
    }

    private fun keepChoice(choice: Choice) {
        if (preferred.get() !== choice) {
            if (failedChoice === choice) failedChoice = null
            return
        }
        val saved = safe {
            when (choice.enabled) {
                null -> {
                    if (choiceFile.exists()) {
                        if (!choiceFile.delete()) throw IOException("Diagnostics choice unavailable")
                        org.medianexus.music.store.syncDirOrFail(root)
                    }
                }
                else -> writeAtomically(choiceFile, byteArrayOf(if (choice.enabled) '1'.code.toByte() else '0'.code.toByte()), durable = true)
            }
            true
        } == true
        failedChoice = if (saved) null else choice
        choiceRetryAt = System.nanoTime() + CHOICE_RETRY_NANOS
        updateState()
    }

    fun note(value: DiagnosticNote) = offer(Line.Note(value))
    fun foreground(millis: Long) = offer(Line.Foreground(millis))
    fun downloadStopped(reason: Int) = offer(Line.JobStop(reason))
    fun sessionEnded(reason: DiagnosticSessionEnd) = offer(Line.SessionEnd(reason))
    fun attempt(reach: DiagnosticReach, local: Boolean?, permission: Boolean?, network: DiagnosticNetwork, failed: Boolean) =
        offer(Line.Attempt(reach, local, permission, network, failed))

    fun fault(value: DiagnosticFault) {
        val at = wall()
        journalFault(value)
        earliestFault.getAndUpdate { old -> if (old == 0L) at else minOf(old, at) }
        offer(Line.Fault(value), at)
    }

    private fun flushFault() {
        val emit = send ?: return
        val at = earliestFault.getAndSet(0)
        if (at != 0L) emit(recordingEvent("diagnostics_fault", buildJsonObject { put("at_ms", at) }))
    }

    fun visible(value: Boolean) = used(VISIBLE, value)
    fun playing(value: Boolean) = used(PLAYING, value)

    private fun used(bit: Int, value: Boolean) {
        val previous = use.getAndUpdate { old -> if (value) old or bit else old and bit.inv() }
        if ((previous == 0) != (use.get() == 0)) queueCheckpoint()
    }

    private fun offer(line: Line, at: Long = wall()) {
        if (queued.getAndIncrement() < MAX_QUEUE) changes.add(Observation(at, line))
        else queued.decrementAndGet()
        queueCheckpoint()
    }

    fun refresh() {
        startWorker()
        queueCheckpoint()
    }

    private fun queueCheckpoint() {
        checkpointQueued.set(true)
        LockSupport.unpark(worker)
    }

    private fun publishCheckpoint() {
        val emit = send
        val inUse = use.get() != 0
        if (emit != null && (reportedUse != inUse || reportedSink != emit)) {
            reportedUse = inUse
            reportedSink = emit
            emit(recordingEvent("in_use", JsonPrimitive(inUse)))
        }
        flushFault()
        updateState()
        if (checkpoint() != null || preview.value == null) preview.value = readText()
    }

    fun clear() {
        startWorker()
        enqueue {
            pending.clear()
            clearPending = true
            if (checkpoint() != null) preview.value = readText()
            else if (preview.value == null) preview.value = readText()
        }
    }

    /** Page, export and machine start from the same canonical record via their own paths. */
    fun export(open: () -> OutputStream?) {
        startWorker()
        enqueue {
            val text = readText() ?: return@enqueue
            safe { open()?.use { it.write(text.encodeToByteArray()) } }
        }
    }

    fun readForMachine(scheduleKept: Boolean, answer: (String?) -> Unit) {
        startWorker()
        enqueue {
            val text = if (scheduleKept && checkpoint() != null) readText() else null
            answer(text)
        }
    }

    /** Install once the app is running. A crash writes only the classified event. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val at = wall()
                val fault = DiagnosticFault.crash(error)
                // This is the terminating thread, never a normal playback operation.
                // A crash record is typed and recovered independently of report replacement.
                val bytes = buildJsonObject {
                    put("at", at)
                    put("line", fault.reportWire())
                }.toString().encodeToByteArray()
                safe { writeAtomically(crashFile, bytes, durable = true) }
            } finally {
                previous.uncaughtException(thread, error)
            }
        }
    }

    private fun updateState() {
        val choice = choice()
        val current = mode
        val default = current.default
        val on = safe {
            (Core.nexusDiagnostics(buildJsonObject {
                put("root", root.path)
                put("enabled", buildJsonObject {
                    put("choice", choice?.let(::JsonPrimitive) ?: JsonNull)
                    put("server_default", default?.let(::JsonPrimitive) ?: JsonNull)
                })
            }) as JsonPrimitive).booleanOrNull
        } ?: state.value.switchedOn
        state.value = DiagnosticsState(on, current.signedIn, current.offline, default != null, failedChoice == null)
    }

    private fun checkpoint(): String? = safe {
        if (!crashLoaded) {
            crashLoaded = true
            previousCrash = safe {
                crashFile.inputStream().use { file ->
                    require(file.channel.size() <= CRASH_BYTES)
                    kotlinx.serialization.json.Json.parseToJsonElement(file.readBytes().decodeToString(throwOnInvalidSequence = true))
                }
            }
        }
        while (true) {
            val observation = changes.poll() ?: break
            queued.decrementAndGet()
            pending.add(observation)
        }
        if (pending.size > MAX_QUEUE) pending.subList(0, pending.size - MAX_QUEUE).clear()
        val phone = about
        val current = mode
        val result = Core.nexusDiagnostics(buildJsonObject {
            put("root", root.path)
            put("checkpoint", buildJsonObject {
                put("about", buildJsonObject {
                    put("version", version)
                    put("android_major", phone.androidMajor)
                    put("signed_in", current.signedIn)
                    put("https", phone.https)
                    put("offline", current.offline)
                    put("local_permission", phone.localPermission?.let(::JsonPrimitive) ?: JsonNull)
                    put("android_auto", phone.auto?.let(::JsonPrimitive) ?: JsonNull)
                    put("loss", loss().wire())
                })
                put("observations", kotlinx.serialization.json.JsonArray(listOfNotNull(previousCrash) + pending.map(Observation::wire)))
                put("clear", clearPending)
            })
        }) as JsonPrimitive
        pending.clear()
        clearPending = false
        if (previousCrash != null) {
            previousCrash = null
            crashFile.delete()
            send?.invoke(recordingEvent("diagnostics_fault", buildJsonObject { put("at_ms", wall()) }))
        }
        result.content
    }

    private fun readText(): String? = safe {
        (Core.nexusDiagnostics(buildJsonObject { put("root", root.path); put("read", JsonNull) }) as JsonPrimitive).contentOrNull
    }

    companion object {
        private const val CHECKPOINT_NANOS = 1_000_000_000L
        private const val CHOICE_RETRY_NANOS = 5_000_000_000L
        private const val MAX_QUEUE = 256
        private const val CRASH_BYTES = 1_024
        private const val VISIBLE = 1
        private const val PLAYING = 2
    }
}
