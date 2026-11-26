package org.medianexus.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps an [AccountGraph] for whoever is signed in: a new one each time someone signs in,
 * and the old one closed when they sign out.
 */
class Accounts internal constructor(
    /** Whether anyone is signed in, and who (the session's own state). */
    private val states: StateFlow<SessionState>,
    private val restoredNow: suspend () -> SessionState,
    scope: CoroutineScope,
    /** Makes an account's objects (the app gives them what they need of it). */
    private val make: (SessionState.SignedIn) -> AccountGraph = { AccountGraph(it.account, it.api) },
    /** How long signing back in to an account waits for its old self to finish leaving. */
    private val leaveMillis: Long = LEAVE_MILLIS,
) {
    constructor(session: Session, scope: CoroutineScope, make: (SessionState.SignedIn) -> AccountGraph = { AccountGraph(it.account, it.api) }) :
        this(session.state, { session.restored() }, scope, make)

    private val current = MutableStateFlow<AccountGraph?>(null)

    /** The signed-in account's objects; null when nobody is signed in, or it isn't known
     *  yet. */
    val state: StateFlow<AccountGraph?> = current.asStateFlow()

    /** The account closed last, which may still be leaving. */
    private var leaving: AccountGraph? = null

    init {
        scope.launch {
            states.collect { now ->
                val before = current.value
                if (now is SessionState.SignedIn) {
                    if (before?.api !== now.api) {
                        if (before != null) {
                            current.value = null
                            close(before)
                        }
                        awaitLeft(now.account.key)
                        current.value = make(now)
                    }
                } else if (before != null) {
                    current.value = null
                    close(before)
                }
            }
        }
    }

    private fun close(graph: AccountGraph) {
        graph.close()
        leaving = graph
    }

    /**
     * **One owner of what is kept for an account, at a time.** Signing out of the music
     * server lets the account's requests go behind the person's back (a token is handed
     * back to its sign-in service, what Lidarr was asked is seen through and written down),
     * and that can take seconds. Signing straight back in to the same account waits for it,
     * [leaveMillis] at the longest: the account's objects are not made again while its old
     * self may still read or write the same files. If the old self has not finished by
     * then it is cut off from them ([AccountGraph.cutOff]): its secrets forgotten, as
     * leaving forgets them, and nothing it does afterwards touches a file.
     */
    private suspend fun awaitLeft(account: String) {
        val old = leaving ?: return
        if (old.account.key != account) return
        leaving = null
        if (withTimeoutOrNull(leaveMillis) { old.left() } == null) old.cutOff()
    }

    companion object {
        const val LEAVE_MILLIS = 15_000L
    }

    /**
     * Waits for the saved sign-in to be read, then gives the account that's signed in, or
     * null if nobody is. For things that start cold, the playback service above all.
     */
    suspend fun restored(): AccountGraph? {
        val now = restoredNow()
        if (now !is SessionState.SignedIn) return null
        return withTimeoutOrNull(5_000) { state.first { it?.api === now.api } }
    }
}
