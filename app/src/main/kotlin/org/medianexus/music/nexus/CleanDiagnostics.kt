package org.medianexus.music.nexus

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
            // Stack frames are used only to select a place. Their text and file names are
            // never handed to the report. Until the core adds its Android list it prints Other.
            val frame = error.stackTrace.firstOrNull {
                it.className.startsWith("org.medianexus.music.")
            }
            val place = when (frame?.fileName) {
                "PlaybackService.kt", "QueuePlayer.kt" -> DiagnosticPlace.PLAYBACK
                "DownloadsJob.kt" -> DiagnosticPlace.DOWNLOADS
                "Nexus.kt" -> DiagnosticPlace.NEXUS_SERVER
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

internal data class DiagnosticsState(
    val switchedOn: Boolean = true,
    val signedIn: Boolean = false,
    val offline: Boolean = false,
    val knowsDefault: Boolean = false,
) {
    val status: String? get() = when {
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
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nexus-diagnostics").apply { isDaemon = true }
    }
    private val changes = ConcurrentLinkedQueue<Observation>()
    private val queued = AtomicInteger()
    private val checkpointQueued = AtomicBoolean()
    private val use = AtomicInteger()
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
    @Volatile private var serverDefault: Boolean? = null
    @Volatile private var signedIn = false
    @Volatile private var offline = false
    private var crashLoaded = false
    private var previousCrash: kotlinx.serialization.json.JsonElement? = null
    private var clearPending = false
    private val pending = ArrayList<Observation>()

    private sealed interface Line {
        data class Note(val note: DiagnosticNote) : Line
        data class Fault(val fault: DiagnosticFault) : Line
        data class Foreground(val millis: Long) : Line
        data class JobStop(val reason: Int) : Line
    }

    private data class Observation(val at: Long, val line: Line) {
        fun wire() = buildJsonObject {
            put("at", at)
            put("line", when (val value = line) {
                is Line.Note -> recordingEvent("note", buildJsonObject { put("label", value.note.label) })
                is Line.Fault -> value.fault.reportWire()
                is Line.Foreground -> recordingEvent("foreground", buildJsonObject { put("after_ms", value.millis.coerceIn(0, 3_600_000)) })
                is Line.JobStop -> recordingEvent("download_job_stopped", buildJsonObject { put("reason", value.reason.coerceIn(0, 255)) })
            })
        }
    }

    fun attach(emit: RecordingEvent) {
        if (send != null) return
        send = emit
        if (use.get() != 0) emit(recordingEvent("in_use", JsonPrimitive(true)))
        flushFault()
    }

    fun about(value: PhoneDiagnostics) {
        about = value
        refresh()
    }

    fun state(signedIn: Boolean, offline: Boolean, serverDefault: Boolean?) {
        this.signedIn = signedIn
        this.offline = offline
        this.serverDefault = serverDefault
        refresh()
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
        preferred.set(Choice(value))
        state.value = state.value.copy(switchedOn = value)
        send?.invoke(recordingEvent("diagnostics_switch", JsonPrimitive(value)))
        worker.execute {
            safe { writeAtomically(choiceFile, byteArrayOf(if (value) '1'.code.toByte() else '0'.code.toByte()), durable = true) }
            updateState()
        }
    }

    fun forgetChoice() {
        preferred.set(Choice(null))
        worker.execute {
            choiceFile.delete()
            updateState()
        }
    }

    fun note(value: DiagnosticNote) = offer(Line.Note(value))
    fun foreground(millis: Long) = offer(Line.Foreground(millis))
    fun downloadStopped(reason: Int) = offer(Line.JobStop(reason))

    fun fault(value: DiagnosticFault) {
        val at = wall()
        journalFault(value)
        offer(Line.Fault(value), at)
        earliestFault.getAndUpdate { old -> if (old == 0L) at else minOf(old, at) }
        flushFault()
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
        if ((previous == 0) != (use.get() == 0)) {
            send?.invoke(recordingEvent("in_use", JsonPrimitive(use.get() != 0)))
        }
    }

    private fun offer(line: Line, at: Long = wall()) {
        if (queued.getAndIncrement() < MAX_QUEUE) changes.add(Observation(at, line))
        else queued.decrementAndGet()
        refresh()
    }

    fun refresh() {
        if (!checkpointQueued.compareAndSet(false, true)) return
        worker.execute {
            checkpointQueued.set(false)
            updateState()
            if (checkpoint() != null) preview.value = readText()
            else if (preview.value == null) preview.value = readText()
        }
    }

    fun clear() {
        worker.execute {
            pending.clear()
            clearPending = true
            if (checkpoint() != null) preview.value = readText()
            else if (preview.value == null) preview.value = readText()
        }
    }

    /** Page, export and machine start from the same canonical record via their own paths. */
    fun export(open: () -> OutputStream?) {
        worker.execute {
            val text = readText() ?: return@execute
            safe { open()?.use { it.write(text.encodeToByteArray()) } }
        }
    }

    fun readForMachine(scheduleKept: Boolean, answer: (String?) -> Unit) {
        worker.execute {
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
        val default = serverDefault
        val on = safe {
            (Core.nexusDiagnostics(buildJsonObject {
                put("root", root.path)
                put("enabled", buildJsonObject {
                    put("choice", choice?.let(::JsonPrimitive) ?: JsonNull)
                    put("server_default", default?.let(::JsonPrimitive) ?: JsonNull)
                })
            }) as JsonPrimitive).booleanOrNull
        } ?: state.value.switchedOn
        state.value = DiagnosticsState(on, signedIn, offline, default != null)
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
        val result = Core.nexusDiagnostics(buildJsonObject {
            put("root", root.path)
            put("checkpoint", buildJsonObject {
                put("about", buildJsonObject {
                    put("version", version)
                    put("android_major", phone.androidMajor)
                    put("signed_in", signedIn)
                    put("https", phone.https)
                    put("offline", offline)
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
        private const val MAX_QUEUE = 256
        private const val CRASH_BYTES = 1_024
        private const val VISIBLE = 1
        private const val PLAYING = 2
    }
}
