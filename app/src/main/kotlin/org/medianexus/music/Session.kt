package org.medianexus.music

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.ServerInfo
import org.medianexus.music.net.Subsonic
import org.medianexus.music.net.addressesToTry
import org.medianexus.music.net.unencryptedBeyondHome
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.PasswordVault
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.accountKey

/** Whether anyone is signed in. */
sealed interface SessionState {
    /** Still reading the saved sign-in, just after start-up. */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val account: Account, val api: Subsonic) : SessionState
}

/** How the sign-in screen's request is going. */
data class SignInState(val busy: Boolean = false, val error: String? = null)

/**
 * The signed-in account and its client for the server. Signing in and out happen here, not
 * in a screen, so they carry on through a screen being rebuilt and work for the playback
 * service with no screen at all.
 */
class Session(
    private val settings: SettingsStore,
    private val vault: PasswordVault,
    private val http: OkHttpClient,
    private val offline: OfflineSwitch,
    private val dirsFor: (key: String) -> AccountDirs,
    private val notices: Notices,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val current = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = current.asStateFlow()

    /** The client for the signed-in account; null when nobody is (or not yet known). */
    val api: StateFlow<Subsonic?> = current
        .map { (it as? SessionState.SignedIn)?.api }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val signingIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = signingIn.asStateFlow()
    private var signInJob: Job? = null

    init {
        // The password is opened by the Keystore, which can take a moment: not on the main
        // thread. Nothing is asked of the server here; pages show what's kept and check after.
        scope.launch(io) {
            val saved = settings.value
            val password = if (saved.server.isNotEmpty() && saved.username.isNotEmpty()) {
                vault.load(saved.server, saved.username)
            } else {
                null
            }
            current.value = if (password != null) {
                signedIn(Subsonic(saved.server, saved.username, password, http, offline))
            } else {
                SessionState.SignedOut
            }
        }
    }

    /** Waits until the saved sign-in has been read. */
    suspend fun restored(): SessionState = state.first { it != SessionState.Restoring }

    private fun signedIn(api: Subsonic): SessionState.SignedIn {
        val key = accountKey(api.server, api.username)
        return SessionState.SignedIn(Account(api.server, api.username, key, dirsFor(key)), api)
    }

    /**
     * Signs in as the Windows app does: an address typed without "http://" or "https://" is
     * tried over HTTPS first, and over plain HTTP only if nothing answers there.
     */
    fun signIn(server: String, username: String, password: String) {
        if (signingIn.value.busy) return
        signingIn.value = SignInState(busy = true)
        val name = username.trim()
        signInJob = scope.launch {
            var answer: Result<Pair<Subsonic, ServerInfo>> = Result.failure(ApiError.Network("No address was given."))
            val addresses = addressesToTry(server)
            for ((index, address) in addresses.withIndex()) {
                val client = Subsonic(address, name, password, http, offline)
                // HTTPS was a guess: if nothing answers there soon, go on to plain HTTP
                // without waiting out the full time a real server is given.
                if (index + 1 < addresses.size && !client.answersWithin(GUESS_LIMIT_MILLIS)) continue
                answer = try {
                    Result.success(client to client.ping())
                } catch (e: ApiError) {
                    Result.failure(e)
                }
                // Signed in, or the server is there and says no: either way that settles it.
                val error = answer.exceptionOrNull()
                if (error == null || error is ApiError.Server) break
            }
            answer.fold(
                onSuccess = { (client, _) ->
                    settings.update { it.copy(server = client.server, username = name) }
                    val kept = withContext(io) { vault.save(client.server, name, password) }
                    if (!kept) notices.post(Notice.PasswordNotSaved)
                    if (unencryptedBeyondHome(client.server)) notices.post(Notice.SignedInWithoutEncryption)
                    current.value = signedIn(client)
                    signingIn.value = SignInState()
                    // Have connections open before the first taps need them.
                    scope.launch { client.warmUp(WARM_CONNECTIONS) }
                },
                onFailure = { error -> signingIn.value = SignInState(error = error.message) },
            )
        }
    }

    /** Signs out and forgets the password. The address and username stay, ready for signing
     *  in again. */
    fun signOut() {
        signInJob?.cancel()
        signingIn.value = SignInState()
        notices.dismiss(Notice.SignedInWithoutEncryption)
        current.value = SessionState.SignedOut
        scope.launch(io) { vault.delete() }
    }

    private companion object {
        const val GUESS_LIMIT_MILLIS = 5_000L
        const val WARM_CONNECTIONS = 6
    }
}
