package org.medianexus.music

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
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
import org.medianexus.music.net.MusicLogin
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Subsonic
import org.medianexus.music.net.normalizeServerUrl
import org.medianexus.music.nexus.Folders
import org.medianexus.music.nexus.KeptAccount
import org.medianexus.music.nexus.KeptMusic
import org.medianexus.music.nexus.NexusKept
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.accountKey

/** Whether anyone is signed in. */
sealed interface SessionState {
    /** Still reading the saved sign-in, just after start-up. */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val account: Account, val api: Subsonic) : SessionState
}

/**
 * Where the phone's own part of a sign-in stands: what the Nexus server's machine says of signing
 * in is its own (`Nexus.shown`); this is only what happens on the phone after the backend has
 * handed over a music login, before the account is published.
 */
data class SignInState(
    /** The music server the backend handed over leads inside the house on this network, and the
     *  app may not reach it (Android 17): the screen explains and asks before it is connected to
     *  (6.4). Answered either way, [Session.continueSignIn] carries on. */
    val musicNeedsPermission: Boolean = false,
    /** The music server could not be reached at all, and the permission may be why: the screen
     *  says so and offers to ask. [Session.continueSignIn] carries on either way. */
    val musicMayBeWhy: Boolean = false,
    /** Signed in at the backend, but the person's choices of the account before could not be
     *  put away on the phone, so nobody was signed in here. */
    val choicesNotPutAway: Boolean = false,
)

/**
 * The signed-in account and its client for the music server. **Signing in is the Nexus
 * server's** (`nexus/Nexus.kt`, the shared core's machine): this is what the phone does with
 * what it hands over, and how a start begins from what it left.
 *
 * - **A cold start reads what sign-in left on the phone and plays** (the backend design's 6.1):
 *   the music login, kept sealed with the rest of the sign-in, and who it is for. Nothing is
 *   asked of the backend first, and nothing of the shared library is waited for; the client for
 *   the music server is made from the salt and token the backend handed over ([MusicLogin]).
 * - **A music login handed over** ([handed]) is put in place in the client already published if
 *   it is the same music server and user (a renewal: nothing of the account is made again), and
 *   otherwise taken on as an account: its folder found ([Folders]), its choices taken on, then
 *   published.
 * - **Its music login forgotten** ([forgotten]: signed out here, or by the backend) ends the
 *   account on the phone at once: nobody is signed in; the queue and Recently played go;
 *   downloads stay, under that person.
 *
 * Every change of who is signed in is a step in one queue, run one at a time in the order asked
 * for, so a start's restore can't land after a sign-out.
 */
class Session(
    private val settings: SettingsStore,
    private val kept: NexusKept,
    private val folders: Folders,
    private val http: OkHttpClient,
    private val offline: OfflineSwitch,
    private val dirsFor: (key: String) -> AccountDirs,
    private val notices: Notices,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Makes the phone's shared choices (pins, list choices, the theme) this account's, by
     *  its key, before it is said to be signed in: the account before's are put away first
     *  (`sync/Belongs.kt`). False if that couldn't be done: then nobody is signed in. */
    private val takeOn: suspend (key: String) -> Boolean = { true },
    /** Whether the music server at an address leads inside the house on this network and the
     *  local-network permission is not held (6.4): asked before it is first connected to. */
    private val needsPermission: suspend (address: String) -> Boolean = { false },
    /** Whether a failure to reach an address may be for want of that permission. */
    private val mayBeWhy: suspend (address: String) -> Boolean = { false },
    /** Told the number of a music login the music server refused (the shared core's rule). */
    private val refusedLogin: (count: Long) -> Unit = {},
) {
    private val current = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = current.asStateFlow()

    /** The client for the signed-in account; null when nobody is (or not yet known). */
    val api: StateFlow<Subsonic?> = ApiOf(current)

    private val signingIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = signingIn.asStateFlow()

    /** The steps that change who is signed in, run by one consumer (see the class). */
    private val steps = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** A login handed over and waiting for the person to answer a question about its music
     *  server (see [SignInState]): in memory only. */
    private var waiting: Pair<KeptMusic, KeptAccount>? = null

    init {
        scope.launch(io) { for (step in steps) step() }
        // The sign-in is opened by the Keystore, which can take a moment: not on the main
        // thread. Nothing is asked of the backend or the music server here.
        inOrder {
            val parts = kept.load()
            val music = parts.music
            val account = parts.account
            val restored = if (music != null && account != null) signedIn(music, account) else null
            // **Through the same door as a sign-in**: the account is taken on before it is said
            // to be signed in. If the choices in the settings are another account's and can't
            // be put away, nobody is signed in, and the sign-in screen says why.
            if (restored != null && !takeOn(restored.account.key)) {
                signingIn.value = SignInState(choicesNotPutAway = true)
                current.value = SessionState.SignedOut
                return@inOrder
            }
            current.value = restored ?: SessionState.SignedOut
        }
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

    /** The account for a music login and who it is for: its folder, and a client made from the
     *  salt and token handed over. */
    private fun signedIn(music: KeptMusic, who: KeptAccount): SessionState.SignedIn {
        val login = MusicLogin(music.handed).also { login -> login.onRefused = { refusedLogin(it.count) } }
        val api = Subsonic(music.address, music.name, login, http, offline)
        val key = folders.of(Folders.personOf(who))
        return SessionState.SignedIn(Account(api.server, api.username, key, dirsFor(key)), api)
    }

    /**
     * The Nexus server's machine handed the player this music login (`Hand::Login`), for `who`.
     * On the machine's thread: queued, never waited for there.
     */
    fun handed(music: KeptMusic, who: KeptAccount?) {
        inOrder {
            val now = current.value as? SessionState.SignedIn
            if (now != null && normalizeServerUrl(music.address) == now.api.server && music.name == now.api.username) {
                // A renewal: the same music server and user, a new salt and token (or the same
                // one, at a start). Put in place in the client every request uses, at once.
                now.api.login.renew(music.handed)
                return@inOrder
            }
            if (who == null) return@inOrder
            // **6.4, for the music server's address as the backend handed it**: judged before
            // it is first connected to. The screen explains and asks, then [continueSignIn].
            if (needsPermission(music.address)) {
                waiting = music to who
                signingIn.value = SignInState(musicNeedsPermission = true)
                return@inOrder
            }
            adopt(music, who, judged = false)
        }
    }

    /** The question about the music server has been answered (either way), or the person chose
     *  to carry on: the sign-in that waited on it goes on. */
    fun continueSignIn() {
        inOrder {
            val (music, who) = waiting ?: return@inOrder
            waiting = null
            adopt(music, who, judged = true)
        }
    }

    /** Takes on the account a music login is for, and publishes it. In a step. */
    private suspend fun adopt(music: KeptMusic, who: KeptAccount, judged: Boolean) {
        val person = Folders.personOf(who)
        // **Section 11's carry-over, once:** the first sign-in on this version, if the login
        // names the same music server and user as the sign-in this phone had before, is given
        // that folder (its downloads, settings and queue). The earlier sign-in is forgotten
        // whatever happens, so that nobody later is given it.
        val before = settings.value
        if (before.server.isNotEmpty() || before.username.isNotEmpty()) {
            if (normalizeServerUrl(music.address) == before.server && music.name == before.username && folders.of(person) == person) {
                folders.carry(person, accountKey(before.server, before.username))
            }
            settings.update { it.copy(server = "", username = "") }
        }
        val account = signedIn(music, who)
        // **And again after a failure in which no connection was opened** (6.4): one look at
        // the music server before the account is published, a few seconds at most. Reached,
        // refused, or not the permission's fault: on it goes.
        if (!judged) {
            val failed = withContext(io) {
                kotlinx.coroutines.withTimeoutOrNull(LOOK_MILLIS) {
                    try {
                        account.api.ping()
                        null
                    } catch (e: org.medianexus.music.net.ApiError.Network) {
                        e
                    } catch (e: org.medianexus.music.net.ApiError) {
                        null
                    }
                }
            }
            if (failed != null && !failed.answered && mayBeWhy(music.address)) {
                waiting = music to who
                signingIn.value = SignInState(musicMayBeWhy = true)
                return
            }
        }
        if (!takeOn(account.account.key)) {
            signingIn.value = SignInState(choicesNotPutAway = true)
            return
        }
        settings.update { it.copy(folder = account.account.key) }
        signingIn.value = SignInState()
        current.value = account
    }

    /** The machine set the login of this number aside (`Hand::Refused`). */
    fun refused(count: Long) {
        (current.value as? SessionState.SignedIn)?.api?.login?.refuseCount(count)
    }

    /**
     * The music login is forgotten (`Forget::Music`: signed out here, or by the backend). Nobody
     * is signed in from now; the queue and Recently played go; downloads stay. The job returned
     * finishes when it has.
     */
    fun forgotten() = inOrder {
        waiting = null
        signingIn.value = SignInState()
        val was = current.value as? SessionState.SignedIn
        current.value = SessionState.SignedOut
        settings.update { it.copy(folder = "") }
        if (was != null) withContext(io) {
            for (name in listOf("queue.json", "queue-place.json", "history.json")) File(was.account.dirs.data, name).delete()
        }
    }

    /** What is kept of the sign-in (for the tests, which stand in for the Nexus server's machine
     *  where it is not what they are about). */
    internal val keptForTests: NexusKept get() = kept

    /** What the machine said to keep could not be written: signed in for now, asked again at
     *  the next start. */
    fun notKept() = notices.post(Notice.SignInNotKept)

    private companion object {
        /** The look at the music server before an account is published (see [adopt]). */
        const val LOOK_MILLIS = 6_000L
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
