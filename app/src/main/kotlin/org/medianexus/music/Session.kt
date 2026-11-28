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

/** Whether anyone is signed in. */
sealed interface SessionState {
    /** Still reading the saved sign-in, just after start-up. */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    /** `person`: who they are to the Nexus server ([Folders.personOf]: its backend id and their
     *  account number), the identity an account is renewed in place under, and nothing else. */
    data class SignedIn(val account: Account, val api: Subsonic, val person: String = "") : SessionState
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
    /** Where the music server's address leads on this network (inside the house, or not; null
     *  if it couldn't be judged) and whether the local-network permission is held (null where
     *  the phone has none): looked at before it is first connected to (6.4). */
    private val look: suspend (address: String) -> Pair<Boolean?, Boolean?> = { null to null },
    /** Told what [look] found, for the login of this number and generation (`MusicLooked`). */
    private val looked: (inside: Boolean?, permission: Boolean?, count: String, generation: String) -> Unit = { _, _, _, _ -> },
    /** Whether a failure to reach an address may be for want of that permission. */
    private val mayBeWhy: suspend (address: String) -> Boolean = { false },
    /** Told the number of a music login the music server refused, and the generation it was
     *  handed under (the shared core's rule). */
    private val refusedLogin: (count: String, generation: String) -> Unit = { _, _ -> },
    /** The shared core's `carries_over`: whether the folder this phone had goes to the person a
     *  music login was handed to. */
    private val carriesOver: (beforeAddress: String, beforeUser: String, music: KeptMusic) -> Boolean = { _, _, _ -> false },
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
    private var waiting: Triple<KeptMusic, KeptAccount, String>? = null

    init {
        // **Nothing is published until the Nexus server's machine has started** (5.5, 6.1): its
        // start is local (no request), reads what sign-in left, forgets what is not to be used (a
        // sign-out not yet told, a login that does not read) and hands the player the login it
        // keeps; only then is an account published ([restoredFrom], [started]). Nothing is asked
        // of the backend or the music server for it.
        scope.launch(io) { for (step in steps) step() }
    }

    /**
     * The machine's start handed the player this login (`Hand::Login` in the answer to `start`):
     * **published at once**, as the person left it, nothing asked of anyone; where its address
     * leads is looked at afterwards and told to the machine (6.4). On the machine's thread:
     * queued.
     */
    fun restoredFrom(music: KeptMusic, who: KeptAccount, generation: String) {
        inOrder {
            val person = Folders.personOf(who)
            // (A start after the sign-in was kept and before its carry-over was settled finishes
            // that one step now, the same way.)
            if (!settle(person, music)) return@inOrder accountNotTakenOn()
            val restored = signedIn(music, who, generation)
            // **Through the same door as a sign-in**: the account is taken on before it is said
            // to be signed in. If the choices in the settings are another account's and can't
            // be put away, nobody is signed in, and the sign-in screen says why.
            if (!takeOn(restored.account.key)) {
                accountNotTakenOn()
                return@inOrder
            }
            publish(restored)
            scope.launch(io) {
                val (inside, permission) = look(music.address)
                looked(inside, permission, music.handed.count, generation)
            }
        }
    }

    /** The machine's start has been done, whatever it handed: with no login handed, nobody is
     *  signed in on the phone. */
    fun started() {
        inOrder { if (current.value == SessionState.Restoring) current.value = SessionState.SignedOut }
    }

    /** Claims the earlier folder only when the handed login matches the released sign-in. */
    private fun settle(person: String, music: KeptMusic): Boolean {
        val before = settings.value
        val settled = folders.settle(person, music, before.server, before.username, carriesOver)
        if (settled == Folders.Settled.NOT_KEPT) {
            notices.post(Notice.SignInNotKept)
            return false
        }
        if (settled == Folders.Settled.CLAIMED && (before.server.isNotEmpty() || before.username.isNotEmpty())) {
            settings.update { it.copy(server = "", username = "") }
        }
        return true
    }

    /** Puts `account` in front: its folder may be written again (a sign-out had fenced it). */
    private fun publish(account: SessionState.SignedIn) {
        org.medianexus.music.store.Fence.open(account.account.dirs.data)
        current.value = account
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
    private fun signedIn(music: KeptMusic, who: KeptAccount, generation: String): SessionState.SignedIn {
        val login = MusicLogin(music.handed, generation)
        // (Reported with the pass that was refused: its own number and generation.)
        login.onRefused = { pass -> refusedLogin(pass.handed.count, pass.generation) }
        val api = Subsonic(music.address, music.name, login, http, offline)
        val person = Folders.personOf(who)
        val key = folders.of(person)
        // (The music user's shared record is this person's only if the earlier folder, the one
        // that used it, was carried over to them: see `Account.ownsSharedRecord`.)
        return SessionState.SignedIn(Account(api.server, api.username, key, dirsFor(key), ownsSharedRecord = sharedRecordForTests ?: (key != person)), api, person)
    }

    /** The account in front is let go of before another is published: nothing more is sent with
     *  its login, and anything waiting on it is let go, unsent. */
    private fun letGo(was: SessionState.SignedIn?) {
        was?.api?.login?.retire()
    }

    /**
     * The Nexus server's machine handed the player this music login (`Hand::Login`), for `who`.
     * On the machine's thread: queued, never waited for there.
     */
    fun handed(music: KeptMusic, who: KeptAccount?, generation: String = "0") {
        inOrder {
            if (who == null) return@inOrder
            val person = Folders.personOf(who)
            if (!settle(person, music)) return@inOrder accountNotTakenOn()
            val now = current.value as? SessionState.SignedIn
            if (now != null && now.person == person && now.account.key == folders.of(person) &&
                normalizeServerUrl(music.address) == now.api.server && music.name == now.api.username
            ) {
                // A renewal: **the same person at the same backend**, the same music server and
                // user, a new salt and token (or the same one). Put in place in the client every
                // request uses, at once. (Two people handed the same music user are never this.)
                now.api.login.renew(music.handed, generation)
                return@inOrder
            }
            // **6.4, for the music server's address as the backend handed it**: judged before
            // it is first connected to, and told to the machine. Inside, without the
            // permission: the screen explains and asks, then [continueSignIn].
            val (inside, permission) = look(music.address)
            looked(inside, permission, music.handed.count, generation)
            if (inside == true && permission == false) {
                waiting = Triple(music, who, generation)
                signingIn.value = SignInState(musicNeedsPermission = true)
                return@inOrder
            }
            adopt(music, who, generation, judged = false)
        }
    }

    /** The question about the music server has been answered (either way), or the person chose
     *  to carry on: the sign-in that waited on it goes on. */
    fun continueSignIn() {
        inOrder {
            val (music, who, generation) = waiting ?: return@inOrder
            waiting = null
            // Answered either way: where it leads and the permission, told to the machine again.
            val (inside, permission) = look(music.address)
            looked(inside, permission, music.handed.count, generation)
            adopt(music, who, generation, judged = true)
        }
    }

    /** Takes on the account a music login is for, and publishes it. In a step. */
    private suspend fun adopt(music: KeptMusic, who: KeptAccount, generation: String, judged: Boolean) {
        val account = signedIn(music, who, generation)
        // **And again after a failure in which no connection was opened** (6.4): one look at
        // the music server before the account is published, a few seconds at most. Reached,
        // refused, or not the permission's fault: on it goes.
        if (!judged) {
            // (No answer in that time counts as one that never connected.)
            val failed = withContext(io) {
                kotlinx.coroutines.withTimeoutOrNull(LOOK_MILLIS) {
                    try {
                        account.api.ping()
                        false
                    } catch (e: org.medianexus.music.net.ApiError.Network) {
                        !e.answered
                    } catch (e: org.medianexus.music.net.ApiError) {
                        false
                    }
                } ?: true
            }
            if (failed && mayBeWhy(music.address)) {
                waiting = Triple(music, who, generation)
                signingIn.value = SignInState(musicMayBeWhy = true)
                return
            }
        }
        if (!takeOn(account.account.key)) {
            accountNotTakenOn()
            return
        }
        settings.update { it.copy(folder = account.account.key) }
        signingIn.value = SignInState()
        // **The account before is fenced first** (another person, or another login): its login
        // admits nothing more, before this one is in front.
        letGo(current.value as? SessionState.SignedIn)
        publish(account)
    }

    private fun accountNotTakenOn() {
        letGo(current.value as? SessionState.SignedIn)
        signingIn.value = SignInState(choicesNotPutAway = true)
        current.value = SessionState.SignedOut
        settings.update { it.copy(folder = "") }
    }

    /** The machine set the login of this number, handed under this generation, aside
     *  (`Hand::Refused`). */
    fun refused(count: String, generation: String? = null) {
        (current.value as? SessionState.SignedIn)?.api?.login?.refuseCount(count, generation)
    }

    /** `Hand::NoLogin`: another account was signed in with no music login: the one before is
     *  not the player's any more. Downloads stay where they are. */
    fun noLogin() = inOrder {
        waiting = null
        signingIn.value = SignInState()
        letGo(current.value as? SessionState.SignedIn)
        current.value = SessionState.SignedOut
        settings.update { it.copy(folder = "") }
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
        // Nothing more is sent with its login, from anywhere, from this instant.
        letGo(was)
        current.value = SessionState.SignedOut
        settings.update { it.copy(folder = "") }
        // **The queue and Recently played go, and stay gone**: fenced and deleted in one step, so
        // no writer still running for that account (the queue's saver, a history write under way,
        // the service saving as it lets go) can put either back (see `Fence`).
        if (was != null) withContext(io) {
            if (!org.medianexus.music.store.Fence.close(was.account.dirs.data, SIGNED_OUT_FILES)) notices.post(Notice.SignInNotKept)
        }
    }

    /** What is kept of the sign-in (for the tests, which stand in for the Nexus server's machine
     *  where it is not what they are about). */
    internal val keptForTests: NexusKept get() = kept

    /** What the machine said to keep could not be written: signed in for now, asked again at
     *  the next start. */
    fun notKept() = notices.post(Notice.SignInNotKept)

    internal companion object {
        /** The look at the music server before an account is published (see [adopt]). */
        const val LOOK_MILLIS = 6_000L

        /** For the tests of keeping devices in step only: whether an account is taken to own the
         *  music user's shared record, in place of [Account.ownsSharedRecord]'s rule. */
        @Volatile internal var sharedRecordForTests: Boolean? = null

        /** What a sign-out removes of an account's folder (downloads stay). */
        val SIGNED_OUT_FILES = listOf("queue.json", "queue-place.json", "history.json")
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
