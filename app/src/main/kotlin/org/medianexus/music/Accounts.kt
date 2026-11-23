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
class Accounts(private val session: Session, scope: CoroutineScope) {
    private val current = MutableStateFlow<AccountGraph?>(null)

    /** The signed-in account's objects; null when nobody is signed in, or it isn't known
     *  yet. */
    val state: StateFlow<AccountGraph?> = current.asStateFlow()

    init {
        scope.launch {
            session.state.collect { now ->
                val before = current.value
                if (now is SessionState.SignedIn) {
                    if (before?.api !== now.api) {
                        before?.close()
                        current.value = AccountGraph(now.account, now.api)
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
        val now = session.restored()
        if (now !is SessionState.SignedIn) return null
        return withTimeoutOrNull(5_000) { state.first { it?.api === now.api } }
    }
}
