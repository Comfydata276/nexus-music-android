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
class Graph(context: Context) {
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

    val offline = OfflineSwitch(settings.value.offline)

    val session = Session(
        settings = settings,
        vault = PasswordVault(passwordFile(context), KeystoreBox()),
        http = http,
        offline = offline,
        dirsFor = { key -> accountDirs(context.filesDir, context.cacheDir, key) },
        notices = notices,
        scope = scope,
        io = Dispatchers.IO,
    )

    /** The one writer of saved queues: it outlives the playback service and any account,
     *  so the last snapshot either hands it is still written. */
    val queueSaver = QueueSaver(scope, Dispatchers.IO)

    /** What belongs to whoever is signed in: their library, covers, likes and queue. */
    val accounts = Accounts(session, scope)

    val toasts = Toasts()

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

    @Volatile private var serverDown = false

    private fun reached(ok: Boolean) {
        localAccess.reached(ok)
        val wasDown = serverDown
        serverDown = !ok
        if (ok && wasDown) reconnected()
    }

    /**
     * While the server couldn't be reached last time it was asked, asks it again (a ping),
     * so that pages showing "Can't reach the server" come back by themselves when it does,
     * even if nothing else asks meanwhile. Only while the screens are in front: see
     * MainActivity.
     */
    suspend fun checkIfDown() {
        if (!serverDown) return
        runCatching { accounts.state.value?.api?.ping() }
    }

    private fun reconnected() {
        accounts.state.value?.covers?.retryFailed()
        org.medianexus.music.ui.parts.CoverRetry.now()
        reconnectCount.value++
    }

    /** The screens' one way to the player (the playback service's session). */
    val player = PlayerConnection(context.applicationContext, accounts, settings, scope, context.getString(R.string.live_stream), context.getString(R.string.lost_connection))

    companion object {
        /** Where the saved password is. Kept out of backups: the key that opens it can't
         *  leave the phone anyway. */
        fun passwordFile(context: Context): File = File(context.noBackupFilesDir, "password.sealed")
    }

    init {
        DebugHosts.load(context)
        settings.damage?.let { notices.post(Notice.SettingsDamaged(it)) }
        // Said once when writing starts to fail, and taken back once a write succeeds.
        scope.launch {
            settings.saveFailed.collect { failed ->
                if (failed) notices.post(Notice.SettingsNotSaved) else notices.dismiss(Notice.SettingsNotSaved)
            }
        }
        // A like or unlike the server turned down has already been put back; say so.
        scope.launch {
            accounts.state.collectLatest { account ->
                account?.likes?.refused?.collect { toasts.show(context.getString(R.string.didnt_save, it.error.message.orEmpty()).trim()) }
            }
        }
        // Whether the server answers, from every request the account's client makes.
        scope.launch {
            accounts.state.collect { account ->
                serverDown = false
                account?.api?.onReached = ::reached
            }
        }
        // Allowed to reach the home network: what failed tries again.
        scope.launch { localAccess.allowed.collect { if (it > 0) reconnected() } }
        // A network again (after none, or another one): the same.
        runCatching {
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var had = true
                override fun onAvailable(network: android.net.Network) {
                    if (!had) reconnected()
                    had = true
                }

                override fun onLost(network: android.net.Network) {
                    had = false
                }
            })
        }
        // The switch in Settings and the one every request checks are the same.
        scope.launch {
            settings.state.map { it.offline }.distinctUntilChanged().collect { offline.on = it }
        }
    }
}
