package org.medianexus.music.requests

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.nexus.AppScope
import org.medianexus.music.nexus.FeatureEndpoint
import org.medianexus.music.store.writeAtomically

/** The phone keeps the original lines and job number. The server owns progress and changes. */
internal class ServerBatch(private val dir: File) {
    data class View(
        val scope: AppScope? = null,
        val notice: String = "",
        val rows: List<Row> = emptyList(),
        val working: Boolean = false,
    )

    data class Row(val title: String, val detail: String)

    @Serializable
    private data class SongLine(val artist: String, val album: String, val song: String)

    @Serializable
    private data class Saved(
        val request: Long,
        val session: JsonPrimitive,
        val singles: Boolean,
        val lines: List<SongLine>,
        val job: JsonPrimitive? = null,
        val resolutions: Map<Int, JsonElement> = emptyMap(),
    )

    @Serializable
    private data class Snapshot(val working: Boolean, val notice: String, val rows: List<Progress>)

    @Serializable
    private data class Progress(val index: Int, val detail: String, @SerialName("needs_resolution") val needsResolution: Boolean)

    private val shown = MutableStateFlow(View())
    val state: StateFlow<View> = shown

    suspend fun show(endpoint: FeatureEndpoint, songs: String?, singles: Boolean): String? = withContext(Dispatchers.IO) {
        if (!endpoint.active()) return@withContext null
        if (!deviceTurn.tryLock()) return@withContext if (songs == null) null else "A request batch is already running on this phone."
        try {
            poll(endpoint, songs, singles)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BatchFailure) {
            e.words
        } catch (e: IOException) {
            "Couldn't keep the batch on this phone."
        } catch (_: CoreHttp.Busy) {
            "Couldn't reach the Nexus server."
        } catch (_: Exception) {
            "Couldn't read the request batch."
        } finally {
            deviceTurn.unlock()
            if (shown.value.scope == endpoint.scope) shown.value = shown.value.copy(working = false)
        }
    }

    private suspend fun poll(endpoint: FeatureEndpoint, songs: String?, singles: Boolean) {
        val scope = endpoint.scope
        val restore = (scope.wire["restore"] as? JsonPrimitive)?.content ?: return
        val session = scope.wire["session"] as? JsonPrimitive ?: return
        val file = File(dir, "nexus-requests-batch-$restore.json")
        val previous = try {
            Json.decodeFromString(Saved.serializer(), file.readText())
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            null
        }

        var saved = if (songs == null) {
            previous ?: return
        } else {
            if (previous?.job != null) {
                val reply = ask(endpoint, "batch_progress", allowMissing = true) { put("job", previous.job) }
                if ((reply["working"] as? JsonPrimitive)?.booleanOrNull == true) {
                    throw BatchFailure("Your Nexus server is still handling the previous batch.")
                }
            }
            val lines = songs.lineSequence().filter(String::isNotBlank).map { text ->
                val fields = text.split('|', limit = 3).map(String::trim)
                if (fields.size != 3 || fields.first().isEmpty() || fields.last().isEmpty()) {
                    throw BatchFailure("Enter each song on its own line as Artist | Album | Song.")
                }
                SongLine(fields[0], fields[1], fields[2])
            }.take(2001).toList()
            if (lines.isEmpty() || lines.size > 2000) throw BatchFailure("Enter between 1 and 2,000 songs.")
            val pending = Saved(random.nextLong().ushr(1).coerceAtLeast(1), session, singles, lines)
            keep(file, pending)
            val answer = ask(endpoint, "batch_begin") {
                put("request", pending.request)
                put("singles", singles)
                put("lines", Json.encodeToJsonElement(lines))
            }
            val job = answer["job"] as? JsonPrimitive ?: throw BatchFailure(UNCONFIRMED)
            pending.copy(job = job).also { keep(file, it) }
        }

        val job = saved.job ?: throw BatchFailure(UNCONFIRMED)
        shown.value = View(scope, working = true)
        while (endpoint.active()) {
            val answer = ask(endpoint, "batch_progress") { put("job", job) }
            val snapshot = Json.decodeFromJsonElement<Snapshot>(answer)
            val rows = snapshot.rows.map { row ->
                val line = saved.lines.getOrNull(row.index) ?: throw BatchFailure("The Nexus server answered a different batch.")
                Row(line.song, "${line.artist} · ${row.detail}")
            }
            if (!endpoint.active()) return
            shown.value = View(scope, snapshot.notice, rows, snapshot.working)
            if (!snapshot.working) return
            if (session == saved.session) {
                for (row in snapshot.rows.filter(Progress::needsResolution)) {
                    saved = resolve(endpoint, file, saved, job, row.index, allowReplay = songs == null)
                }
            }
            delay(1000)
        }
    }

    private suspend fun resolve(
        endpoint: FeatureEndpoint,
        file: File,
        saved: Saved,
        job: JsonPrimitive,
        index: Int,
        allowReplay: Boolean,
    ): Saved {
        val original = saved.resolutions[index]
        if (original != null && !allowReplay) return saved
        val next = Core.nexusRequestStep(buildJsonObject {
            put("step", "batch_step")
            put("kept", original ?: JsonNull)
        })
        fun retaining(state: JsonElement) = saved.copy(resolutions = saved.resolutions + (index to state)).also { keep(file, it) }
        val release: JsonElement
        val sends: Int
        when ((next["step"] as? JsonPrimitive)?.content) {
            "lookup" -> {
                retaining(JsonPrimitive("Started"))
                val line = saved.lines.getOrNull(index) ?: throw BatchFailure("The Nexus server answered a different batch.")
                val lookup = ask(endpoint, "batch_lookup") {
                    put("line", Json.encodeToJsonElement(line))
                    put("singles", saved.singles)
                }
                release = lookup["release"] ?: JsonNull
                sends = 1
            }
            "send" -> {
                release = next["release"] ?: JsonNull
                sends = (next["sends"] as? JsonPrimitive)?.intOrNull ?: return saved
            }
            else -> return saved
        }
        retaining(buildJsonObject {
            put("Ready", buildJsonObject { put("release", release); put("sends", sends) })
        })
        ask(endpoint, "batch_resolve") {
            put("job", job)
            put("index", index)
            put("release", release)
        }
        return retaining(JsonPrimitive("Accepted"))
    }

    private suspend fun ask(
        endpoint: FeatureEndpoint,
        step: String,
        allowMissing: Boolean = false,
        fields: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        if (!endpoint.active()) throw CancellationException("Requests session changed")
        val result = Core.nexusRequest(endpoint, step, fields)
        if (!endpoint.active()) throw CancellationException("Requests session changed")
        val words = (result["error"] as? JsonPrimitive)?.contentOrNull
        if (words != null && !(allowMissing && (result["missing"] as? JsonPrimitive)?.booleanOrNull == true)) {
            throw BatchFailure(words)
        }
        return result
    }

    private fun keep(file: File, saved: Saved) {
        writeAtomically(file, Json.encodeToString(Saved.serializer(), saved).encodeToByteArray(), durable = true)
    }

    private class BatchFailure(val words: String) : Exception()

    private companion object {
        const val UNCONFIRMED = "The phone couldn't confirm the previous batch number."
        val deviceTurn = Mutex()
        val random by lazy { SecureRandom() }
    }
}
