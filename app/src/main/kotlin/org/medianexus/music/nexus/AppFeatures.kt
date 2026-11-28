package org.medianexus.music.nexus

import kotlinx.coroutines.CancellableContinuation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The core's session identity, with its unsigned numbers preserved for the trip back. */
internal class AppScope(val wire: JsonObject) {
    val life get() = wire["life"]

    override fun equals(other: Any?) = other is AppScope && wire == other.wire
    override fun hashCode() = wire.hashCode()

    fun sameSession(other: AppScope) = wire - "life" == other.wire - "life"

    fun matchesKept(saved: JsonObject?): Boolean {
        saved ?: return false
        val revoke = saved["sign_out"] as? JsonObject ?: return false
        return saved["backend_id"] == wire["backend"] &&
            saved["account"] == wire["account"] &&
            revoke["session"] == wire["session"] &&
            saved["restore"] == wire["restore"] &&
            saved["life"] == life
    }
}

internal data class AppSession(val scope: AppScope, val offline: Boolean, val verified: Boolean) {
    companion object {
        fun read(value: JsonObject): AppSession? {
            val scope = value["scope"] as? JsonObject ?: return null
            val offline = (value["offline"] as? JsonPrimitive)?.booleanOrNull ?: return null
            val verified = (value["verified"] as? JsonPrimitive)?.booleanOrNull ?: return null
            return AppSession(AppScope(scope), offline, verified)
        }
    }
}

internal class FeaturePost(val path: String, val body: String) {
    val deletesHistory get() = path == "/nexus/api/delete-listening-data"

    companion object {
        fun read(value: JsonObject): FeaturePost? {
            val path = (value["path"] as? JsonPrimitive)?.contentOrNull ?: return null
            val body = (value["body"] as? JsonPrimitive)?.contentOrNull ?: return null
            return FeaturePost(path, body)
        }
    }
}

internal sealed interface FeatureResult {
    class Checked(val status: Int, val body: String) : FeatureResult
    data object Stopped : FeatureResult
    data object Invalid : FeatureResult
    data object Unreachable : FeatureResult
}

/** Staging belongs to the driver thread. The send grant also checks the caller's cancellation. */
internal class FeatureFlight(
    val scope: AppScope,
    val deletesHistory: Boolean,
    val continuation: CancellableContinuation<FeatureResult>,
    val allowed: () -> Boolean,
) {
    var ask: JsonPrimitive? = null
    var answer: FeatureResult.Checked? = null
}

/** A Rust Requests operation keeps this endpoint for its entire lifetime. */
internal class FeatureEndpoint(val nexus: Nexus, val scope: AppScope, private val allowed: () -> Boolean = { true }) {
    val identity = numbers.incrementAndGet()

    fun active(): Boolean = nexus.featureOnline(scope) && allowed()

    private companion object {
        val numbers = java.util.concurrent.atomic.AtomicLong()
    }
}
