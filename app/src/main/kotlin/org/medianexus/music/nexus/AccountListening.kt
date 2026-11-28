package org.medianexus.music.nexus

import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.medianexus.music.core.Core

@Serializable
internal data class ListeningStats(
    val starts: ULong,
    val recent: List<Recent>,
    @SerialName("can_delete") val canDelete: Boolean,
) {
    @Serializable
    data class Recent(
        val title: String,
        val artist: String,
        val album: String,
        @SerialName("at_ms") val atMs: Long,
        val whenPlayed: String = "",
    )
}

internal sealed interface ListeningResult<out T> {
    data class Done<T>(val value: T) : ListeningResult<T>
    data class Error(val words: String) : ListeningResult<Nothing>
}

/** The last fetched page is for this session and life, including when Offline. */
internal class AccountListening(private val nexus: Nexus) {
    private data class Kept(val scope: AppScope, val stats: ListeningStats)

    private var kept: Kept? = null
    private val random by lazy { SecureRandom() }

    @Synchronized
    fun cached(scope: AppScope?): ListeningStats? = kept?.takeIf { it.scope == scope }?.stats

    @Synchronized
    fun sessionChanged(scope: AppScope?) {
        if (kept?.scope != scope) kept = null
    }

    @Synchronized
    private fun save(scope: AppScope, stats: ListeningStats): ListeningStats {
        if (nexus.appSession.value?.scope == scope) kept = Kept(scope, stats)
        return stats
    }

    suspend fun read(scope: AppScope): ListeningResult<ListeningStats> = inBackground {
        val answer = fetchStats(scope)
        val result = step(scope, "read") { put("answer", answer) }
        val stats = Json.decodeFromJsonElement<ListeningStats>(result)
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        ListeningResult.Done(save(scope, stats.copy(
            recent = stats.recent.map { it.copy(whenPlayed = format.format(Date(it.atMs))) },
        )))
    }

    suspend fun delete(scope: AppScope, password: String): ListeningResult<AppScope> = inBackground {
        val plan = buildJsonObject {
            put("stats", fetchStats(scope))
            put("action", random.nextLong().ushr(1).coerceAtLeast(1))
        }
        val grant = step(scope, "grant") { put("delete", plan); put("password", password) }
        val granted = send(scope, grant)
        val spend = step(scope, "spend") { put("delete", plan); put("answer", granted) }
        val deleted = send(scope, spend)
        val confirmation = step(scope, "confirm") { put("delete", plan); put("answer", deleted) }
        val now = nexus.appSession.value
        if (now == null || !now.scope.sameSession(scope) || now.scope.life != confirmation["life"]) {
            throw ListeningFailure(STOPPED)
        }
        save(now.scope, ListeningStats(0u, emptyList(), true))
        ListeningResult.Done(now.scope)
    }

    private suspend fun fetchStats(scope: AppScope): JsonObject = send(scope, step(scope, "stats"))

    private suspend fun send(scope: AppScope, command: JsonObject): JsonObject {
        val post = FeaturePost.read(command) ?: throw ListeningFailure(INVALID)
        return when (val answer = nexus.postFeature(scope, post)) {
            is FeatureResult.Checked -> buildJsonObject { put("status", answer.status); put("body", answer.body) }
            FeatureResult.Invalid -> throw ListeningFailure(INVALID)
            FeatureResult.Stopped -> throw ListeningFailure(STOPPED)
            FeatureResult.Unreachable -> throw ListeningFailure("Couldn't reach your Nexus server.")
        }
    }

    private fun step(scope: AppScope, step: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val result = Core.nexusListening(buildJsonObject {
            put("scope", scope.wire)
            put("step", step)
            fields()
        })
        val error = (result["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) throw ListeningFailure(error)
        return result
    }

    private suspend fun <T> inBackground(work: suspend () -> ListeningResult<T>): ListeningResult<T> =
        withContext(Dispatchers.Default) {
            try {
                work()
            } catch (e: ListeningFailure) {
                ListeningResult.Error(e.words)
            } catch (e: IllegalArgumentException) {
                ListeningResult.Error(INVALID)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                ListeningResult.Error(INVALID)
            }
        }

    private class ListeningFailure(val words: String) : Exception()

    private companion object {
        const val INVALID = "Your Nexus server couldn't complete this action. Try again."
        const val STOPPED = "Sign in again or turn off Offline mode to continue."
    }
}
