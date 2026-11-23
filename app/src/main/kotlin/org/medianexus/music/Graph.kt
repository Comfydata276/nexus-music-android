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

    /** Read here, at start-up, because the first frame needs the theme. The file is small. */
    val settings: SettingsStore = SettingsStore.open(File(context.filesDir, "settings.json"), scope, Dispatchers.IO)

    val offline = OfflineSwitch(settings.value.offline)

    val session = Session(
        settings = settings,
        // Kept out of backups: the key that opens it can't leave the phone anyway.
        vault = PasswordVault(File(context.noBackupFilesDir, "password.sealed"), KeystoreBox()),
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
    val accounts = Accounts(session, scope, queueSaver)

    val toasts = Toasts()

    /** Whether a server on the home network can be reached (Android 17's permission). */
    val localAccess = LocalAccess(context.applicationContext, session, scope)

    /** The screens' one way to the player (the playback service's session). */
    val player = PlayerConnection(context.applicationContext, accounts, settings, scope, context.getString(R.string.live_stream))

    init {
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
        // The switch in Settings and the one every request checks are the same.
        scope.launch {
            settings.state.map { it.offline }.distinctUntilChanged().collect { offline.on = it }
        }
    }
}
