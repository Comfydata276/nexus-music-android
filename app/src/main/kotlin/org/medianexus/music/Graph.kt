package org.medianexus.music

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.medianexus.music.net.Http
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.playback.Callers
import org.medianexus.music.playback.DebugHosts
import org.medianexus.music.playback.KnownHosts
import org.medianexus.music.playback.PlayerConnection
import org.medianexus.music.playback.QueueSaver
import org.medianexus.music.store.KeystoreBox
import org.medianexus.music.store.PasswordVault
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.accountDirs

/**
 * The long-lived objects, built once when the process starts and handed to whatever needs
 * them. Screens and the playback service both take what they need from here; nothing here
 * belongs to a screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Graph(
    context: Context,
    /** What seals the saved password: the Android Keystore, except in a test of the
     *  saved-password start (the test runner has no Keystore). */
    passwordBox: org.medianexus.music.store.SecretBox = KeystoreBox(),
) {
    private val app: Context = context.applicationContext

    /** The app's words, for what is said from outside a screen (a toast after a question). */
    val resources: android.content.res.Resources get() = app.resources

    /** Work that outlives any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http = Http.client(userAgent = "NexusMusic/${BuildConfig.VERSION_NAME} (Android)")

    val notices = Notices()

    /** What the player has met, for Settings to show and share (see [Diagnostics]). */
    val diagnostics = Diagnostics(File(context.filesDir, "diagnostics.txt"), scope, Dispatchers.IO)

    /** Who may connect to the player and read covers: this app, callers Android trusts with
     *  media, and the known hosts of media apps (Google's list; see `KnownHosts`). Made when
     *  first asked: the screens never need it. */
    val callers: Callers by lazy {
        val listed = context.resources.getXml(R.xml.allowed_media_browser_callers).use { KnownHosts.parse(it) }
        Callers(context, listed, DebugHosts::listed)
    }

    /** Read here, at start-up, because the first frame needs the theme. The file is small. */
    val settings: SettingsStore = SettingsStore.open(File(context.filesDir, "settings.json"), scope, Dispatchers.IO)

    /** The apps the person has been asked about, and has allowed, to control playback
     *  (see [org.medianexus.music.playback.ControlApps]). Made when first asked. */
    val controlApps: org.medianexus.music.playback.ControlApps by lazy {
        org.medianexus.music.playback.ControlApps(callers, settings, note = { text, now -> diagnostics.note(text, now) })
    }

    /** A tap, with the account it was made under; or a mark in the queue, for whoever
     *  waits until everything tapped before it has been carried out. */
    private class Tapped(val did: org.medianexus.music.sync.Chosen.Did?, val account: String, val reached: kotlinx.coroutines.CompletableDeferred<Unit>? = null)

    private val chosen = kotlinx.coroutines.channels.Channel<Tapped>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    /**
     * The person changed one of the choices that are shared between devices (a pin, a
     * list's shuffle or sort, the theme), on a screen or with the player. It is made off the
     * main thread, in the order it was asked for, and **is on the disk before it shows as
     * made** (`sync/Chosen.kt`: if it is something shared, the shared core keeps it as the
     * change, for the account's other devices). If it couldn't be saved it isn't made, and
     * the person is told as for any setting that couldn't be saved.
     */
    fun choose(did: org.medianexus.music.sync.Chosen.Did) {
        // **Under the account signed in at this moment**: by the time its turn comes
        // another may have been taken on, and it is not that account's choice.
        chosen.trySend(Tapped(did, (session.state.value as? SessionState.SignedIn)?.account?.key.orEmpty()))
    }

    /** Waits until everything tapped so far has been carried out (an account is not taken
     *  on with the one before's taps still on their way). */
    private suspend fun choicesSettled() {
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        chosen.trySend(Tapped(null, "", reached))
        reached.await()
    }

    /** The shared choices in the settings are made `account`'s (see sync/Belongs.kt): what
     *  was chosen under another account is put away with it first. False if that couldn't
     *  be done: then they are still the other account's, and this one is not signed in. */
    private val takeOn: suspend (String) -> Boolean = { account ->
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            choicesSettled()
            org.medianexus.music.sync.Belongs.takeOn(settings, account, { key -> accountDirs(context.filesDir, context.cacheDir, key).data }) { diagnostics.note(it) }
        }
    }

    val offline = OfflineSwitch(settings.value.offline)

    val session = Session(
        settings = settings,
        vault = PasswordVault(passwordFile(context), passwordBox),
        http = http,
        offline = offline,
        dirsFor = { key -> accountDirs(context.filesDir, context.cacheDir, key) },
        notices = notices,
        scope = scope,
        io = Dispatchers.IO,
        takeOn = { key -> takeOn(key) },
    )

    /** The one writer of saved queues: it outlives the playback service and any account,
     *  so the last snapshot either hands it is still written. */
    val queueSaver = QueueSaver(scope, Dispatchers.IO)

    /** The phone's network, as Android reports it and as it changes (see [NetworkWatch]). */
    val network = org.medianexus.music.net.NetworkWatch(app)

    /** What songs are streamed as: the one place the streaming limit is read (see
     *  [org.medianexus.music.playback.StreamQuality]). */
    val quality = org.medianexus.music.playback.StreamQuality(
        settings.state, network.state,
        route = { network.routeNow()?.let { (handle, unmetered) -> org.medianexus.music.playback.StreamQuality.Route(handle, unmetered) } },
    )

    /** The unmetered network downloads are bound to while "Download on Wi-Fi only" is on
     *  (see [org.medianexus.music.net.UnmeteredNetwork]). */
    val unmetered = org.medianexus.music.net.UnmeteredNetwork(app, http) { diagnostics.note(it) }

    /** Media3's database: the kept songs' index and each account's list of downloads. */
    val database: androidx.media3.database.DatabaseProvider by lazy { databaseFor(app) }

    /**
     * Whether the server can be reached (see [ServerWatch]): noticed from every request the
     * account's client makes, asked again every 15 seconds while it can't be, and on its
     * return what was held back is sent and what was waiting carries on.
     */
    val watch: ServerWatch = ServerWatch(
        scope,
        ping = { accounts.state.value?.api?.ping() },
        // The first ask after start-up is given a few seconds, not the half minute a request
        // may take: on a link that swallows everything, the car must not wait that long to
        // be shown what is on the phone.
        firstPing = {
            kotlinx.coroutines.withTimeoutOrNull(FIRST_ASK_MILLIS) { accounts.state.value?.api?.ping(); Unit }
                ?: throw org.medianexus.music.net.ApiError.Network("It took too long to answer.")
        },
        cameOnline = { caughtUp(back = true) },
        signInGone = ::signInGone,
        caughtUp = { caughtUp(back = false) },
        // Not a server on the home network while the app isn't allowed to reach it: it can't
        // answer, so asking is for nothing. Allowed, asking resumes (see localAccess.allowed).
        mayAsk = { !localAccess.certain.value },
        wanted = ::wantsToKnow,
    )

    /** Set by the playback service: something is playing, or meant to be (Play is down). */
    @Volatile var playing = false
        set(value) {
            field = value
            // M6: music playing is a reason for the phone to be among the person's devices.
            jamsIfMade()?.let { jams -> mainHandler.post { jams.playingChanged(value) } }
        }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Whether anything wants to know, now, that the server is back: the app's screens in
     * front, something playing or meant to be, downloads waiting, or changes held for the
     * server. With none of those, an out-of-reach server is not asked after (see ServerWatch).
     */
    private fun wantsToKnow(): Boolean {
        if (org.medianexus.music.downloads.DownloadKeeper.visible || playing) return true
        val account = accounts.state.value ?: return false
        return account.held.waiting.value > 0 || account.downloads?.state?.value?.busy == true
    }

    /** Whether the app's screens are in front: the activity says, as it starts and stops.
     *  What only matters to someone looking (the look, every quarter of a minute, at what
     *  the person's other devices have changed) runs while this is true and not otherwise. */
    val front = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Whether the app's own screen is **the one in front**: resumed, not merely started (an
     * activity that is visible and paused, under a dialog of another app's or beside it in
     * a split screen, is not). A sign-in's code is traded for its token only then (see
     * `requests/Requests.kt`): the activity says, as it is resumed and paused.
     */
    val resumed = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * What owns each account's requests, **for the life of the process** (see
     * `requests/RequestsOwners`): made the first time an account with its requests switched
     * on is signed in to, or the toggle is turned on, in a build that has requests; never
     * otherwise. Its work is the app's own, not an account's.
     */
    private val requestsOwners: org.medianexus.music.requests.RequestsOwners by lazy {
        // With offline mode switched on by hand nothing is asked of Lidarr or the services
        // beside it either, and each call says so in the library's sentence ("Couldn't
        // reach Lidarr. Offline mode is on.").
        org.medianexus.music.core.CoreHttp.unsent = { if (watch.state.value.manual) context.getString(R.string.requests_offline) else null }
        val afterwards: (suspend () -> Unit) -> Unit = { work -> scope.launch { work() } }
        // (Beside the saved password, out of backups; a folder for each account.)
        val secretsOf: (org.medianexus.music.store.Account) -> org.medianexus.music.requests.RequestsSecrets = { account ->
            org.medianexus.music.requests.RequestsSecrets(java.io.File(context.noBackupFilesDir, "requests/${account.key}"), passwordBox, account.server, account.username)
        }
        org.medianexus.music.requests.RequestsOwners(
            scope,
            make = { account, sources, here, switched ->
                // (An account's own work, for as long as the process: a failure in it is
                // that account's requests', and nobody else's.)
                val work = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]))
                val requests = org.medianexus.music.requests.Requests(
                    account, work, secretsOf(account),
                    afterwards = afterwards,
                    comeBack = { org.medianexus.music.requests.BrowserTab.comeBack(context.applicationContext) },
                    awake = { org.medianexus.music.requests.SignInService.hold(context) },
                    // (The last attempt, and the last that failed, each with the network it was
                    // made on: a success afterwards does not wipe the failure.)
                    trace = { line, failed ->
                        // (Before the long last part, so that the cut at 300 characters can't take it.)
                        val network = "; network: ${transportNow(context)}"
                        val long = line.indexOf("; last request:")
                        val on = if (long < 0) line + network else line.substring(0, long) + network + line.substring(long)
                        diagnostics.replacing(org.medianexus.music.requests.Trace.PREFIX, on)
                        if (failed) diagnostics.replacing(org.medianexus.music.requests.Trace.FAILED_PREFIX, on.replaceFirst(org.medianexus.music.requests.Trace.PREFIX, org.medianexus.music.requests.Trace.FAILED_PREFIX))
                    },
                    local = org.medianexus.music.requests.AppLocalGate(localAccess) { transportNow(context).let { it == "Wi-Fi" || it == "Ethernet" } },
                    note = { diagnostics.note(it) },
                    // (A sign-in is finished only with the app's own screen in front: resumed.)
                    front = resumed,
                    switched = switched,
                )
                requests to org.medianexus.music.requests.RequestsPages.of(requests, work, account.dirs.data, sources, here, note = { diagnostics.note(it) }, afterwards = afterwards)
            },
            note = { diagnostics.note(it) },
            holds = { account -> secretsOf(account).exist() },
        )
    }

    /** What belongs to whoever is signed in: their library, covers, likes, downloads and
     *  queue. */
    val accounts = Accounts(session, scope) { signedIn ->
        // (The pins, each list's choices and the theme in the settings are this account's
        // by now: the session takes an account on before it says anyone is signed in, at a
        // typed sign-in and at a start from the saved password alike, and signs nobody in
        // if that can't be done. See [takeOn] and sync/Belongs.kt.)
        AccountGraph(
            signedIn.account, signedIn.api,
            wiring = AccountWiring(
                settings = settings.state,
                reach = watch.state,
                suspect = { watch.check() },
                downloads = { account -> downloadsFor(context.applicationContext, account) },
                note = { diagnostics.note(it) },
                network = network.state,
                inUse = { org.medianexus.music.downloads.DownloadKeeper.visible || playing },
                settingsStore = settings,
                front = front,
                requests = { requestsOwners },
                quality = quality,
            ),
        )
    }

    val toasts = Toasts()

    /** What the server last said in refusing something asked from the car (see [Refusals]). */
    val refusals = Refusals()

    /** The sleep timer: the app's, not a screen's, so it carries on with the screens closed
     *  (see SleepTimer); the playback service pauses the music when it says. */
    val sleep = org.medianexus.music.playback.SleepTimer(scope)

    /** Whether a server on the home network can be reached (Android 17's permission). */
    val localAccess = LocalAccess(context.applicationContext, session, settings, scope)

    private val reconnectCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Goes up whenever whatever failed for want of a connection is worth trying again: the
     * server answers again after it couldn't be reached, the phone has a network again, or
     * it has just been allowed to reach the home network. Pages that failed load again, and
     * covers that failed are asked for again, without anyone doing anything.
     */
    val reconnects: kotlinx.coroutines.flow.StateFlow<Int> = reconnectCount

    private fun reached(ok: Boolean) {
        localAccess.reached(ok)
        watch.reached(ok)
    }

    /**
     * While the server couldn't be reached last time it was asked, asks it again (a ping).
     * The watch does so every 15 seconds by itself; this is the screens coming to the front.
     */
    fun checkIfDown() = watch.nudge()

    /**
     * The server answers again after being out of reach (`back`), or offline mode has been
     * switched off and the server found there (see [ServerWatch]): what was held back is
     * sent, and said once when it has been; downloads that failed for want of it are tried
     * again; downloaded playlists are topped up; pages and covers that failed ask again.
     */
    private fun caughtUp(back: Boolean) {
        reconnected()
        // As Windows says it, when it was out of reach; not after offline mode is switched off
        // by hand, which says nothing (the switch was in the person's own hand).
        val said = back && !settings.value.offline
        if (said) toasts.show(app.getString(R.string.back_online))
        val account = accounts.state.value ?: return
        account.scope.launch {
            account.flush()
            val sent = account.acceptedToSay()
            // Said once. (A toast takes the place of one still showing, so after "Back online"
            // it says both.)
            if (sent > 0) toasts.show(app.resources.getQuantityString(if (said) R.plurals.back_online_held_sent else R.plurals.held_sent, sent, sent))
            account.downloads?.let { downloads ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    downloads.retryFailed(onlyNetwork = true)
                    downloads.topUp { list -> freshList(account, list) }
                }
            }
        }
    }

    /** The server refused the saved sign-in (the password was changed there): signed out,
     *  as Windows does, to be asked for it again. Nothing kept for the account is lost. */
    private fun signInGone() {
        notices.post(Notice.SignInGone)
        session.signOut()
    }

    private fun downloadsFor(context: Context, account: AccountGraph) = org.medianexus.music.downloads.Downloads(
        context = context,
        catalogue = account.catalogue,
        api = account.api,
        http = http,
        database = database,
        accountKey = account.account.key,
        settings = settings.state,
        reach = watch.state,
        covers = account.covers,
        keeper = { busy, wifiOnly -> org.medianexus.music.downloads.DownloadKeeper.update(context, busy, wifiOnly) },
        scope = account.scope,
        spare = { org.medianexus.music.downloads.DebugSpare.spare(context) ?: org.medianexus.music.downloads.Downloads.SPARE },
        note = { diagnostics.note(it) },
        network = network.state,
        unmeteredRoutes = unmetered,
    ).also { downloads -> downloads.reachedFailed = { watch.check() } }

    /** A list downloaded whole, as the server has it now, with its songs. */
    private suspend fun freshList(account: AccountGraph, list: org.medianexus.music.downloads.DownloadedList): Pair<org.medianexus.music.downloads.DownloadedList, List<org.medianexus.music.net.Song>>? {
        val library = account.library
        val page: Any = when {
            list.key == "liked" -> library.fresh(library.liked())
            list.key.startsWith("album:") -> library.fresh(library.album(list.key.removePrefix("album:")))
            list.key.startsWith("playlist:") -> library.fresh(library.playlist(list.key.removePrefix("playlist:")))
            else -> return null
        }
        return org.medianexus.music.downloads.DownloadedViews.listOf(list.key, page, list)
    }

    private fun batchEnded(context: Context, state: org.medianexus.music.downloads.DownloadsState) {
        val (done, failed) = state.batchDone to state.batchFailed
        val said = when {
            failed == 0 && done == 1 -> context.getString(R.string.download_finished)
            failed == 0 && done > 1 -> context.resources.getQuantityString(R.plurals.songs_downloaded, done, done)
            done == 0 && failed > 0 -> org.medianexus.music.downloads.DownloadPages.trouble(context, state.failed)
            failed > 0 -> context.resources.getQuantityString(R.plurals.some_downloaded, done, done, failed)
            else -> null
        }
        said?.let(toasts::show)
    }

    private fun reconnected() {
        accounts.state.value?.covers?.retryFailed()
        org.medianexus.music.ui.parts.CoverRetry.now()
        reconnectCount.value++
    }

    /** Another player the phone's player shows while it is in a jam or steering another of
     *  the person's devices (M6; see `jam/Jams`). */
    val steered = org.medianexus.music.playback.Steered()

    /** The person's other devices as the new linking knows them: what a list of them is
     *  drawn from and calls. Empty, and doing nothing, wherever there is no linking. */
    val linked = org.medianexus.music.playback.Linked()

    /** The screens' one way to the player (the playback service's session). */
    val player = PlayerConnection(context.applicationContext, accounts, settings, scope, context.getString(R.string.live_stream), context.getString(R.string.lost_connection), steered, linked, quality)

    /** The jam relay's host key, sealed as the password is (see `jam/KeyVault`). */
    val relayKey = org.medianexus.music.jam.KeyVault(java.io.File(context.noBackupFilesDir, "relay-key.sealed"), KeystoreBox())

    fun relayKeyKept(): Boolean = relayKey.kept()

    private val jamsNow = kotlinx.coroutines.flow.MutableStateFlow<org.medianexus.music.jam.Jams?>(null)

    /** [jams] once anything has made it; null until then (the strip and Connect look here,
     *  so that showing them doesn't make it). */
    val jamsMade: kotlinx.coroutines.flow.StateFlow<org.medianexus.music.jam.Jams?> = jamsNow

    /** Jams and the person's other devices: made the first time anything asks (see
     *  `jam/JamApp`), never with the feature unused. */
    val jams: org.medianexus.music.jam.Jams by lazy {
        // Never in a build without them: nothing that would ask is there (jam/JamBuild).
        check(org.medianexus.music.jam.JamBuild.on) { "Jams don't exist in this build" }
        org.medianexus.music.jam.JamApp(app, this, steered).also { it.start() }.jams.also { jamsNow.value = it }
    }

    /** A jam's link handed over by another app (see `jam/JamLinkActivity`), for the jam page
     *  to put in its box; taken by the shell. */
    val jamLinkArrived = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** [jams], if anything has made it. */
    fun jamsIfMade(): org.medianexus.music.jam.Jams? = jamsNow.value

    companion object {
        /** How long the server is given to answer the first time it is asked after start-up. */
        const val FIRST_ASK_MILLIS = 4_000L

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun databaseFor(context: Context): androidx.media3.database.DatabaseProvider = androidx.media3.database.StandaloneDatabaseProvider(context)

        /** Where the saved password is. Kept out of backups: the key that opens it can't
         *  leave the phone anyway. */
        fun passwordFile(context: Context): File = File(context.noBackupFilesDir, "password.sealed")
    }

    init {
        quietMedia3(BuildConfig.DEBUG)
        // A debug build logs what each download did (by song id; never an address).
        if (BuildConfig.DEBUG) org.medianexus.music.downloads.CrashPoints.log = { android.util.Log.d("NexusDownloads", it) }
        DebugHosts.load(context)
        // The shared core, loaded now, off the main thread, so that it is there by the time
        // anything asks (core/Core.kt). And the person's changes to what is shared, made in
        // turn (see [choose]).
        scope.launch(Dispatchers.IO) {
            try {
                org.medianexus.music.core.Core.warm()
            } catch (e: org.medianexus.music.core.CoreException) {
                diagnostics.note("the shared library couldn't be loaded: ${e.message}")
            }
            for (tapped in chosen) {
                tapped.did?.let { did -> settings.commit { org.medianexus.music.sync.Chosen.made(it, did, tapped.account, System.currentTimeMillis() / 1000) { said -> diagnostics.note(said) } } }
                tapped.reached?.complete(Unit)
            }
        }
        settings.damage?.let { notices.post(Notice.SettingsDamaged(it)) }
        // Said once when writing starts to fail, and taken back once a write succeeds.
        scope.launch {
            settings.saveFailed.collect { failed ->
                if (failed) notices.post(Notice.SettingsNotSaved) else notices.dismiss(Notice.SettingsNotSaved)
            }
        }
        // A like or unlike, a change to a playlist or a rating the server turned down has
        // already been put back; say so.
        scope.launch {
            accounts.state.collectLatest { account ->
                if (account == null) return@collectLatest
                kotlinx.coroutines.flow.merge(
                    account.likes.refused.map { context.getString(R.string.didnt_save, it.error.message.orEmpty()).trim() },
                    account.playlists.refused.map {
                        when {
                            it.outcome == org.medianexus.music.library.PlaylistRefused.Outcome.UNCONFIRMED -> context.getString(R.string.playlist_unconfirmed)
                            it.outcome == org.medianexus.music.library.PlaylistRefused.Outcome.UNSENT -> context.getString(R.string.playlist_unsent)
                            it.outcome == org.medianexus.music.library.PlaylistRefused.Outcome.CHANGED -> context.getString(R.string.playlist_changed_elsewhere)
                            it.outcome == org.medianexus.music.library.PlaylistRefused.Outcome.UNDO_CHANGED -> context.getString(R.string.playlist_undo_changed)
                            it.outcome == org.medianexus.music.library.PlaylistRefused.Outcome.GONE -> context.getString(R.string.playlist_gone)
                            it.outcome == org.medianexus.music.library.PlaylistRefused.Outcome.MISSING_SONGS -> context.getString(R.string.playlist_missing_songs)
                            else -> context.getString(R.string.didnt_save, it.error?.message.orEmpty()).trim()
                        }
                    },
                    account.ratings.refused.map { context.getString(R.string.couldnt_save_rating, it.error.message.orEmpty()).trim() },
                ).collect { toasts.show(it) }
            }
        }
        // Whether the server answers, from every request the account's client makes.
        scope.launch {
            var had = false
            accounts.state.collect { account ->
                // Signed out (not "not yet read", at start-up, when the job may be what
                // started the app): nothing of theirs is carried on, by job or service.
                if (account == null && had) org.medianexus.music.downloads.DownloadKeeper.letGo(app)
                // Signed out: the relay's key goes with the password (M6, rule 4).
                if (account == null && had && org.medianexus.music.jam.JamBuild.on) {
                    relayKey.delete()
                    jamsIfMade()?.let { jams -> mainHandler.post { jams.signedOut() } }
                }
                had = account != null
                // What was found out about one account's server is not the next account's.
                watch.reset()
                // (What one account's server said is not the next account's to be shown.)
                refusals.clear()
                account?.api?.onReached = ::reached
                // Is its server there? With no network, no: from this instant. Otherwise it
                // is asked at once (see ServerWatch.begin).
                if (account != null) watch.begin(networkThere = network.state.value.connected)
                // When a batch of downloads ends, it is said, as Windows says it.
                account?.downloads?.let { downloads ->
                    account.scope.launch {
                        var busy = false
                        downloads.state.collect { state ->
                            if (busy && !state.busy && state.settled && state.batchTotal > 0) batchEnded(context, state)
                            busy = state.busy
                        }
                    }
                }
                // Changes held from before that couldn't be read were set aside, not sent: said.
                if (account != null) account.scope.launch {
                    account.held.damaged.collect { if (it) toasts.show(context.getString(R.string.held_damaged)) }
                }
                // Downloaded lists topped up with what they gained while the app was away.
                if (account != null) account.scope.launch {
                    if (!watch.offline) account.downloads?.let { downloads ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { downloads.topUp { list -> freshList(account, list) } }
                    }
                }
            }
        }
        // Jams and linked devices, when hosting is switched on: made now, so that the phone
        // can be among the person's devices while it has a reason to be (M6). Otherwise
        // nothing of them is made until asked for.
        if (org.medianexus.music.jam.JamBuild.on && settings.value.jamHost) mainHandler.post { jams }
        // Allowed to reach the home network: what failed tries again.
        scope.launch { localAccess.allowed.collect { if (it > 0) { reconnected(); watch.networkBack() } } }
        // A network again (after none, or another one): the same.
        network.onBack = {
            reconnected()
            watch.networkBack()
        }
        // No network at all: the server is out of reach until it answers again.
        scope.launch { network.state.collect { if (!it.connected && accounts.state.value != null) watch.noNetwork() } }
        // The switch in Settings and the one every request checks are the same.
        scope.launch {
            settings.state.map { it.offline }.distinctUntilChanged().collect {
                offline.on = it
                watch.manual(it)
            }
        }
    }
}

/**
 * What Media3 may write to the system log. It logs a failure with its stack trace and every
 * cause down the chain (a download's final failure, a load that failed), and an exception
 * from the network's own code can name the server it was talking to. A release build has it
 * log the failure's own message and no more; a debug build, everything, for whoever is at
 * the other end of adb.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun quietMedia3(debug: Boolean) {
    androidx.media3.common.util.Log.setLogStackTraces(debug)
}

/** The kind of network the phone is on now, in a word, for the diagnostics line about
 *  requests: Wi-Fi, mobile data, Ethernet, a VPN over one of them, or none. Nothing more. */
internal fun transportNow(context: android.content.Context): String = try {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
    if (caps == null) {
        "none"
    } else {
        val kind = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) "VPN over $kind" else kind
    }
} catch (_: RuntimeException) {
    "unknown"
}
