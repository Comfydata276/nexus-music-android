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
) {
    constructor(session: Session, scope: CoroutineScope, make: (SessionState.SignedIn) -> AccountGraph = { AccountGraph(it.account, it.api) }) :
        this(session.state, { session.restored() }, scope, make)

    private val current = MutableStateFlow<AccountGraph?>(null)

    /** The signed-in account's objects; null when nobody is signed in, or it isn't known
     *  yet. */
    val state: StateFlow<AccountGraph?> = current.asStateFlow()

    init {
        scope.launch {
            states.collect { now ->
                val before = current.value
                if (now is SessionState.SignedIn) {
                    // (At once: an account's music waits for nothing. What an earlier
                    // sign-in left of its requests is finished behind it, by what owns
                    // them: see `requests/RequestsOwners`.)
                    if (before?.api !== now.api) {
                        before?.close()
                        current.value = make(now)
                    }
                } else if (before != null) {
                    current.value = null
                    before.close()
                }
            }
        }
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
