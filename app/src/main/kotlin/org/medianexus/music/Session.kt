package org.medianexus.music

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.ServerInfo
import org.medianexus.music.net.Subsonic
import org.medianexus.music.net.addressesToTry
import org.medianexus.music.net.splitScheme
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

/** How the sign-in screen's request is going. `suggestHttp` is set when an address typed
 *  without a scheme got nowhere over HTTPS and wasn't tried over plain HTTP, because it is
 *  beyond the home network: the screen says how to ask for plain HTTP. */
data class SignInState(
    val busy: Boolean = false,
    val error: String? = null,
    val suggestHttp: Boolean = false,
    /** The server said yes, but the settings naming the account couldn't be written, so
     *  nobody was signed in. */
    val settingsNotSaved: Boolean = false,
)

/**
 * The signed-in account and its client for the server. Signing in and out happen here, not
 * in a screen, so they carry on through a screen being rebuilt and work for the playback
 * service with no screen at all.
 *
 * Every change of who is signed in (reading the saved sign-in at start-up, signing in,
 * signing out) is a step in one queue, run one at a time in the order asked for. So a
 * restore can't land after a sign-out, a sign-out's deletion can't land after the next
 * sign-in has saved its password, and `state` only changes once the password on disk agrees.
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

    /** The client for the signed-in account; null when nobody is (or not yet known). Read
     *  straight from `state`, so the two can never disagree. */
    val api: StateFlow<Subsonic?> = ApiOf(current)

    private val signingIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = signingIn.asStateFlow()
    private var signInJob: Job? = null

    /** The steps that change who is signed in, run by one consumer (see the class). */
    private val steps = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Moves on at every sign-out, so a sign-in or restore begun before it can tell it has
     *  been overtaken and must not sign anyone in. */
    private val generation = AtomicLong(0)

    /** Held while a sign-out moves `generation` on, and while a sign-in or restore looks at
     *  it and says "signed in": so the look and the saying are one step, and a sign-out is
     *  either before both (nobody is signed in) or after both (its own step signs them out). */
    private val gate = Any()

    /** Work that belongs to the signed-in session (warming up connections), stopped when it
     *  ends. Only touched by steps. */
    private var sessionWork: Job? = null

    init {
        scope.launch(io) { for (step in steps) step() }
        // The password is opened by the Keystore, which can take a moment: not on the main
        // thread. Nothing is asked of the server here; pages show what's kept and check after.
        val asked = generation.get()
        inOrder {
            val saved = settings.value
            val password = if (saved.server.isNotEmpty() && saved.username.isNotEmpty()) {
                vault.load(saved.server, saved.username)
            } else {
                null
            }
            val restored = password?.let { signedIn(Subsonic(saved.server, saved.username, it, http, offline)) }
            if (restored == null || !publish(asked, restored)) current.value = SessionState.SignedOut
        }
    }

    /** Says "signed in", unless a sign-out has been asked for since `asked` was read. */
    private fun publish(asked: Long, state: SessionState.SignedIn): Boolean = synchronized(gate) {
        if (generation.get() != asked) return false
        current.value = state
        true
    }

    /** Waits until the saved sign-in has been read. */
    suspend fun restored(): SessionState = state.first { it != SessionState.Restoring }

    /** Queues a step that changes who is signed in; the result says when it has run. */
    private fun <T> inOrder(step: suspend () -> T): Deferred<T> {
        val done = CompletableDeferred<T>()
        steps.trySend {
            try {
                done.complete(step())
            } catch (e: Throwable) {
                done.completeExceptionally(e)
                if (e is CancellationException) throw e
            }
        }
        return done
    }

    private fun signedIn(api: Subsonic): SessionState.SignedIn {
        val key = accountKey(api.server, api.username)
        return SessionState.SignedIn(Account(api.server, api.username, key, dirsFor(key)), api)
    }

    /**
     * Signs in. An address typed without "http://" or "https://" is tried over HTTPS first,
     * and over plain HTTP only if nothing answers there and the address is on the home
     * network (see `addressesToTry`).
     */
    fun signIn(server: String, username: String, password: String) {
        if (signingIn.value.busy) return
        signingIn.value = SignInState(busy = true)
        val name = username.trim()
        val asked = generation.get()
        signInJob = scope.launch {
            var answer: Result<Pair<Subsonic, ServerInfo>> = Result.failure(ApiError.Network("No address was given."))
            val addresses = addressesToTry(server)
            for ((index, address) in addresses.withIndex()) {
                // Asking to sign in always goes out, offline mode or not, as on Windows where
                // signing in makes a client of its own. The client kept afterwards shares the
                // app's switch, so offline mode stays as it was.
                val client = Subsonic(address, name, password, http, OfflineSwitch())
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
            val client = answer.getOrNull()?.first?.sharing(offline)
            if (client == null) {
                val error = answer.exceptionOrNull()
                // HTTPS was the only thing tried, and not because the person said so.
                val onlyGuessedHttps = addresses.size == 1 && splitScheme(server.trim()).first == null
                if (generation.get() == asked) {
                    signingIn.value = SignInState(error = error?.message, suggestHttp = onlyGuessedHttps && error !is ApiError.Server)
                }
                return@launch
            }
            inOrder {
                // Signed out while the server was being asked: that sign-out wins.
                if (generation.get() != asked) return@inOrder
                // The settings name the account first, and the password is saved only once
                // they're on disk: a failure between the two never leaves a new account's
                // password beside settings that name the old one. Without the password the
                // next start simply asks for it.
                settings.update { it.copy(server = client.server, username = name) }
                if (!settings.flush()) {
                    if (generation.get() == asked) signingIn.value = SignInState(settingsNotSaved = true)
                    return@inOrder
                }
                // Writing the settings took a moment, and saving the password takes another:
                // a sign-out asked for during either still wins. Its own step, queued behind
                // this one, removes whatever password was saved here.
                if (generation.get() != asked) return@inOrder
                val kept = withContext(io) { vault.save(client.server, name, password) }
                if (!publish(asked, signedIn(client))) return@inOrder
                if (!kept) notices.post(Notice.PasswordNotSaved)
                if (unencryptedBeyondHome(client.server)) notices.post(Notice.SignedInWithoutEncryption)
                signingIn.value = SignInState()
                // Have connections open before the first taps need them.
                sessionWork?.cancel()
                sessionWork = scope.launch { client.warmUp(WARM_CONNECTIONS) }
            }.await()
        }
    }

    /**
     * Signs out and forgets the password. The address and username stay, ready for signing
     * in again. Done once the saved password is gone from the phone; if it can't be removed,
     * the account stays signed in (it would be back at the next start anyway) and a notice
     * says so. The job returned finishes when the sign-out has.
     */
    fun signOut(): Job {
        synchronized(gate) { generation.incrementAndGet() }
        signInJob?.cancel()
        signingIn.value = SignInState()
        return inOrder {
            sessionWork?.cancel()
            sessionWork = null
            if (withContext(io) { vault.delete() }) {
                notices.dismiss(Notice.SignOutFailed)
                notices.dismiss(Notice.SignedInWithoutEncryption)
                current.value = SessionState.SignedOut
            } else {
                notices.post(Notice.SignOutFailed)
            }
        }
    }

    private companion object {
        const val GUESS_LIMIT_MILLIS = 5_000L
        const val WARM_CONNECTIONS = 6
    }
}

/** `state`'s client, as a `StateFlow` whose value is worked out from `state`'s at the moment
 *  it is read, rather than a copy kept up to date after it (which would lag behind). */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ApiOf(private val state: StateFlow<SessionState>) : StateFlow<Subsonic?> {
    override val value: Subsonic? get() = (state.value as? SessionState.SignedIn)?.api
    override val replayCache: List<Subsonic?> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Subsonic?>): Nothing {
        state.map { (it as? SessionState.SignedIn)?.api }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
