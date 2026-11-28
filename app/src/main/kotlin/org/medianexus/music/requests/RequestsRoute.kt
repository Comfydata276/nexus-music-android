package org.medianexus.music.requests

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.medianexus.music.core.Core
import org.medianexus.music.nexus.FeatureEndpoint
import org.medianexus.music.nexus.Folders
import org.medianexus.music.nexus.KeptAccount
import org.medianexus.music.nexus.Nexus
import org.medianexus.music.store.Account

internal sealed interface RequestsRoute {
    data class Unavailable(val words: String) : RequestsRoute

    class Usable(
        val person: String,
        val status: FeatureEndpoint,
        val direct: Boolean,
    ) : RequestsRoute
}

/** A positive status applies to one app session. Closing or replacing it also closes direct Lidarr. */
internal class RequestsRouter {
    private val refresh = MutableStateFlow(0)
    private val selected = MutableStateFlow<RequestsRoute>(RequestsRoute.Unavailable(CHECKING))
    val route: StateFlow<RequestsRoute> = selected

    fun refresh() = refresh.update { it + 1 }

    fun forAccount(account: Account): RequestsRoute.Usable? =
        usable()?.takeIf { it.person == account.nexusPerson }

    fun directEndpoint(): FeatureEndpoint? = usable()?.takeIf { it.direct }?.status

    private fun usable(): RequestsRoute.Usable? =
        (route.value as? RequestsRoute.Usable)?.takeIf { it.status.active() }

    suspend fun follow(nexus: Nexus) {
        combine(nexus.appSession, refresh) { session, looked -> session to looked }.collectLatest { (session, looked) ->
            val words = when {
                session == null -> "Sign in to Nexus Music to see Requests."
                session.offline -> "Turn off Offline mode to see Requests."
                else -> CHECKING
            }
            if (session == null || session.offline || !session.verified || looked == 0) {
                selected.value = RequestsRoute.Unavailable(words)
                return@collectLatest
            }
            val prior = usable()?.takeIf { it.status.scope == session.scope }
            if (prior == null) selected.value = RequestsRoute.Unavailable(CHECKING)
            val endpoint = FeatureEndpoint(nexus, session.scope)
            val reply = try {
                Core.nexusRequestsStatus(endpoint)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!endpoint.active()) return@collectLatest
            val route = (reply?.get("route") as? JsonPrimitive)?.contentOrNull
            if (route == "direct" || route == "server") {
                val direct = route == "direct"
                val scope = session.scope.wire
                val backend = (scope["backend"] as? JsonPrimitive)?.content ?: return@collectLatest
                val account = (scope["account"] as? JsonPrimitive)?.content ?: return@collectLatest
                val person = Folders.personOf(KeptAccount("", backend, account))
                selected.value = prior?.takeIf { it.direct == direct } ?: RequestsRoute.Usable(person, endpoint, direct)
            } else {
                val detail = (reply?.get("words") as? JsonPrimitive)?.contentOrNull ?: "Couldn't reach the Nexus server."
                selected.value = RequestsRoute.Unavailable(detail)
            }
        }
    }

    private companion object {
        const val CHECKING = "Checking whether Requests are set up on your Nexus server…"
    }
}
