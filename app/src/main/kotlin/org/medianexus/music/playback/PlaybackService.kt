package org.medianexus.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.AccountGraph
import org.medianexus.music.BuildConfig
import org.medianexus.music.Graph
import org.medianexus.music.MainActivity
import org.medianexus.music.R
import org.medianexus.music.SessionState
import org.medianexus.music.nexus.DiagnosticFault
import org.medianexus.music.nexus.DiagnosticNote
import org.medianexus.music.nexus.DiagnosticPlace
import org.medianexus.music.nexus.DiagnosticShape
import org.medianexus.music.nexus.PlayBegan
import org.medianexus.music.nexus.PlayOrigin
import org.medianexus.music.nexus.PlaySound
import org.medianexus.music.nexus.StopReason
import org.medianexus.music.graph
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Http
import org.medianexus.music.library.columns
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptSongs

/**
 * The playback service: one ExoPlayer in a Media3 `MediaLibraryService`. The app's screens,
 * the notification and lock screen, headset buttons and the car all control the same player
 * through its session; the car also browses the library through it.
 *
 * It must work with no screen: a car starts it cold. So everything it needs comes from the
 * app's [Graph], it waits for the saved sign-in to be read before deciding that nobody is
 * signed in, and it brings back the saved queue by itself.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var graph: Graph
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    /** Levelling, the equaliser and mono: one processor in the player's sink (see SoundProcessor). */
    private val sound by lazy { org.medianexus.music.sound.PlayerSound(scope) }
    private lateinit var session: MediaLibrarySession
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())

    /**
     * One account's turn at the player: everything that is theirs and must not outlive them.
     * Each sign-in is a new generation (`number`). The car's requests, voice lookups and
     * anything else begun for this account run in `scope` and are dropped when it goes;
     * search results are kept here, so they go too; and the player's reads carry the number
     * and are refused once it is no longer current (see [Leases]).
     */
    private inner class Era(val number: Long, val account: AccountGraph) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tree = CarTree(
            account.library, words,
            CarOffline(
                now = { graph.watch.offline },
                availability = account.availability,
                // The downloads as they stand; before their engine has said anything (the
                // first moments of a cold start), as the catalogue read them from the disk.
                downloads = {
                    account.downloads?.state?.value?.takeIf { it.settled || it.downloaded.isNotEmpty() }
                        ?: account.catalogue.state.value.let { org.medianexus.music.downloads.DownloadsState(downloaded = it.songs, collections = it.collections) }
                },
                unsure = { !graph.watch.state.value.sure },
                settle = { graph.watch.state.first { it.sure } },
                liked = { account.likes.isLiked(it) },
            ),
            mixes = { account.mixes.state.value },
        )
        val kept = KeptSongs(
            account.keptSongsDir, graph.database,
            limitBytes = { graph.settings.value.cacheLimitMb * 1024L * 1024L },
            // What is kept whole, told to the account's index as the cache opens and as songs
            // are cleared away for the limit (see KeptIndex).
            opened = { cache -> account.keptIndex.reconcile(org.medianexus.music.store.WholeMark.wholeKeys(cache)) { key -> Song(id = songIdOfKey(key)) } },
            removed = { key -> account.keptIndex.gone(key) },
        )
        val searches = SearchResults()
        /** What this account's conversions have been found to be, and the songs of its the
         *  phone turned out not to play as they are. By song id, which is only this server's
         *  to give: another account's server can use the same ids for other songs. */
        val conversions = Conversions()
        val needsConverting = NeedsConverting().also { found -> account.undecodable = { it in found } }
        /** Who made each search lately, by browser: told when one is to be made again. */
        val searchedBy = Searchers<MediaSession.ControllerInfo>()
        /** The account's saved queue, for this service's turn at it: nothing is written to
         *  it until what was read from it is in the player (see [restore]). */
        val queue = KeptQueue(account.queueFiles, graph.queueSaver)
        /** Done once the saved queue has been read and put back (see [restore]); **or the
         *  account has been let go** ([leave]), which finishes it as abandoned: whoever
         *  waited then finds this is no longer the service's account, and does nothing.
         *  (Never left unfinished: a wait on it would last for ever, and whatever turn the
         *  waiter held with it.) */
        val restoring = CompletableDeferred<Unit>()

        /** When the queue ends, music like its last song carries on (see [AutoplayRunner]):
         *  this account's, with this account's server, and dropped with it. */
        val autoplay = AutoplayRunner(
            enabled = { graph.settings.value.autoplay },
            source = object : AutoplaySource {
                override suspend fun similarSongs(id: String, count: Int) = account.library.similarSongs(id, count)
                override suspend fun randomSongs(count: Int, genre: String?) = account.library.randomSongs(count, genre)
            },
            scope = scope,
            offline = { graph.watch.offline },
            found = { seed, songs, resume ->
                if (era === this) {
                    val before = player.queue.lastUid()
                    player.carriedOn(seed, songs, resume)
                    if (before != player.queue.lastUid()) journalAutoplayFrom = before
                }
            },
            intent = { player.intent },
        ).also { player.end = it }

        /** The account's opaque key: what its covers' addresses carry. */
        /** The account's name in its covers' addresses (never its storage key). */
        val key: String get() = account.artwork
    }

    /** The account signed in, on the main thread. */
    private var era: Era? = null
    /** The same, for the player's loading threads. */
    @Volatile private var current: Era? = null
    private var eras = 0L
    private val account: AccountGraph? get() = era?.account
    /** The next few songs of the queue, fetched ahead into the kept songs (see [aheadNow]). */
    private lateinit var ahead: FetchAhead

    /** What the server is told about listening: "now playing", and each play that counts
     *  (which the log of plays notes too: see [counted]). */
    private val plays = PlayReporter(nowPlaying = ::reportNowPlaying, played = ::counted, started = ::recordStarted)
    private var failures = 0

    /** The songs passed over, with no server, since something last played (see
     *  [passOverToThePhone]). */
    private val passing = Recovery.PassingOver()

    /** The try at the server that has been put off (see [Recovery.Retry]); null when the
     *  player isn't waiting for the server. */
    private var waitingFor: Recovery.Retry? = null
    private var retry: Job? = null

    /** How long the server has been out of reach for the song that is stuck (see
     *  [Recovery.Outage]); and whether the player has since given up waiting (see [giveUp]). */
    private val outage = Recovery.Outage()
    private var gaveUp = false

    /** What has been asked for that is meant to end in playing, and is still on its way
     *  (see [PlayRequests]). */
    private val requests = PlayRequests<MediaSession.ControllerInfo>()

    /** When Play was last asked for, on [clock]. */
    private var askedToPlayAt = 0L

    /** The player is being emptied because the account is going: nothing to save. */
    private var leaving = false
    private var lastSaveAt = 0L

    private lateinit var words: CarWords

    /** What the notification's own buttons carry, and nothing sent from outside can (see
     *  [OwnMark]). Made with the service; never written down. */
    private val mark = OwnMark()
    private val callback = Callback()
    private val watcher = Watcher()

    override fun onCreate() {
        super.onCreate()
        graph = applicationContext.graph
        words = CarWords(
            home = getString(R.string.tab_home),
            playlists = getString(R.string.playlists),
            library = getString(R.string.tab_library),
            albums = getString(R.string.albums),
            artists = getString(R.string.artists),
            genres = getString(R.string.genres),
            radio = getString(R.string.radio),
            likedSongs = getString(R.string.liked_songs),
            recentlyPlayed = getString(R.string.recently_played),
            recentlyAdded = getString(R.string.recently_added),
            songs = getString(R.string.songs),
            others = getString(R.string.others),
            shuffle = getString(R.string.shuffle),
            mostPlayed = getString(R.string.most_played),
            aToZ = getString(R.string.sort_a_to_z),
            downloads = getString(R.string.downloads),
            songCount = { resources.getQuantityString(R.plurals.song_count, it, it) },
            albumCount = { resources.getQuantityString(R.plurals.album_count, it, it) },
            madeForYou = getString(R.string.made_for_you),
            mixTitle = { org.medianexus.music.ui.mixes.MixText.title(resources, it) },
            mixAbout = { org.medianexus.music.ui.mixes.MixText.about(resources, it) },
        )

        // One HTTP client for everything, so audio shares the warm connections; for audio,
        // with a shorter limit on a read that has stalled.
        val http = OkHttpDataSource.Factory(Http.forStreams(graph.http))
        val sources = SongDataSource.Factory(Leases(), http)
        ahead = FetchAhead(sources, ::formNow)
        val extractors = songExtractors()
        val mediaSources = DefaultMediaSourceFactory(sources, extractors).setLoadErrorHandlingPolicy(RideOut())
        exo = ExoPlayer.Builder(this, renderers(), mediaSources)
            // Handling audio focus is what makes it give way to a navigation prompt or a call.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val queue = Queue().apply {
            shuffle = graph.settings.value.shuffle
            repeat = graph.settings.value.repeat.coerceIn(Repeat.OFF, Repeat.ONE)
        }
        player = QueuePlayer(
            // (A song's cover is addressed to the account it is from: whoever is signed in
            // when its media item is made, which is whose queue it is.)
            exo, queue, { CarMedia.metadata(it, current?.key.orEmpty()) }, changed = { queueChanged() }, explain = ::explain,
            // Everyone but the app's own screens names a queue entry by its place.
            byPlace = { session.controllerForCurrentRequest?.let { callerOf(session, it) != Caller.OWN_INTERFACE } ?: false },
            // In the foreground before the player asks for the sound, whoever pressed Play.
            mayPlay = ::mayPlay,
            notice = ::notice,
            // What Media3 sends after a hand-over that came to nothing does nothing: noted
            // against whoever's commands are being carried out, until Media3 says they are
            // done (see PlayRequests).
            overtaken = { session.controllerForCurrentRequest?.let(requests::drop) },
            mayPrepare = { !requests.isDropped(session.controllerForCurrentRequest) },
        )
        player.observeTransport = ::observeTransport
        exo.addListener(watcher)
        exo.addListener(sleepWatch)
        // The sleep timer: the time is up (told on this, the player's thread, while that timer
        // is still the one set), or the song it was waiting for has ended (the player pauses
        // at the end of each song while it waits; a song that fails is passed over to the
        // next, paused: see passTo).
        graph.sleep.player = SleepTimer.Sleeper(::sleepNow)
        scope.launch { graph.sleep.state.collect { exo.pauseAtEndOfMediaItems = it == SleepTimer.State.EndOfSong } }
        // Debug builds log what the player does (track joins, underruns, errors): how
        // gapless joins and seeking are checked on the emulator, which can't record sound.
        if (BuildConfig.DEBUG) exo.addAnalyticsListener(EventLogger("NexusPlayer"))
        exo.addAnalyticsListener(heard)

        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(openApp())
            .setMediaButtonPreferences(buttons())
            // An error answering the car (nobody signed in) stays on its screen until put
            // right, rather than flashing up and going.
            .setLibraryErrorReplicationMode(MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_FATAL)
            .build()
        // Media3's notification, its like, shuffle and repeat buttons carrying this app's
        // mark: an intent shaped like one of them, without it, is not carried out (see
        // OwnButtons).
        setMediaNotificationProvider(
            OwnButtons(
                DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_bars) },
                mark, press = { press(it) }, note = { graph.diagnostics.note(it) },
            ),
        )
        // Media3's hook for a foreground start Android refuses (Android 12 and later): it
        // catches the exception and tells this, wherever in its own code the start was made.
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() = foregroundRefused()
        })

        // Who is signed in decides everything else.
        scope.launch {
            combine(graph.session.state, graph.accounts.state) { state, account -> state to account }
                .distinctUntilChanged()
                .collect { (state, account) -> signedIn(state, account) }
        }
        // The limit on kept songs applies the moment it is changed in Settings. (Changed
        // while nobody is signed in, it applies when someone next is: see signedIn.)
        scope.launch {
            graph.settings.state.map { it.cacheLimitMb }.distinctUntilChanged().drop(1).collect {
                val kept = era?.kept ?: return@collect
                // (Tried again if the folder is still held by the account's turn before
                // this one; a limit of nothing clears everything kept, now.)
                graph.scope.launch(Dispatchers.IO) { limitApplied(kept.enforceWhenFree()) }
            }
        }
        // Streaming quality changed in Settings: the song playing switches to it at once,
        // from where it is, as on Windows (`reload_current`). Only for a setting: a song
        // playing when the phone moves between Wi-Fi and mobile data plays on as it began.
        scope.launch { graph.quality.chosenAgain.collect { qualityChanged() } }
        // The person switched an app off in Settings (or forgot it): a connection it has
        // open is cut. Media3 has no way to throw a controller out, so it is left with
        // nothing: no command it may send, and nothing more it is told. (Every command is
        // also decided afresh as it arrives: see onPlayerCommandRequest.)
        scope.launch { graph.controlApps.apps.drop(1).collect { cutThoseNoLongerAllowed() } }
        // The sound settings reach the sound processor as one value, heard at once.
        scope.launch { graph.settings.state.collect { sound.update(it, player.queue.shuffle) } }
        main.postDelayed(ticker, TICK_MILLIS)
        // The server found to be out of reach, with a song chosen that isn't on the phone:
        // passed over then, not after the stream's own tries have run their course.
        scope.launch { graph.watch.state.map { it.unreachable && it.sure && !it.manual }.distinctUntilChanged().collect { if (it) passOverOutOfReach() } }
        // Fetching ahead stops the moment offline mode goes on, or the server is gone.
        scope.launch { graph.watch.state.map { it.offline }.distinctUntilChanged().collect { aheadNow() } }
        scope.launch { graph.settings.state.map { it.cacheAhead to it.cacheLimitMb }.distinctUntilChanged().collect { aheadNow() } }
        // Offline, or back: the car's tabs show something else now (only what plays).
        scope.launch {
            graph.watch.state.map { it.offline }.distinctUntilChanged().drop(1).collect { if (era != null) reload() }
        }
        // Offline mode switched on by the person takes its place in the order of choices:
        // whatever was waiting to start music (a lookup, a start, a rejoin) is overtaken by it,
        // whether or not the server had gone already. The app finding that the server has
        // gone, or is back, is nobody's choice and takes none: a Play on its way then goes
        // ahead, and plays if what it asked for is on the phone (it used to be dropped, and
        // a song chosen in the car just as the phone noticed sat paused).
        scope.launch {
            graph.watch.state.map { it.manual }.distinctUntilChanged().drop(1).collect { manual -> if (manual) Choices.take() }
        }
        // And when it becomes known whether the server is there, after the moment of not
        // knowing at a cold start: what the car was shown meanwhile came from the phone alone
        // (and without covers that aren't on it), so it is told to look again.
        scope.launch { graph.watch.state.map { it.sure }.distinctUntilChanged().drop(1).collect { sure -> if (sure && era != null) reload() } }
        listenForCalls()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    // ---- What started the player, and whether Android let it into the foreground ------------
    //
    // Noted for Settings' Diagnostics page (see org.medianexus.music.Diagnostics): on a real
    // phone, started cold by a car or a headset, this is all there is to go on afterwards.

    /** When this service was made, and what first asked for it. */
    private val madeAt = android.os.SystemClock.elapsedRealtime()
    private var startedBy: String? = null
    private var foregroundNoted = false

    private fun noteStartedBy(what: String) {
        if (startedBy != null) return
        startedBy = what
        val cold = madeAt - android.os.Process.getStartElapsedRealtime() < COLD_START_MILLIS
        graph.recording.diagnostics.note(DiagnosticNote.PLAYER_STARTED)
        graph.diagnostics.note("player started by $what, with the app ${if (cold) "not running" else "already running"}", now = true)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        noteStartedBy(
            when (intent?.action) {
                SERVICE_INTERFACE, MediaSessionService.SERVICE_INTERFACE -> "a Media3 controller connecting"
                "android.media.browse.MediaBrowserService" -> "a media browser connecting (a car, or the system)"
                else -> "something binding to it"
            },
        )
        return super.onBind(intent)
    }

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        // While the foreground is held for a start (see holdForeground), Media3 is
        // not asked to update a notification it would take away: with nothing playing yet it
        // removes the notification and leaves the foreground, which is the one thing that
        // must not happen before the player has asked for the sound.
        if (holding && !startInForegroundRequired) return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        val updated = super.onUpdateNotificationAsync(session, startInForegroundRequired)
        if (startInForegroundRequired) {
            updated.addListener({
                val failure = runCatching { updated.get() }.exceptionOrNull()?.let { it.cause ?: it }
                if (failure != null) {
                    // (Media3 has caught it; if it is Android refusing the foreground, its
                    // listener is told too: see onCreate.)
                    foregroundFault()
                    graph.diagnostics.note("foreground: Android refused it (${failure.javaClass.simpleName})", now = true)
                } else if (inForeground() || !holding) {
                    // Media3's notification holds the foreground from here.
                    if (holding) heldLongEnough()
                    if (!foregroundNoted) {
                        foregroundNoted = true
                        graph.recording.diagnostics.foreground(clock() - madeAt)
                        graph.diagnostics.note("foreground: playing in it ${android.os.SystemClock.elapsedRealtime() - madeAt} ms after the player was made")
                    }
                } else {
                    // Asked to hold the foreground, Media3 took its notification away instead
                    // (it does when it thinks there is nothing to show), and the foreground
                    // this service was holding for the start went with it. By Android's word,
                    // not Media3's: the service is not in the foreground. Entered again, if
                    // the player still means to play.
                    foregroundFault()
                    graph.diagnostics.note("foreground: Media3 did not take it over when playback began", now = true)
                    holding = false
                    if (busy()) holdForeground(why = "again, Media3 having let it go")
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(this))
        }
        return updated
    }

    /**
     * The app was swiped away from the recent apps. Media3 stops the service unless a song
     * is sounding at that very moment, which ends the music for good if it was only
     * buffering, or had given way to a navigation prompt. Only a pause by the person ends it
     * here (see [Recovery.carriesOnWhenSwipedAway]).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Recovery.carriesOnWhenSwipedAway(exo.playWhenReady, exo.playbackState, waitingFor != null)) return
        pauseAndStop()
    }

    /**
     * Pauses and stops the service, as Media3's `pauseAllPlayersAndStopSelf` does, without
     * the other thing that does: switch off, for the rest of the service's life, the ten
     * minutes Media3 keeps the service in the foreground after playback stops (its
     * notification manager's "user engaged" timeout, which nothing can switch on again).
     * While a car stays bound the service lives on, and whatever it played next would then
     * leave the foreground the moment the player went idle (an ordinary Wi-Fi dropout), with
     * no standing to come back into it when the server answered again.
     */
    private fun pauseAndStop() {
        for (each in sessions) each.player.playWhenReady = false
        stopSelf()
    }

    // ---- Started by a button, with the app not running --------------------------------------

    /**
     * The service is holding the foreground with a notification of its own, for a Play that
     * has been asked for and has not begun yet, until playback begins and Media3's takes its
     * place. See [holdForeground].
     */
    private var holding = false

    /** Whether the one deadline for a start has been set. */
    private var deadlineSet = false
    private var holdNoted = false

    /** The deadline has passed with something still being looked up: looked at again when
     *  that request ends (see [requestEnded]). */
    private var overdue = false
    private var handOverNoted = false

    /**
     * The time allowed for a start has run out, and the foreground is still held by this
     * service's own notification: Media3's has not taken its place. What that means depends
     * on what is going on (see [Recovery.whenHoldRunsOut]); that the player looks busy is no
     * longer taken to mean Media3 has the foreground.
     */
    private val deadline = Runnable {
        deadlineSet = false
        overdue = false
        if (!holding) return@Runnable
        when (Recovery.whenHoldRunsOut(requests.waiting, busy())) {
            Recovery.Held.WAIT -> overdue = true
            Recovery.Held.HAND_OVER -> {
                if (!handOverNoted) {
                    handOverNoted = true
                    graph.diagnostics.note("foreground: still held by the player's own notification ${HOLD_MILLIS / 1000} s after it was entered; Media3 asked to take it over", now = true)
                }
                // Media3 takes it when it next brings its notification up to date with a
                // player that is playing: asked to now, and looked at again later.
                triggerNotificationUpdate()
                deadlineSet = true
                main.postDelayed(deadlineAgain, HOLD_MILLIS)
            }
            Recovery.Held.STAND_DOWN -> standDown()
        }
    }
    private val deadlineAgain: Runnable = Runnable { deadline.run() }

    /** Whether the player means to play and is doing something about it: playing, getting
     *  ready, or waiting for the server to come back. */
    private fun busy(): Boolean =
        exo.playWhenReady && (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING || waitingFor != null)

    /** A request has been handed over, or has failed. If the time allowed for the start ran
     *  out while it was being looked up, what to do about the foreground is decided now,
     *  once Media3 has done with what was handed over. */
    private fun requestEnded(request: PlayRequests.Request<MediaSession.ControllerInfo>) {
        requests.end(request)
        if (overdue && requests.waiting == 0) main.post(deadline)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // In a fixed set of words: the action is whatever the sender wrote, and the service
        // is open to every app.
        noteStartedBy(
            when (intent?.action) {
                Intent.ACTION_MEDIA_BUTTON -> "a media button" + (keyOf(intent)?.let { " ($it)" } ?: "")
                null -> "a start request"
                MEDIA3_BUTTON_ACTION -> "a start request (a notification button)"
                else -> "a start request (other)"
            },
        )
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) holdForeground(why = "for a media button")
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Enters the foreground now, if the service isn't in it already, and says whether the
     * service is in it. Done at three moments.
     *
     * The first thing for a Play button that started the service, before anything is read or
     * looked up: the promise to the system is kept at once, however long the saved sign-in
     * and the saved queue take to read. The order used to be the other way round: up to six
     * seconds of reading first, and the foreground only once the player was buffering.
     *
     * The first thing for anything else that is to end in playing, as it arrives (a song
     * chosen in the car, a spoken request, Play with nothing loaded: see the session's
     * callback): Android gives a command from a car a few seconds in which the service may
     * enter the foreground, and what such a request has to look up first can take longer.
     *
     * And just before the player is asked to play, whoever asked (a car, a headset, the
     * notification, the app): Media3 enters the foreground a moment after playback has begun,
     * which is after the player has asked Android for the sound. Android 15 gives audio
     * focus to an app in the background only if it is running a foreground service, and
     * Android 17 lets it be heard only then: so a service that had been paused long enough
     * to have left the foreground has to be back in it first. If Android won't have it, the
     * player is not asked to play (see [mayPlay]).
     *
     * The notification is Media3's own in all but content (the same number and channel), so
     * that when playback begins Media3's replaces it in place. On Android 12 and later the
     * system holds a foreground notification back for ten seconds, so it is never seen.
     *
     * How long the foreground is held with nothing playing is bounded once, from the first
     * request: later ones don't push the limit back. When it runs out, see [deadline].
     */
    private fun holdForeground(why: String): Boolean {
        if (inForeground()) return true
        if (!holding) {
            try {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
                if (manager.getNotificationChannel(channel) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(channel, getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID), NotificationManager.IMPORTANCE_LOW),
                    )
                }
                val notification = NotificationCompat.Builder(this, channel)
                    .setSmallIcon(R.drawable.ic_bars)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentIntent(openApp())
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                    .build()
                foreground.enter(notification)
                graph.recording.diagnostics.note(DiagnosticNote.FOREGROUND_ENTERED)
                holding = true
                // (Said once for each life of the service, with how soon; then only counted.)
                if (!holdNoted) {
                    holdNoted = true
                    graph.recording.diagnostics.foreground(clock() - madeAt)
                    graph.diagnostics.note("foreground: entered at once $why, ${android.os.SystemClock.elapsedRealtime() - madeAt} ms after the player was made", now = true)
                } else {
                    graph.diagnostics.note("foreground: entered at once $why")
                }
            } catch (e: RuntimeException) {
                // Android would not have it (not allowed from the background, on Android 12
                // and later; or a rule this was not written for). The caller is told: a Play
                // is then not carried out (see mayPlay); a request that has only arrived goes
                // on being looked up, and is asked again when its Play comes.
                foregroundFault()
                graph.diagnostics.note("foreground: Android refused it $why (${e.javaClass.simpleName})", now = true)
                return false
            }
        }
        if (!deadlineSet) {
            deadlineSet = true
            main.postDelayed(deadline, HOLD_MILLIS)
        }
        return true
    }

    /**
     * Asked by the player just before it is asked to play, whoever asked (see
     * `QueuePlayer.mayPlay`). In the foreground first; and if Android will not have the
     * service there, the player is not asked to play at all. It used to be asked all the
     * same: on Android 15 and 16 it is then refused the sound and sits paused with nothing
     * said, and on Android 17 it runs on in silence.
     */
    private fun mayPlay(): Boolean {
        // The Play that Media3 sends after a request that was overtaken, or failed: not a
        // Play anyone pressed. Whatever is in the player now stays as it is.
        if (requests.isDropped(session.controllerForCurrentRequest)) return false
        if (!holdForeground(why = "to play")) {
            cannotStart("Android would not let the player into the foreground")
            return false
        }
        // Started: whatever was said about not being able to is taken back.
        cantStart = false
        return true
    }

    /** Tests only: what a Play waiting for the saved queue does once it has waited (a gate). */
    @androidx.annotation.VisibleForTesting
    internal var resumptionWaited: suspend () -> Unit = {}

    /** Playing could not be started from where it was asked for: said on the session, in
     *  the player's error line, until the next start that works (see [cannotStart]). */
    private var cantStart = false

    private fun notice(): PlaybackException? =
        if (cantStart) PlaybackException(getString(R.string.car_open_to_play), null, PlaybackException.ERROR_CODE_INVALID_STATE, openAppButton()) else null

    /**
     * Android will not let playing start from here: not into the foreground, or, straight
     * after Play, not the sound. Play is not called (or has been turned down by the player
     * itself). The queue stays as it was chosen, stopped at its place, and the session says
     * what will put it right, where a car shows a player's error: short, in words, with a
     * button that opens the app, and with Play still offered, since the next Play may well be
     * allowed (Android's allowance belongs to each command). It goes when a start works.
     */
    private fun cannotStart(why: String) {
        graph.diagnostics.note("playing could not be started: $why. Left paused, and the session says to open the app", now = true)
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, "playing could not be started: $why")
        cantStart = true
        player.refuseStart()
    }

    /**
     * Whether the service is in the foreground at this moment, by Android's own word.
     * (Media3's `isPlaybackOngoing` is not that: in 1.11.1 it says whether the service has
     * ever been started in the foreground, and stays true after Media3 has left it.)
     */
    private fun inForeground(): Boolean = try {
        foreground.isIn()
    } catch (_: RuntimeException) {
        false
    }

    /** Android's foreground, as the service uses it: behind this so that a test can stand in
     *  for Android, which is the one thing here that can say no. */
    internal interface Foreground {
        fun isIn(): Boolean

        /** Enters it with this notification. Throws what Android throws when it won't have it. */
        fun enter(notification: android.app.Notification)

        /** Leaves it, taking the notification away. */
        fun leave()
    }

    internal var foreground: Foreground = object : Foreground {
        override fun isIn(): Boolean = foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE

        override fun enter(notification: android.app.Notification) {
            ServiceCompat.startForeground(this@PlaybackService, DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }

        override fun leave() {
            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /** The service's own parts, for the tests that run it whole (`StartingTest`). */
    internal class Inside(val player: QueuePlayer, val exo: ExoPlayer, val session: MediaLibrarySession, val callback: MediaLibrarySession.Callback, val watcher: Player.Listener)

    @androidx.annotation.VisibleForTesting
    internal fun inside() = Inside(player, exo, session, callback, watcher)

    /** For a test: waited for before an account's saved queue is read and put back, so that
     *  what happens while that is under way can be looked at. */
    @androidx.annotation.VisibleForTesting
    @Volatile internal var holdingRestore: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Playback has begun and Media3's notification holds the foreground now (or the start
     *  came to nothing). The next start has a limit of its own. */
    private fun heldLongEnough() {
        holding = false
        deadlineSet = false
        overdue = false
        main.removeCallbacks(deadline)
        main.removeCallbacks(deadlineAgain)
    }

    /** The button a media button intent carries: one of the keys a player answers to, by
     *  name, or "another key". */
    private fun keyOf(intent: Intent): String? = runCatching {
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT))?.keyCode?.let { MEDIA_KEYS[it] ?: "another key" }
    }.getOrNull()

    /**
     * Ends a start that has nothing to play (nobody signed in after all, no saved queue that
     * holds together, or nothing begun within the limit): leaves the foreground it was
     * holding, takes its notification away (on Android 12 and later it was never shown), and
     * stops. Does nothing if playback did start.
     */
    private fun standDown() {
        // Playing, or about to: what is held is still needed, and is not forgotten (the
        // deadline hands it to Media3, or keeps it).
        if (busy()) return
        val was = holding
        heldLongEnough()
        runCatching { foreground.leave() }
        if (was) graph.diagnostics.note("asked to play with nothing to play: left the foreground and stopped cleanly", now = true)
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "asked to play with nothing to play: stopped cleanly")
        pauseAndStop()
    }

    /**
     * Android refused to let the service into the foreground when Media3 asked for it (it
     * tells its listener: see onCreate), at the start or at any time after: the player
     * buffering again after a dropout, with the ten minutes after a stop run out. It is
     * handled, never thrown, and it is what a refusal at the start is (see [cannotStart]):
     * the player is stopped at its place, so that nothing claims to be playing from a service
     * Android may silence or stop at any moment, and the session says what will put it right,
     * with Play still offered. It used to be only paused, with nothing said.
     */
    private fun foregroundRefused() {
        graph.diagnostics.note("foreground: Android would not allow it when Media3 asked; stopped where it was", now = true)
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Android refused the foreground")
        cannotStart("Android would not let the player into the foreground")
        if (holding) standDown()
    }

    override fun onDestroy() {
        if (::graph.isInitialized) {
            graph.playing = false
            graph.recording.diagnostics.playing(false)
        }
        if (::graph.isInitialized) graph.sleep.player = null
        main.removeCallbacksAndMessages(null)
        if (::ahead.isInitialized) ahead.release()
        // Handed to the app's writer, which finishes it whatever becomes of this service.
        save()
        scope.cancel()
        session.release()
        player.release()
        exo.release()
        leave()
        super.onDestroy()
    }

    /** The sleep timer's time is up: the music pauses, where it is, with the queue as it was. */
    private fun sleepNow() {
        // Its place in the order of choices all the same: a start still being looked up
        // gives way to it, as to a Pause. (Playing, the pause takes it.)
        if (!exo.playWhenReady) {
            Choices.take()
            return
        }
        player.pause()
        graph.toasts.show(getString(R.string.sleep_paused))
    }

    /** A song that failed is passed over to the queue's `place`. With "End of this song" set,
     *  that was the song's end: the next is lined up, paused (as on Windows, the timer is
     *  for the song playing however it ends). */
    private fun passTo(place: Int) {
        listening.stop(entryOf(exo.currentMediaItem), exo.currentPosition, StopReason.ERROR,
            journalFault ?: DiagnosticFault(DiagnosticPlace.MUSIC_SERVER, DiagnosticShape.UNAVAILABLE))
        player.passTo(place)
        if (graph.sleep.songEnded()) {
            player.pause()
            graph.toasts.show(getString(R.string.sleep_paused))
        }
    }

    /** "End of this song": the player paused at its end by itself (it was asked to), which is
     *  the sleep timer done. */
    private val sleepWatch = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && graph.sleep.songEnded()) {
                graph.toasts.show(getString(R.string.sleep_paused))
            }
        }
    }

    /**
     * The renderers: Media3's, with the MP3 trim put right and the sound processor in the
     * sink. (Crossfade, which needs two songs sounding at once, is not here.)
     */
    private fun renderers() = object : DefaultRenderersFactory(this) {
        override fun buildAudioRenderers(
            context: android.content.Context,
            extensionRendererMode: Int,
            mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: androidx.media3.exoplayer.audio.AudioSink,
            eventHandler: Handler,
            eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
            out: ArrayList<androidx.media3.exoplayer.Renderer>,
        ) {
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
            // Media3's renderer, with the trim for MP3 put right (see Mp3Trim).
            val at = out.indexOfFirst { it is androidx.media3.exoplayer.audio.MediaCodecAudioRenderer }
            if (at >= 0) out[at] = GaplessAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) { graph.diagnostics.note(it) }
        }

        // Media3's sink with the sound processor in it, and after it, in debug builds only, a
        // tap that can write what goes to the speaker to a file (see DebugAudio); none in a
        // release build.
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
            sound.sink(context, enableFloatOutput, enableAudioTrackPlaybackParams, DebugAudio.processors(context))
    }

    // ---- Who is signed in ---------------------------------------------------------------------

    private fun signedIn(state: SessionState, now: AccountGraph?) {
        if (state == SessionState.Restoring) return
        if (now == null && state is SessionState.SignedIn) return // its objects are on their way
        // Nothing has changed, once the session has been told the first time: a cold start
        // with nobody signed in is "no change" from the start, and must still be said.
        if (now === era?.account && told) return
        told = true
        // Levelling values the phone found for one account's songs are not another's.
        sound.account(now)
        // (That playing could not be started was said of the queue that was there.)
        cantStart = false
        // Someone else, or nobody: what was playing was theirs.
        if (era != null) {
            save()
            leave()
            // Emptying the player isn't a change to the queue that was theirs: it stays saved
            // for when they sign in again.
            leaving = true
            player.clear()
            leaving = false
            plays.reset()
        }
        if (now == null) {
            // A standing error in words, there before anything is asked: a car that only
            // shows the player, and never opens a folder, still says why nothing plays.
            session.setPlaybackException(signInNeeded())
            reload()
            return
        }
        session.setPlaybackException(null)
        // And the same error as a folder gave it to a car: Media3 keeps that one standing
        // until a folder next opens, which a car showing only the player never asks for.
        session.clearReplicatedLibraryError()
        val next = Era(++eras, now)
        era = next
        current = next
        reload()
        // The like button follows the likes, wherever they're changed.
        next.scope.launch { now.likes.liked.collect { updateButtons() } }
        // The list the queue came from changed (a fresh copy from the server, a song unliked
        // from Liked songs): the queue follows it. And a list the car has open, perhaps as
        // numbered ranges: every one of those folders is told to load again (see
        // CarTree.stale).
        next.scope.launch {
            var following: kotlinx.coroutines.Job? = null
            now.pages.changes.collect { (key, value) ->
                if (key == player.queue.source) songsIn(value)?.let { songs ->
                    // Nothing to do when the list's songs are the ones the queue last saw (most
                    // refreshes of a page): no dates, no sort. Otherwise the list's order (its
                    // dates added, a sort of thousands) is worked out off the main thread, and
                    // only the queue's own change made on it; a newer copy of the list
                    // overtakes one still being worked out.
                    if (sameSongs(player.queue.sourceList, songs)) return@let
                    val filter = player.queue.filter
                    following?.cancel()
                    following = next.scope.launch {
                        val order = withContext(Dispatchers.Default) { orderOf(key, songs, filter) }
                        // (Offline, what doesn't play doesn't join, as on Windows.)
                        // (The list changed, perhaps on another device: not a change to the
                        // queue made here.)
                        if (player.queue.source == key) quietly { player.follow(key, songs, order, playsOffline() ?: { true }) }
                    }
                }
                for (id in next.tree.stale(asked.toList(), key)) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
            }
        }
        next.scope.launch { restore(next) }
        // The person's other devices: this is the player whose queue is shared with them.
        queueStamp = 0
        queueTaken = null
        queueMark = null
        now.queueStep?.here = queueHere(next)
        // The library copy arriving or changing: songs held that came without levelling
        // values may find them there.
        next.scope.launch { now.copy.state.collect { copy -> if (era === next) sound.songsHeld(songsHeld(), copy) } }
        // The "Made for you" mixes were worked out again (a play, a like, a newer copy of the
        // library, the day): Home, and any mix the car has open, are told to look again.
        // Only what changed (they are worked out again at every song that starts), by what
        // the folders hold, not where it sits in the library: the songs' ids, and offline
        // whether each plays, so going offline or a download arriving tells them too.
        next.scope.launch {
            var shown: List<CarEntry>? = null
            val songs = HashMap<String, List<String>>()
            val downloaded = now.downloads?.state?.map { it.downloaded.keys }?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(emptySet())
            kotlinx.coroutines.flow.combine(now.mixes.state, graph.watch.state.map { it.offline }.distinctUntilChanged(), downloaded) { _, _, _ -> }.collect {
                val home = next.tree.madeForYouShown()
                val ids = buildList {
                    if (shown != null && home != shown) add(CarIds.format(CarNode.Home))
                    for (id in asked) (CarIds.parse(id) as? CarNode.Mix)?.let { mix ->
                        val holds = next.tree.mixShown(mix.id)
                        if (songs.put(mix.id, holds).let { it != null && it != holds }) add(id)
                    }
                }
                shown = home
                for (id in ids.distinct()) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
            }
        }
        // What is downloaded changed: the car's Downloads tab, and anything of it open, is
        // told to look again.
        now.downloads?.let { downloads ->
            next.scope.launch {
                downloads.state.map { it.downloaded.keys to it.collections }.distinctUntilChanged().drop(1).collect {
                    val ids = listOf(CarIds.format(CarNode.Downloads)) + asked.filter { id ->
                        when (CarIds.parse(id)) { CarNode.Downloads, CarNode.DownloadedSongs, is CarNode.DownloadedAlbum, is CarNode.DownloadedList -> true; else -> false }
                    }
                    for (id in ids.distinct()) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
                }
            }
        }
        // The limit on kept songs may have been lowered, or turned off, while this account
        // was signed out (or the app not running): what is over it goes now. With a limit of
        // nothing the kept songs are never opened to play from, so nothing else would ever
        // clear them. A look at the folder's size, off the main thread; nothing is opened
        // unless there is something to remove.
        graph.scope.launch(Dispatchers.IO) { limitApplied(next.kept.enforceWhenFree(onlyIfOver = true)) }
    }

    /** Noted when the limit on kept songs could not be applied because their folder never
     *  came free (not when the account simply went). */
    private fun limitApplied(done: Boolean) {
        if (!done && era != null) graph.diagnostics.note("kept songs: the limit could not be applied yet (their folder was still in use)")
    }

    /**
     * Back to where things were, paused, ready for "play" from anywhere. Until the saved
     * queue is in the player nothing is saved (see [KeptQueue]): the player's queue is empty
     * while the saved one is being read and then made ready, and an account that goes, or a
     * service destroyed, in that time must not have "empty" written over its queue. (This
     * runs in the account's scope: if the account goes meanwhile it stops where it is, and
     * the queue is never marked as put back.)
     */
    private suspend fun restore(era: Era) {
        holdingRestore?.await()
        val saved = era.queue.read()
        if (saved != null) {
            // Made ready off the main thread: it can be thousands of songs.
            val revived = withContext(Dispatchers.Default) { player.revive(saved) }
            // Putting it back isn't a change to pass on: it keeps the time it last changed.
            if (player.queue.isEmpty) {
                queueStamp = saved.stamp
                // (A queue marked as handed to another device by the device linking that
                // earlier builds had is this phone's own: nothing hands a queue on now.)
                queueTaken = saved.taken.takeUnless { it == HANDED_BY_LINKING }
            }
            quietly { player.restore(revived, (saved.position * 1000).toLong()) }
        }
        // Only now: the saved queue is the player's, or there was none.
        era.queue.settle()
        era.restoring.complete(Unit)
        // The screens are told the queue is as it will be: until now, "nothing playing" only
        // meant "not read yet".
        publishExtras()
        // Something started while the saved queue was being read is the queue now.
        if (!player.queue.isEmpty) save()
    }

    /**
     * Has the car look again at every folder it has asked for lately, and the tabs: after a
     * sign-in they have something in them, after a sign-out they don't, and a car sitting on
     * "sign in" (or on someone else's albums) would otherwise sit there until its screen was
     * closed and opened again.
     */
    private fun reload() {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "who is signed in changed: the car is asked to look again at ${asked.size + 1} folders")
        for (id in asked + CarIds.ROOT) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
    }

    /** The folders the car has asked for lately, most recent last. */
    private val asked = LinkedHashSet<String>()

    private fun askedFor(parentId: String) {
        asked.remove(parentId)
        asked.add(parentId)
        if (asked.size > MOST_ASKED) asked.remove(asked.first())
    }

    /** Whether the session has been told, at least once, who is signed in (or that nobody is). */
    private var told = false

    /**
     * The account's turn is over: its generation is no longer current (so a read that opens
     * from now on is refused), whatever was running for it is dropped, and its kept songs
     * close as soon as the reads still using them have.
     */
    private fun leave() {
        val old = era ?: return
        listening.retire(null, entryOf(exo.currentMediaItem), exo.currentPosition)
        journalCarEntry = null
        journalAutoplayFrom = null
        journalInAutoplay = false
        old.account.queueStep?.here = null
        stopWaiting()
        outage.clear()
        current = null
        era = null
        reading.clear()
        triedAgain.clear()
        passing.end()
        ahead.want(emptyList())
        old.scope.cancel()
        // Whatever was waiting for this account's saved queue to be put back is let go: it
        // is not going to be. (Each such wait then looks, and finds the account gone.)
        old.restoring.complete(Unit)
        // Off the main thread: closing writes the cache's index.
        graph.scope.launch(Dispatchers.IO) { old.kept.close() }
    }

    /** Gives the player's reads what they need from the account they were made for. */
    private inner class Leases : SongLeases {
        override fun generation(): Long = current?.number ?: 0

        override fun lease(generation: Long): SongLease {
            val era = current
            if (era == null || era.number != generation) throw IOException("The account this song was for has signed out")
            val cache = if (graph.settings.value.cacheLimitMb > 0) era.kept.acquire() else null
            if (BuildConfig.DEBUG && cache == null) android.util.Log.d(TAG, "a read without the kept songs (limit ${graph.settings.value.cacheLimitMb} MB)")
            return object : SongLease {
                override val cache = cache
                // The same switch the API client obeys: offline mode stops the player
                // fetching too, not only the pages. As it stands at each read: turned on
                // while a stream is open, it stops that stream as well.
                override val offline: Boolean get() = graph.offline.on
                override val conversions = era.conversions
                override fun resolve(songId: String): SongWhere = resolve(era, songId, cache != null)
                override fun resolve(songId: String, entry: Long?): SongWhere = resolve(era, songId, cache != null, entry != null && entry in movedEntries)
                override fun opened(songId: String, entry: Long?, copy: SongCopy) {
                    // (From a loading thread.) Which copy each queue entry is being read
                    // from: for the format line, and so that a failure is laid at the right
                    // door. By entry, not by song: the same song twice in the queue is two
                    // entries, and fetching ahead, which reads for no entry, tells nothing.
                    if (entry != null) main.post { if (current === era) nowReading(entry, copy) }
                }
                override fun closed(key: String) {
                    // Whether that song is kept whole now, for the account's index of them.
                    val cache = cache ?: return
                    val whole = if (org.medianexus.music.store.WholeMark.isConversion(key)) org.medianexus.music.store.WholeMark.length(cache, key) >= 0 else org.medianexus.music.store.WholeMark.cached(cache, key) >= 0
                    val id = songIdOfKey(key)
                    if (whole) era.account.keptIndex.kept(key, player.songFor(id) ?: Song(id = id)) else era.account.keptIndex.gone(key)
                }
                override fun dropped(key: String) = era.account.keptIndex.gone(key)
                override fun release() {
                    if (cache != null) era.kept.release()
                }
            }
        }
    }

    /** The songs of a kept page that a queue can come from: an album, a playlist, Liked songs. */
    /** When each of a list's songs was added to it (for "Date added"; see
     *  AccountGraph.addedDates). */
    private fun addedDates(source: String, songs: List<Song>): LongArray = account?.addedDates(source, songs) ?: LongArray(songs.size)

    /** The order a list's page shows these songs in, as places in it, with the order the list
     *  remembers (Windows' `order_for`): what a queue started from it follows. */
    private fun orderOf(key: String, songs: List<Song>, filter: String): List<Int> {
        // Narrowed as it was when the queue was started from it (ListView.following).
        val view = org.medianexus.music.library.ListView.following(key, graph.settings.value.lists[key], filter)
        if (view.isDefault) return songs.indices.toList()
        val added = if (view.sort == org.medianexus.music.library.ListView.ADDED) addedDates(key, songs) else null
        return org.medianexus.music.library.Arrange.arrange(songs.columns(), view) { added?.get(it) ?: 0 }.toList()
    }

    /** Whether a list holds these songs, in this order (by id). */
    private fun sameSongs(ids: List<String>, songs: List<Song>): Boolean {
        if (ids.size != songs.size) return false
        for (i in ids.indices) if (ids[i] != songs[i].id) return false
        return true
    }

    private fun songsIn(page: Any?): List<Song>? = when (page) {
        is org.medianexus.music.net.Album -> page.songs
        is org.medianexus.music.net.Playlist -> page.songs
        is org.medianexus.music.net.Found -> page.songs
        else -> null
    }

    private fun signInNeeded(): PlaybackException =
        PlaybackException(getString(R.string.car_sign_in), null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, signInButton())

    /** A "Sign in" button for the car's error screen, which opens the app on the phone. */
    private fun signInButton() = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.sign_in))
        putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp())
    }

    /** An "Open Nexus Music" button for the car's error screen. */
    private fun openAppButton() = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.open_app))
        putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp())
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- Where songs come from ------------------------------------------------------------------

    /** The copy each queue entry was last opened from, by the entry's number (the entry
     *  playing, and the next, made ready ahead of its turn): what the player's own data source
     *  says, not what the downloads and settings would give now (see [SongDataSource.open]). */
    private val reading = object : LinkedHashMap<Long, SongCopy>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SongCopy>?) = size > 32
    }

    /** Queue entries made ready again once after an error that blames nothing (see
     *  onPlayerError): not twice. */
    private val triedAgain = HashSet<Long>()

    private fun nowReading(entry: Long, copy: SongCopy) {
        reading.remove(entry)
        reading[entry] = copy
        if (player.queue.currentUid() == entry) publishExtras()
    }


    /** The server is known to be there: a check of it has succeeded, and nothing since has
     *  said otherwise. */
    private fun serverThere(): Boolean = graph.watch.state.value.let { it.sure && !it.offline }

    /**
     * Queue entries a read of which failed as "moved" (it could not go on in its form without
     * going over the lower limit): their next preparation takes the lower limit's form,
     * whatever the network says, so it can't fail so again (a network that keeps coming and
     * going could otherwise have it made ready again without end). Forgotten when the
     * setting is changed. Read on the loading threads.
     */
    private val movedEntries: MutableSet<Long> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** Called on the player's loading threads, for a read that belongs to `era`, with or
     *  without the kept songs to read; `lowerOnly` for an entry in [movedEntries]. */
    private fun resolve(era: Era, songId: String, withKept: Boolean, lowerOnly: Boolean = false): SongWhere {
        val api = era.account.api
        val song = player.songFor(songId)
        // A downloaded copy first, then the kept copy, then the server. A download that
        // needs a fresh copy (it failed once when played) is passed over for the server's
        // while the server is known to be there, and played all the same when it isn't (or
        // isn't yet known to be: a cold start in a dead spot). And only a
        // file still as long as the one that was verified: one that isn't is no download,
        // and the catalogue is told so now, not at the next start (`Downloads.notWhole`).
        val catalogued = era.account.catalogue.entry(songId)
        val whole = if (catalogued != null) era.account.catalogue.wholeFileOf(songId) else null
        if (catalogued != null && whole == null) era.account.downloads?.notWhole(songId)
        val downloaded = whole.takeIf { catalogued?.stale != true || !serverThere() }
        val suffix = song?.suffix?.lowercase().orEmpty()
        // (As the server converts it, if this phone is known not to decode the file itself:
        // found by this session's player, or remembered from a download.)
        val native = (suffix.isEmpty() || suffix in NATIVE_SUFFIXES) && songId !in era.needsConverting && !era.account.catalogue.convertsFor(songId, song?.size ?: 0)
        // The form for the network it is opened on (in doubt, the lower), unless it is kept
        // whole only in the other: then that copy, as it was fetched (see StreamQuality). What
        // is kept counts only if there are kept songs to read it from.
        val keeping = withKept && graph.settings.value.cacheLimitMb > 0
        val kept = era.account.keptIndex
        return graph.quality.whereNow(songId, song, native, { key -> keeping && kept.isWhole(key) }, downloaded, catalogued?.bitrate ?: 0, lowerOnly) { kbps, offset -> api.streamUrl(songId, kbps, offset) }
    }

    // ---- The queue changing -----------------------------------------------------------------

    private fun queueChanged() {
        val queue = player.queue
        // Shuffle and repeat are settings, and the list being played remembers its shuffle
        // when the person changes it while it plays (the shuffle button, anywhere).
        //
        // Not whenever the list's remembered choice and the queue disagree, as it used to
        // be. They also disagree when the choice was changed on another device, or the
        // queue was put back from the last run, or the app itself decided (a spoken "play
        // <artist>" is shuffled): putting the time on it then would tell every other device
        // that this one's shuffle is the newer choice, and overwrite theirs. The list's
        // choice is shared between devices, and the newer wins (see sync/PhoneShared.kt).
        val toggled = player.shufflePresses != shufflePressesSeen
        shufflePressesSeen = player.shufflePresses
        if (toggled || Remembered.differs(graph.settings.value, queue.shuffle, queue.repeat, "")) {
            val (shuffle, repeat, source) = Triple(queue.shuffle, queue.repeat, if (toggled) queue.source else "")
            Remembered.listChoice(graph.settings.value, shuffle, source)?.let { graph.choose(it) }
            graph.settings.update { Remembered.with(it, shuffle, repeat) }
        }
        publishExtras()
        player.refresh()
        updateButtons()
        save()
        aheadNow()
        // Auto levelling goes by song when shuffled; and the songs held now may need their
        // levelling values from the library copy.
        sound.update(graph.settings.value, queue.shuffle)
        lyricsAhead()
        sound.songsHeld(songsHeld(), era?.account?.copy?.state?.value)
    }

    /**
     * What only the app's own screens read, in the session's extras: how long "Next in
     * queue" is and how many songs are to come in all (for the queue sheet, which is shown
     * the nearest few hundred), the list the queue came from and the place in it of
     * the song playing (to mark its row, and no other row of the same song), and whether the
     * server is converting the song (for the format line).
     */
    private fun publishExtras() {
        val queue = player.queue
        val extras = Bundle().apply {
            putInt(EXTRA_QUEUED_AHEAD, queue.queuedAhead())
            putInt(EXTRA_AHEAD, player.ahead)
            putString(EXTRA_SOURCE, queue.source)
            putInt(EXTRA_ORIGIN, queue.currentOrigin() ?: -1)
            putBoolean(EXTRA_CONVERTED, queue.current()?.id?.let { id -> era?.needsConverting?.let { id in it } } == true)
            // The copy the song playing is being read from: what the format line says.
            putInt(EXTRA_PLAYING_KBPS, queue.currentUid()?.let { reading[it]?.kbps } ?: -1)
            // The entry that copy is of: the same song twice in a row is two entries.
            putLong(EXTRA_ENTRY, queue.currentUid() ?: -1)
            putBoolean(EXTRA_SETTLED, era?.restoring?.isCompleted == true)
            // The song these extras are about: the player's own word on what's playing can
            // reach a controller before or after them.
            putString(EXTRA_SONG, queue.current()?.id.orEmpty())
        }
        val before = shownExtras
        if (before != null && before.keySet() == extras.keySet() && extras.keySet().all { before.get(it) == extras.get(it) }) return
        shownExtras = extras
        session.setSessionExtras(extras)
    }

    private var shownExtras: Bundle? = null

    /** Hands the queue as it stands to the app's one writer of saved queues, which writes
     *  the newest it has been given, off the main thread, in order. */
    private fun save() {
        if (leaving) return
        val kept = era?.queue ?: return
        val queue = player.queue
        // Something the person started while the saved queue was still being put back is the
        // queue now (the saved one is not put over it: see QueuePlayer.restore).
        if (!kept.settled && !queue.isEmpty) kept.settle()
        val position = exo.currentPosition / 1000.0
        // When the queue last really changed, or was played on (Windows' `queue_stamp`): the
        // queue itself, the place in it, or the second of the song. Putting a queue back, or
        // taking one from another device or from the list it follows, is not that (`quiet`):
        // between devices the queue changed last has the say, and one this phone only holds
        // must not be passed on as changed here, now.
        val mark = Triple(queue.revision, queue.cursor, position.toLong())
        val before = queueMark
        if (mark != before) {
            queueMark = mark
            // The list itself is another list (a change made here, or one put in its
            // place): a save prepared before it is for another queue. (The place in it and
            // the second of the song moving on are the song playing on, and no change: what
            // moves them otherwise is a jump, which says so itself. See [queueRevision].)
            if (before == null || before.first != mark.first) queueRevision++
            // Whose the queue is was said at the person's own action ([claimQueue]). Here
            // it has only come to look different. If the list itself is another list, that
            // is a change made here whoever made it (a list chosen by voice or in the car
            // reaches the player without a command of its own). If only the place has
            // moved, that is the song playing on: a change to save if the queue is this
            // phone's own, and nothing at all if it is still another device's.
            val listChanged = before == null || before.first != mark.first
            if (!queueQuiet && !queue.isEmpty && (listChanged || queueTaken == null)) {
                queueStamp = System.currentTimeMillis() / 1000
                queueTaken = null
                // Saved on the server for the person's other devices: a few seconds after a
                // change, and now and then while a song simply plays on.
                val step = era?.account?.queueStep
                if (!listChanged && before?.second == mark.second && exo.playWhenReady) step?.progressed() else step?.changedHere()
            }
        }
        val stamp = queueStamp
        // The queue is copied for the writer only when it has changed; while it only plays,
        // where it has got to is all that's handed over.
        val taken = queueTaken
        kept.save(queue.revision, queue.cursor, position, stamp, taken) { queue.saved(position, stamp, taken) }
        lastSaveAt = System.currentTimeMillis()
    }

    /** How many times shuffle had been changed by its button when the queue was last looked
     *  at (see [queueChanged]). */
    private var shufflePressesSeen = 0L

    /**
     * Moves on whenever **what a save of the queue would carry has changed**: the list (in
     * [save]), the song or the place in it by a jump of any kind (the player's position
     * discontinuity, other than one song ending and the next beginning), playing or paused.
     * It does not move as a song simply plays on, or plays on into the next: the saves made
     * now and then while one plays are for the same queue. See `QueueHere.revision`.
     */
    private var queueRevision = 0L
    /** When the queue was last changed here, or played on (seconds since 1970; see [save]). */
    private var queueStamp = 0L
    /** Set while the queue is one taken up from another device with nothing done to it or
     *  played of it here since: when that device saved it, as the server said. Kept with
     *  the queue's place, so that after a restart it is still that device's and is not saved
     *  as this phone's own. */
    private var queueTaken: String? = null
    /** The queue, the place in it and the second of the song as last saved. */
    private var queueMark: Triple<Long, Int, Long>? = null
    /** Set while a queue is put in place that is not a change made here. */
    private var queueQuiet = false

    /**
     * **The person has done something to the queue, or to playing it, on this phone**: from
     * this moment it is this phone's own queue, timed now, and not one "taken up from another
     * device and left alone", which that device's next save would replace. (The shared
     * core's contract, at the top of its `handover.rs`.)
     *
     * Said at the action itself, not found out afterwards from the queue looking different
     * ([save]): a seek while paused, or Play and then Pause inside a second, changes nothing a
     * look at the queue would notice. **This is the one place the person's ways in pass**:
     * every command a controller sends the player (the app's own screens, the notification,
     * a headset, the car, an app the person allowed) and the app's own commands for the
     * queue. What the app does by itself never comes here: the next song beginning, the
     * sleep timer, the sound taken away, similar songs arriving, another device's queue put
     * in the player, the last run's queue put back.
     */
    private fun claimQueue() {
        queueTaken = null
        if (player.queue.isEmpty) return
        queueStamp = System.currentTimeMillis() / 1000
        // (Another queue, to a save that was being prepared: it is sent only for the queue
        // as it was at the number it was prepared at.)
        queueRevision++
        val step = era?.account?.queueStep ?: return
        // What another device was playing was offered as the newer of the two, which it has
        // just stopped being: the person has acted past that offer.
        step.actedPast()
        step.changedHere()
    }

    /** Once a press has been carried out, the queue as it then stands is kept: a seek in a
     *  paused song moves the place, and nothing else would write that down before the
     *  next time the song is played (the place is otherwise noted as a song plays, and
     *  when it stops). */
    private val keepAfterPress = Runnable { save() }

    /** Does something to the queue that is not the person changing it here: nothing of it
     *  is stamped as changed now, or passed on to other devices. */
    private inline fun quietly(change: () -> Unit) {
        queueQuiet = true
        try {
            change()
            // (Whatever it did has been seen by now: queue changes are told at once.)
            val queue = player.queue
            queueMark = Triple(queue.revision, queue.cursor, (exo.currentPosition / 1000.0).toLong())
        } finally {
            queueQuiet = false
        }
    }

    /** The player's side of sharing the queue with the person's other devices (see
     *  [org.medianexus.music.sync.QueueStep]), for one account's turn. */
    private fun queueHere(era: Era) = object : org.medianexus.music.sync.QueueHere {
        override fun queue(): org.medianexus.music.sync.QueueNow? {
            if (this@PlaybackService.era !== era) return null
            val queue = player.queue
            if (queue.isEmpty) return null
            // (In the order it plays. What of it is passed on is the shared core's to say.)
            val songs = List(queue.order.size) { queue.songs[queue.order[it]].let { song -> org.medianexus.music.core.QueueSong(song.id, song.duration.toInt().coerceAtLeast(0), song.radioUrl.isNotEmpty()) } }
            return org.medianexus.music.sync.QueueNow(songs, queue.cursor, exo.currentPosition / 1000.0)
        }

        override fun currentId(): String? = if (this@PlaybackService.era === era) player.queue.current()?.id else null
        override fun playing(): Boolean = exo.playWhenReady && !player.queue.isEmpty
        override fun stamp(): Long = queueStamp
        override fun taken(): String? = queueTaken
        override fun revision(): Long = queueRevision

        override suspend fun replace(songs: List<Song>, cursor: Int, positionMs: Long, stamp: Long, taken: String, choice: Long?, asOf: Long, stampThen: Long, still: () -> Boolean): Boolean {
            // Not before this phone's own queue from its last run is back: that comes first,
            // and what another device saved takes its place only if it is the newer.
            era.restoring.await()
            if (this@PlaybackService.era !== era || songs.isEmpty()) return false
            // (Looked at again: something may have been started here, or done to the queue,
            // since it was decided to take this one up. Whether the other device's is the
            // newer was the shared core's to say, from the stamp as it was then.)
            if (choice == null && (exo.playWhenReady && !player.queue.isEmpty || queueStamp != stampThen || !Choices.stands(asOf))) return false
            // (The queue as it is at this moment, by number: looked at once more when the
            // one to take up is ready.)
            val was = queueRevision
            // The place it acts for in the order of choices. "Continue here" took its own, at
            // the tap. A queue taken up without being asked is nobody's choice and takes
            // none: it acts for the newest there was when it was decided on, so a start the
            // person made just before it, still being made ready, is not overtaken by it and
            // plays over it; and anything the person does after it was decided on, to the
            // queue too, stands.
            val place = choice ?: asOf
            val next = withContext(Dispatchers.Default) { player.prepare(songs, cursor.coerceIn(0, songs.size - 1), context = "", shuffle = false) }
            if (this@PlaybackService.era !== era) return false
            // Not once sharing has been switched off. And it gives way to anything the person
            // chose meanwhile (a pause, a stop, something else to play): since the tap, for
            // "Continue here"; while it was being made ready, taken up unasked.
            if (!still() || !Choices.stands(place)) return false
            if (choice == null && exo.playWhenReady && !player.queue.isEmpty) return false
            // Immediately before it goes in, once more: nothing has been done here to the
            // queue, or to playing it, while this one was being made ready. Another queue
            // taken up meanwhile is such a thing: of two made ready at once, only the first
            // to be ready goes in, and the other gives way to it.
            if (choice == null && (queueRevision != was || queueStamp != stampThen)) return false
            if (choice != null) holdForeground(why = "to carry on from another device")
            val before = queueStamp
            queueStamp = stamp
            var done = false
            quietly { done = player.playFor(place, next, positionMs, playNow = choice != null) }
            if (!done) queueStamp = before
            // It is that device's queue, and not this phone's to save, until something is
            // done to it here.
            if (done) queueTaken = taken.takeIf { choice == null }
            if (done) save()
            // "Continue here", once taken, is the person's own choice: this phone's own
            // queue from this moment, by its own clock.
            if (done && choice != null) claimQueue()
            return done
        }
    }

    // ---- The car's buttons ------------------------------------------------------------------

    private fun buttons(): List<CommandButton> {
        val queue = if (::player.isInitialized) player.queue else null
        val liked = queue?.current()?.let { song -> account?.likes?.isLiked(song.id) } == true
        val shuffle = queue?.shuffle == true
        val repeat = queue?.repeat ?: Repeat.OFF
        return listOf(
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (liked) R.string.remove_from_liked else R.string.add_to_liked))
                .setSessionCommand(LIKE)
                .build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(if (shuffle) R.string.shuffled else R.string.shuffle))
                .setSessionCommand(SHUFFLE)
                .build(),
            CommandButton.Builder(
                when (repeat) {
                    Repeat.ALL -> CommandButton.ICON_REPEAT_ALL
                    Repeat.ONE -> CommandButton.ICON_REPEAT_ONE
                    else -> CommandButton.ICON_REPEAT_OFF
                },
            )
                .setDisplayName(getString(when (repeat) { Repeat.ALL -> R.string.repeat_all; Repeat.ONE -> R.string.repeat_one; else -> R.string.repeat }))
                .setSessionCommand(REPEAT)
                .build(),
        )
    }

    private var shownButtons: List<CommandButton>? = null

    private fun updateButtons() {
        if (!::session.isInitialized) return
        val now = buttons()
        if (now != shownButtons) {
            shownButtons = now
            session.setMediaButtonPreferences(now)
        }
    }

    // ---- What the player does -----------------------------------------------------------------

    private val listening = ListeningPlayback(::clock) { event, scope -> graph.recording.capture(event, scope) }
    private var journalReason: StopReason? = null
    private var journalBegan = PlayBegan.HERE
    private var journalCarEntry: Long? = null
    private var journalAutoplayFrom: Long? = null
    private var journalInAutoplay = false
    private var journalFault: DiagnosticFault? = null

    private fun foregroundFault() {
        graph.recording.diagnostics.fault(DiagnosticFault(DiagnosticPlace.FOREGROUND, DiagnosticShape.PERMISSION))
    }

    private fun carController(controller: MediaSession.ControllerInfo?): Boolean =
        controller?.packageName == "com.google.android.projection.gearhead" ||
            controller?.packageName == "com.android.car.media"

    private fun observeTransport(command: Int) {
        when (command) {
            Player.COMMAND_STOP -> listening.stop(entryOf(exo.currentMediaItem), exo.currentPosition, StopReason.STOPPED)
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> journalReason = StopReason.NEXT
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> journalReason = StopReason.PREVIOUS
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS -> {
                journalReason = StopReason.CHOICE
                journalAutoplayFrom = null
                journalInAutoplay = false
            }
        }
        if (::session.isInitialized && carController(session.controllerForCurrentRequest)) {
            journalBegan = PlayBegan.CAR
        }
    }

    private fun observeSounding() {
        val queue = player.queue
        val scope = era?.account?.account?.key?.let(graph.recording::scopeFor)
        val entry = entryOf(exo.currentMediaItem)
        listening.retire(scope, entry, exo.currentPosition)
        if (scope == null || entry == null || entry != queue.currentUid()) return
        val song = queue.current() ?: return
        val fromRadio = Autoplay.isRadio(queue.source)
        val onPage = queue.currentOrigin() != null
        val source = if (onPage || fromRadio) queue.source else ""
        val began = when {
            entry == journalCarEntry -> PlayBegan.CAR
            journalBegan == PlayBegan.NEXT && journalInAutoplay && !onPage && !song.queued -> PlayBegan.AUTOMATIC
            else -> journalBegan
        }
        val sound = when (val opened = reading[entry]) {
            is SongCopy.Download -> PlaySound.DOWNLOAD
            is SongCopy.Stream -> if (opened.fromKept) PlaySound.KEPT else PlaySound.STREAM
            null -> PlaySound.STREAM
        }
        val duration = exo.duration.takeIf { it > 0 } ?: (song.duration * 1000L).takeIf { it > 0 }
        listening.sounding(scope, entry, song, PlayOrigin.from(source, if (fromRadio) queue.songs.takeIf { it.isNotEmpty() }?.id(0) else null), began, sound, exo.currentPosition, duration)
        journalBegan = PlayBegan.HERE
        journalCarEntry = null
    }

    private inner class Watcher : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listening.running(isPlaying)
            if (isPlaying) {
                listening.resume(entryOf(exo.currentMediaItem), exo.currentPosition)
                journalFault = null
                heardUid = player.queue.currentUid()
                failures = 0
                passing.end()
                stopWaiting()
                gaveUp = false
                waitingForCall = null
                // What the server said in refusing something earlier is old news now.
                graph.refusals.clear()
                countPlaying()
                listenFor()
                aheadNow()
            } else {
                // If it had been playing steadily it was going well up to this moment, and
                // anything that goes wrong for this song from here is counted from here. (Not
                // after a moment's sound between two stalls: see Recovery.Outage.played.)
                outage.played(era?.number ?: 0, player.queue.currentUid() ?: 0, playingSince, clock())
                // A last sample: what was heard up to the pause counts.
                plays.paused(exo.currentPosition / 1000.0, clock())
                save()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Another song: a try at the server put off for the last one is no longer wanted.
            if (waitingFor?.uid != player.queue.currentUid()) stopWaiting()
            gaveUp = false
            // An outage is counted for the song it stopped: this one starts from nothing.
            goingWell()
            // Come to a song that isn't on the phone, with the server known to be out of
            // reach: not waited on.
            passOverOutOfReach()
            // Rolled into the next song while playing (the song before it has had its last
            // sample, in onPositionDiscontinuity, which comes first).
            if (exo.isPlaying) {
                if (heardThisTime) observeSounding()
                countPlaying()
            }
            aheadNow()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A jump in position, or another song by anything but the one before it
            // ending: what a save of the queue would carry has changed (within the same
            // second of the same song too, which the queue's time can't show).
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) queueRevision++
            val from = oldPosition.positionMs / 1000.0
            val oldEntry = entryOf(oldPosition.mediaItem)
            val sameEntry = oldEntry != null && oldEntry == entryOf(newPosition.mediaItem)
            val intent = journalReason
            journalReason = null
            when {
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    listening.stop(oldEntry, oldPosition.positionMs, StopReason.FINISHED)
                    if (oldEntry != null && journalAutoplayFrom == oldEntry) journalInAutoplay = true
                    journalBegan = PlayBegan.NEXT
                }
                !sameEntry -> {
                    listening.stop(oldEntry, oldPosition.positionMs, intent ?: StopReason.CHOICE)
                    if (journalBegan != PlayBegan.CAR) journalBegan = if (intent == StopReason.NEXT) PlayBegan.NEXT else PlayBegan.HERE
                }
                intent == StopReason.NEXT -> listening.stop(oldEntry, oldPosition.positionMs, StopReason.NEXT)
                reason == Player.DISCONTINUITY_REASON_SEEK -> listening.seek(oldEntry, oldPosition.positionMs, newPosition.positionMs)
            }
            when {
                // Repeating one song: the play that just finished is counted, and the next
                // time round is a new play of it.
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> plays.left(from, clock(), again = sameEntry)
                sameEntry -> plays.sought(from, newPosition.positionMs / 1000.0, clock())
                else -> plays.left(from, clock())
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The end of the queue. The same song started again from the top is a new play.
            if (playbackState == Player.STATE_ENDED) {
                listening.stop(entryOf(exo.currentMediaItem), exo.currentPosition, StopReason.FINISHED)
                plays.ended(clock())
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Something is playing, or meant to be: a reason to know whether the server is
            // back, and to ask now if it is out of reach (see ServerWatch).
            graph.playing = playWhenReady
            graph.recording.diagnostics.playing(playWhenReady)
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                listening.pause(entryOf(exo.currentMediaItem), exo.currentPosition)
            }
            // (Play and pause: a save of the queue prepared before it was decided on what
            // was true then. See `QueueHere.revision`.)
            queueRevision++
            if (playWhenReady) graph.watch.nudge()
            // "Play" with nothing to play is not playing: Media3 presses play all the same
            // when there was nothing to resume, and left like that the next queue to arrive
            // would start by itself.
            if (playWhenReady && player.queue.isEmpty) {
                exo.pause()
                if (holding) standDown()
            }
            // Paused by someone: a Play waiting for a call to end is no longer wanted.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) waitingForCall = null
            if (playWhenReady) {
                askedToPlayAt = clock()
                // Play, pressed by someone: a new try at a server that was given up on.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    goingWell()
                    gaveUp = false
                    passing.end()
                }
            } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                // Noted for Diagnostics: straight after Play, this is Android declining to let
                // the app be heard (its rules for an app in the background: Android 15 gives
                // audio focus only to the app in front or one with a foreground service, and
                // Android 17 asks more of that service). Later, another app has taken over.
                val after = clock() - askedToPlayAt
                val call = inCall()
                graph.diagnostics.note(
                    when {
                        after < FOCUS_REFUSED_MILLIS && call -> "audio focus: refused at once after Play, a call being in progress: playing when it ends"
                        after < FOCUS_REFUSED_MILLIS -> "audio focus: refused or taken away at once after Play (Android's rules for an app in the background?)"
                        else -> "audio focus: lost to another app"
                    },
                    now = after < FOCUS_REFUSED_MILLIS,
                )
                // Refused the sound as it asked: the player has not played (ExoPlayer turns
                // Play down itself), and the car is told what will put it right rather than
                // shown a paused song with nothing said. Not so for a call: opening the app
                // would be the wrong advice. Nothing is said, and Play happens by itself when
                // the call ends, as it was asked for.
                if (after < FOCUS_REFUSED_MILLIS) {
                    if (call) waitForCall() else cannotStart("Android would not give it the sound")
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // By kind only: an error's own message can name the server.
            val status = (error.cause as? StreamFailure)?.status?.takeIf { it > 0 }
            val fault = DiagnosticFault(DiagnosticPlace.PLAYBACK, when {
                undecodable(error) -> DiagnosticShape.DECODE
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> DiagnosticShape.TIMEOUT
                network(error) -> DiagnosticShape.CONNECTION
                status == 401 || status == 403 -> DiagnosticShape.REFUSED
                status != null -> DiagnosticShape.ANSWER
                else -> DiagnosticShape.READ
            })
            journalFault = fault
            graph.recording.diagnostics.fault(fault)
            graph.diagnostics.note("playback error: ${error.errorCodeName}" + (status?.let { " (the server answered $it)" } ?: ""))
            val song = player.queue.current() ?: return
            val failure = error.cause as? StreamFailure
            // The copy that was being played, by the player's own data source.
            val copy = player.queue.currentUid()?.let { reading[it] }
            val download = copy as? SongCopy.Download
            when {
                // An internet radio station is somewhere else on the internet, not the music
                // server: joined again a few times (see QueuePlayer.rejoinStation), then
                // passed over, as Windows does. Nothing is asked of the server about it.
                // Offline (switched on while it played): not joined again. Paused (by the person
                // or the sleep timer) before the failure arrived: left as it is, nothing joined
                // again or passed over; Play tries it afresh.
                song.radioUrl.isNotEmpty() -> if (exo.playWhenReady && (graph.watch.offline || !player.rejoinStation())) passOver()
                // The copy the song was playing from has gone (its download removed or
                // replaced), or a download has arrived while its stream failed: made ready
                // again from where it is, by time, on whatever is best now. Nothing failed.
                failure?.moved == true -> {
                    player.queue.currentUid()?.let { entry ->
                        reading.remove(entry)
                        // Made ready again in the lower form (see movedEntries).
                        synchronized(movedEntries) {
                            movedEntries += entry
                            while (movedEntries.size > 32) movedEntries.remove(movedEntries.first())
                        }
                    }
                    publishExtras()
                    exo.prepare()
                }
                // A downloaded file that couldn't be read or parsed (or holds what this phone
                // can't decode): **it needs a fresh copy** (`Downloads.demote`), which is
                // fetched when the server can be reached and takes its place once verified.
                // Nothing is deleted: with no server it is still what there is. Only when it
                // was the download that was playing, and only that file. Meanwhile the song
                // is played from the server if it can be had; if not, this entry is passed
                // over (it has just failed where it stands).
                download != null && Recovery.laidAtTheFile(error.errorCode, failure?.local == true) -> {
                    graph.diagnostics.note("a downloaded copy wouldn't play (${error.errorCodeName}): it needs a fresh copy")
                    player.queue.currentUid()?.let(reading::remove)
                    publishExtras()
                    val uid = player.queue.currentUid()
                    val downloads = era?.account?.downloads
                    (era?.scope ?: graph.scope).launch(Dispatchers.Main.immediate) {
                        // (On the disk before the player looks again for where to read from.)
                        val stale = downloads?.demote(
                            song.id, download.file.name, download.modified, "wouldn't play (${error.errorCodeName})",
                            beyondThePhone = Recovery.beyondThePhone(error.errorCode),
                        ) == true
                        if (player.queue.currentUid() != uid || exo.playerError == null) return@launch
                        when {
                            stale && serverThere() -> {
                                graph.toasts.show(getString(R.string.bad_download, song.title))
                                exo.prepare()
                            }
                            // (No longer that download: it has been replaced, or removed.
                            // Looked for again, once.)
                            !stale && uid?.let { triedAgain.add(it) } == true -> exo.prepare()
                            // No server to play it from: on to the next that is on the phone.
                            else -> passOverToThePhone()
                        }
                    }
                }
                // A decoder or the sound output failing while a download plays says nothing
                // about the file: nothing is set aside. The entry is made ready again, once.
                download != null && undecodable(error) && !Recovery.laidAtTheFile(error.errorCode, false) &&
                    player.queue.currentUid()?.let { triedAgain.add(it) } == true -> {
                    graph.diagnostics.note("a downloaded copy stopped (${error.errorCodeName}): nothing is laid at the file; tried again")
                    exo.prepare()
                }
                download == null && undecodable(error) && era?.needsConverting?.mark(song.id) == true -> {
                    // The phone can't play this file as it is: have the server convert it.
                    player.queue.currentUid()?.let(reading::remove)
                    publishExtras()
                    exo.prepare()
                }
                // Offline mode, and this one isn't on the phone: the next that is.
                offline(error) -> passOverToThePhone()
                // Chosen with the server known to be out of reach, and not on the phone:
                // the next that is, at once, with a line saying so. Not "Reconnecting…".
                network(error) && passOverOutOfReach() -> Unit
                network(error) -> {
                    // Is the server there at all? (Windows asks it, as here: a ping.) Out of
                    // reach, the app is offline until it is back: what plays, plays from the
                    // phone, and changes are held.
                    graph.watch.check()
                    // Not the song's fault: the player has already tried a few times. Wait
                    // where it is for the server to come back, trying now and then (with no
                    // permission to reach the home network, until it is given). But not for
                    // ever: see giveUp.
                    val waited = outage.waited(era?.number ?: 0, player.queue.currentUid() ?: 0, clock())
                    if (Recovery.givesUp(waited, SERVER_WAIT_MILLIS)) giveUp() else retryLater(SERVER_WAIT_MILLIS)
                }
                else -> passOver()
            }
        }
    }

    /**
     * Asks for the song again later, from where it stopped. If that fails too, the player's
     * error brings this round again. The try belongs to this account's turn and this entry
     * of the queue: if either has changed by the time it is due, it is dropped.
     */
    @androidx.annotation.VisibleForTesting
    internal fun retryLater(delayMs: Long) {
        val era = era ?: return
        val uid = player.queue.currentUid() ?: return
        val wanted = Recovery.Retry(era.number, uid)
        // Nobody chose this try: anything that takes a place in the order of choices before it
        // is due (a Pause, a Stop, which leaves "play when ready" as it was) stops it.
        val asked = Choices.latest
        retry?.cancel()
        waitingFor = wanted
        retry = era.scope.launch {
            delay(delayMs)
            if (waitingFor != wanted) return@launch
            waitingFor = null
            // Paused or stopped meanwhile: no try; Play tries again (Media3 prepares the
            // player when it is pressed).
            if (!exo.playWhenReady || !Choices.stands(asked)) return@launch
            if (!wanted.stillStands(this@PlaybackService.era?.number, player.queue.currentUid(), failedNow())) return@launch
            // The player is to play again with nobody having asked it to just now, and after a
            // long outage Media3 may have let the foreground go with the player idle: in the
            // foreground first, as before any Play, or the player would ask for the sound with
            // no standing to have it (and be refused it, on Android 15 and later, with nothing
            // said). Android refusing is the same recoverable error as a refusal at the start.
            if (exo.playWhenReady && !holdForeground(why = "to carry on after the server came back")) {
                cannotStart("Android would not let the player back into the foreground to carry on")
                return@launch
            }
            exo.prepare()
        }
    }

    /** Whether the player has failed and is waiting to be asked again: ExoPlayer's word,
     *  stood in for by the one test that is about what a try does, not how a stream fails. */
    internal var failedNow: () -> Boolean = { exo.playerError != null }

    private fun stopWaiting() {
        retry?.cancel()
        retry = null
        waitingFor = null
    }

    /** Things are going well for the song at the cursor, as far as is known at this moment
     *  (see [Recovery.Outage.well]). */
    private fun goingWell() {
        outage.well(era?.number ?: 0, player.queue.currentUid() ?: 0, clock())
    }

    // ---- Whether it can be heard ----------------------------------------------------------------
    //
    // Android 17 can let a player run on in silence, and says nothing to the app when it
    // does. What the app can see for itself is noted for Diagnostics each time playing
    // begins: whether the audio track is moving (the player's own word), whether the service
    // is in the foreground, what Android lists as sounding and where that is going, and the
    // volume. Not whether Android has muted it: no app is told that. So a line that says the
    // track is moving, in the foreground, with the sound, and going to the car, from a drive
    // where nothing was heard, is Android muting it.

    private var playingSince = 0L
    private var heardThisTime = false

    /** Playing has begun: if the audio track has not moved a few seconds on, that is noted. */
    private fun listenFor() {
        playingSince = clock()
        heardThisTime = false
        main.removeCallbacks(notHeard)
        main.postDelayed(notHeard, HEARD_WITHIN_MILLIS)
    }

    private val notHeard = Runnable {
        if (exo.isPlaying && !heardThisTime) {
            graph.recording.diagnostics.fault(DiagnosticFault(DiagnosticPlace.AUDIO_OUTPUT, DiagnosticShape.NO_SOUND))
            graph.diagnostics.note("sound: playing for ${HEARD_WITHIN_MILLIS / 1000} s and the audio track has not moved. ${Audible.describe(this, inForeground())}", now = true)
        }
    }

    private val heard = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioPositionAdvancing(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            if (exo.isPlaying) observeSounding()
            if (heardThisTime) return
            heardThisTime = true
            graph.recording.diagnostics.note(DiagnosticNote.SOUND_MOVED)
            graph.diagnostics.note("sound: the audio track is moving. ${Audible.describe(this@PlaybackService, inForeground())}")
        }

        override fun onAudioUnderrun(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            graph.recording.diagnostics.fault(DiagnosticFault(DiagnosticPlace.AUDIO_OUTPUT, DiagnosticShape.NO_SOUND))
            graph.diagnostics.note("sound: the audio track ran dry for a moment")
        }
    }

    /**
     * The server has been out of reach for ten minutes: stop trying, and pause, saying so.
     *
     * Ten minutes is how long Media3 keeps the service in the foreground once nothing is
     * playing (and how long Google asks a player to hold it through a failure). Past that the
     * service is an ordinary background one: Android may not let it back into the foreground
     * when the server returns, and Android 17 would not let it be heard. So rather than drift
     * into "playing" with no standing to play, it ends where a person would expect: paused,
     * with the reason on the screen, and the next Play (which Android does let start it)
     * tries again from where the song stopped.
     */
    private fun giveUp() {
        stopWaiting()
        gaveUp = true
        graph.diagnostics.note("the server was out of reach for ten minutes: paused")
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "the server was out of reach for ten minutes: paused")
        exo.pause()
        // The words change: no longer "Reconnecting…".
        player.refresh()
    }

    /** The queue entry that has been heard since it came to the cursor (its number). */
    private var heardUid: Long? = null

    /**
     * The server is **known** to be out of reach, and the song at the cursor is not on the
     * phone and has not been heard since it was chosen (see [Recovery.passesOverOutOfReach]):
     * the next in the queue that can be played is started at once, and a short line says
     * why; with none, the player stops where it is and says that. Returns whether it did
     * either. Asked when the server is found to be out of reach, when the player comes to
     * another song, and when a stream fails.
     */
    private fun passOverOutOfReach(): Boolean {
        val era = era ?: return false
        val reach = graph.watch.state.value
        val song = player.queue.current() ?: return false
        val known = reach.unreachable && reach.sure && !reach.manual
        val onPhone = song.radioUrl.isEmpty() && era.account.availability.plays(song)
        if (!Recovery.passesOverOutOfReach(known, onPhone, heard = heardUid == player.queue.currentUid(), meansToPlay = exo.playWhenReady)) return false
        val next = nextOnThePhone()
        stopWaiting()
        if (next == null) {
            // Nothing further on is on the phone: stopped here, and said (not left trying).
            graph.diagnostics.note("a song chosen with the server out of reach is not on the phone, and nor is anything after it: stopped")
            exo.pause()
            exo.stop()
            say(getString(R.string.cant_play_offline))
            return true
        }
        graph.diagnostics.note("a song chosen with the server out of reach is not on the phone: passed over for the next that is")
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "out of reach: passed over to place $next of the queue")
        passTo(next)
        exo.prepare()
        say(getString(R.string.passed_over_out_of_reach))
        return true
    }

    /** The place of the next entry that plays with no server, in the order the queue will
     *  play it (see [Recovery.nextThatPlays]): asked of the queue itself, which has all of
     *  it, its shuffled order and its repeat, and not of the player's part of it. */
    private fun nextOnThePhone(mayTry: (String) -> Boolean = { true }): Int? {
        val availability = era?.account?.availability ?: return null
        val queue = player.queue
        return Recovery.nextThatPlays(queue.cursor, queue.order.size, queue.repeat) { place ->
            queue.songs[queue.order[place]].let { it.radioUrl.isEmpty() && availability.plays(it) && mayTry(it.id) }
        }
    }

    /** A short line for whoever is looking: the phone's own screens (a toast), and every
     *  controller (a car shows it under what is playing), without stopping anything. */
    private fun say(words: String) {
        graph.toasts.show(words)
        runCatching { session.sendError(SessionError(SessionError.ERROR_NOT_SUPPORTED, words)) }
    }

    /**
     * Gives up on the song that's playing and goes to the next, unless three in a row have
     * failed or there is no next, as Windows does. Whether it then plays is as it was: a
     * song that failed while paused, or while only being made ready, leaves the next one
     * paused too.
     */
    private fun passOver() {
        failures++
        if (failures >= 3) {
            finishWithError()
            return
        }
        // "The next" is the queue's to say, in the order it will play: the place after this
        // one, and from the last, round to the top if repeat is on (as Next goes). The
        // player's own playlist is only part of a long queue, and ends where that part does.
        val step = player.queue.next()
        if (step == null || !step.carriesOn || step.place == player.queue.cursor) {
            finishWithError()
            return
        }
        passTo(step.place)
        exo.prepare()
    }

    /**
     * With no server to be had, the song at the cursor can't be played: on to the next in
     * the queue that is on the phone, in the order the queue will play ([nextOnThePhone]),
     * and not one that has already failed in this run of passing over ([passing]); with
     * none, it stays where it is, stopped, and says so.
     */
    private fun passOverToThePhone() {
        player.queue.current()?.let { passing.failed(player.queue.currentUid(), it.id) }
        val next = nextOnThePhone(passing::mayTry)
        if (next == null) {
            finishWithError()
            say(getString(R.string.cant_play_offline))
            return
        }
        passTo(next)
        passing.passedTo(player.queue.currentUid())
        exo.prepare()
    }

    private fun finishWithError() {
        listening.stop(entryOf(exo.currentMediaItem), exo.currentPosition, StopReason.ERROR,
            journalFault ?: DiagnosticFault(DiagnosticPlace.PLAYBACK, DiagnosticShape.UNAVAILABLE))
    }

    private fun undecodable(error: PlaybackException) = error.errorCode in listOf(
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private fun network(error: PlaybackException) = error.errorCode in listOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
    ) || (error.cause as? StreamFailure)?.let { it.status >= 500 } == true

    /** Offline mode is on, and the song isn't kept on the phone. */
    private fun offline(error: PlaybackException) = (error.cause as? StreamFailure)?.offline == true

    /** The words the car, the notification and the app show for a playback error. */
    @androidx.annotation.VisibleForTesting
    internal fun explain(error: PlaybackException, song: Song?): PlaybackException? = when {
        // "Moved": the entry is being made ready again from where it is, at once (see
        // onPlayerError), as quietly as the player's own retries: nothing to tell anyone.
        (error.cause as? StreamFailure)?.moved == true -> null
        // Android 17, a server on the home network, and no permission to reach it: waiting
        // for it to come back would be waiting for ever. Say what will put it right.
        // The player's own error is not passed on as the cause: a playback error goes to
        // every app with a controller, cause and all.
        offline(error) -> PlaybackException(getString(R.string.cant_play_offline), null, error.errorCode)
        // A station given up on after its rejoins: as a song that can't be played.
        song?.radioUrl?.isNotEmpty() == true && player.stationGaveUp() -> PlaybackException(getString(R.string.couldnt_play_song), null, error.errorCode)
        network(error) && graph.localAccess.blocked.value -> PlaybackException(getString(R.string.car_local_network), null, error.errorCode, openAppButton())
        // Short, as Windows says it ("Reconnecting"): a car's player has a line for it under
        // the title. The phone's own Now Playing has room for the whole sentence, and is told
        // by the extra that this is the lost connection.
        // Given up on after ten minutes: it will not carry on by itself any more.
        network(error) && gaveUp -> PlaybackException(getString(R.string.car_unreachable), null, error.errorCode)
        network(error) -> PlaybackException(getString(R.string.lost_connection_short), null, error.errorCode, Bundle().apply { putBoolean(EXTRA_LOST, true) })
        // A fixed sentence: the song's name goes to the car and the notification as what is
        // playing, not into the error every app with a controller can read.
        song != null && !undecodable(error) -> PlaybackException(getString(R.string.couldnt_play_song), null, error.errorCode)
        // (A file the phone can't decode is about to be asked for again, converted.)
        else -> PlaybackException(null, null, error.errorCode)
    }

    /** Once a second: counts listening, and notes where playback has got to now and then. */
    private val ticker = object : Runnable {
        override fun run() {
            if (exo.isPlaying) {
                listening.checkpoint(entryOf(exo.currentMediaItem), exo.currentPosition)
                plays.tick(exo.currentPosition / 1000.0, clock())
                if (System.currentTimeMillis() - lastSaveAt >= CHECKPOINT_MILLIS) save()
            }
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    /** The clock listening is timed on: it only has to run steadily while a song plays. */
    private fun clock(): Long = android.os.SystemClock.elapsedRealtime()

    private fun entryOf(item: MediaItem?): Long? = (item?.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid

    /** The song at the cursor is playing: count it (see [PlayReporter]). */
    private fun countPlaying() {
        val uid = player.queue.currentUid() ?: return
        val song = player.queue.current() ?: return
        // A song playing as part of a playlist counts towards that playlist too; one put in
        // the queue by hand, or played from anywhere else, doesn't (Windows' `tick`).
        val queue = player.queue
        val from = if (queue.currentOrigin() != null && queue.source.startsWith("playlist:")) queue.source else ""
        plays.playing(uid, song, exo.currentPosition / 1000.0, clock(), from)
    }

    /** Something started: Recently played, whether or not plays are reported, a station too
     *  (as Windows' `record_played`). */
    private fun recordStarted(song: Song) {
        account?.recentlyPlayed?.played(song)
    }

    /** A song (never a station: see [PlayReporter]) started: "now playing", for the server. */
    private fun reportNowPlaying(song: Song) {
        val graph = account ?: return
        // ("Now playing" is of the moment: offline, there is nobody to tell, and nothing kept.)
        if (!this.graph.settings.value.scrobble || this.graph.watch.offline) return
        graph.scope.launch { runCatching { graph.api.scrobble(song.id, submission = false) } }
    }

    /** A play counts (decided in one place, [PlayReporter]): the log of plays behind "Your
     *  stats" notes it, whether or not plays are reported (as Windows' `tick`); and the server
     *  is told, if the person wants plays reported. Sent now, or held with the time it
     *  counted and sent when the server can next be reached (see AccountGraph.submit): a
     *  play made in a tunnel is not lost. */
    private fun counted(songId: String, from: String) {
        val graph = account ?: return
        if (songId.isEmpty()) return
        // Both from one place, one after the other (see AccountGraph.played).
        graph.played(songId, System.currentTimeMillis(), from, report = this.graph.settings.value.scrobble)
    }

    // ---- The session's callback: the car, the notification, the app's screens ----------------

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Media3 sorts callers into trusted (this app, the system, and anything allowed to
            // read notifications or control media, which is what a car's own screens are, and
            // on most phones Android Auto and the Assistant) and the rest. Of the rest, the
            // known hosts of media apps are let in by package name and signing certificate, so
            // that Android Auto is never turned away for what one phone didn't grant it. Any
            // other app gets no connection: a private library is not for it to browse. See
            // [Access] and [KnownHosts].
            val caller = callerOf(session, controller)
            noteConnection(controller, caller)
            if (caller == Caller.REFUSED) return MediaSession.ConnectionResult.reject()
            // An app the person allowed in Settings gets what a headset gets, and it is
            // narrowed here, for this controller alone: transport and what is playing. No
            // session commands at all, so nothing of the library, and none of the buttons.
            if (caller == Caller.ALLOWED) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(androidx.media3.session.SessionCommands.EMPTY)
                    .setAvailablePlayerCommands(Player.Commands.Builder().addAll(*Access.ALLOWED_PLAYER.toIntArray()).build())
                    .setMediaButtonPreferences(emptyList())
                    .build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE).add(SHUFFLE).add(REPEAT)
            // The app's own commands go to the app's own screens and nobody else: not to
            // Media3's notification controller, though it carries this app's name. (The
            // platform's session, which is what a car sees, takes its buttons and commands
            // from the notification controller's, so those it keeps in full.)
            if (caller == Caller.OWN_INTERFACE) AppCommands.all.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setMediaButtonPreferences(buttons())
                .build()
        }

        /**
         * Media3 copies a standing error ("sign in") to the platform's session, which is
         * what a car reads, only through its own notification controller, and only if that
         * controller is connected at the moment the error is set. With nobody signed in the
         * saved sign-in is read in no time at all, well before that controller has
         * connected: the error was set on the session and never reached the car, which was
         * told nothing until it opened a folder. So when the notification controller does
         * connect, the error is set again. (Taken off first: Media3 passes over an error it
         * takes for the one already there.)
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (told && era == null && session.isMediaNotificationController(controller)) {
                session.setPlaybackException(null)
                session.setPlaybackException(signInNeeded())
            }
        }

        /**
         * Asked before every player command is carried out. What arrives under the
         * notification controller's name may be a tap on the notification or a headset
         * button, or may be an intent any app sent to the exported service, which Media3
         * can't tell apart: so under that name only transport is carried out, whatever the
         * intent asked for (Media3 will turn one into "play this item" if allowed). Not
         * shuffle or repeat either: the notification's own buttons for those, and for like,
         * are told from a forgery by the mark they carry, and are carried out without ever
         * becoming that controller's commands (see OwnButtons).
         */
        @Deprecated("Media3 offers nothing else that is asked per command and per controller")
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            val caller = callerOf(session, controller)
            // ("Play that entry of the queue", by its place, from someone whose screen may
            // still show the places as they were a moment ago, is the player's to act on or
            // not: see QueuePlayer.placeIsSure.)
            if (!Access.mayPlayer(caller, playerCommand)) return SessionError.ERROR_PERMISSION_DENIED
            // The person's press, about to be carried out: the queue is this phone's own
            // from now (see claimQueue).
            if (playerCommand in PRESSES) {
                if (carController(controller) && playerCommand == Player.COMMAND_PLAY_PAUSE) {
                    journalCarEntry = entryOf(exo.currentMediaItem)
                }
                claimQueue()
                // (After the command itself, which is carried out as this returns.)
                main.removeCallbacks(keepAfterPress)
                main.post(keepAfterPress)
            }
            // Play or Pause tapped on the app's own screens took its place at the tap; carried
            // out now (straight after this, on this thread), it takes no other.
            if (caller == Caller.OWN_INTERFACE && playerCommand == Player.COMMAND_PLAY_PAUSE && Choices.arrivedFromTap()) {
                player.placeTakenAtTap = true
                main.post { player.placeTakenAtTap = false }
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            // Unless a folder says otherwise: folders in a grid, songs in a list.
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            graph.diagnostics.note("library opened by ${browser.packageName}" + (params?.let { " (recent: ${yes(it.isRecent)}; offline: ${yes(it.isOffline)}; suggested: ${yes(it.isSuggested)})" } ?: ""))
            val root = CarMedia.item(CarEntry(CarIds.ROOT, getString(R.string.app_name), browsable = true), "")
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
        }

        /**
         * A subscription to one of our folders stands, whoever is signed in. Media3's own
         * answer asks for the folder's item first and drops the subscription if that fails,
         * which with nobody signed in is always: and a car that isn't subscribed is never
         * told to look again, so it sat on "Sign in" after the person had signed in.
         */
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            if (CarIds.parse(parentId) == null) return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            // The system hands a car of the older kind the folder as it subscribes; a Media3
            // browser waits to be told there is something to fetch.
            if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // "Tap to try again", opened: the folder (or search) it stands in for, fetched
            // again; and the car is told that folder has changed, so going back finds it too.
            when (val again = CarIds.retryOf(parentId)) {
                is CarRetry.Folder -> {
                    session.notifyChildrenChanged(again.parentId, Int.MAX_VALUE, params)
                    return children(session, browser, again.parentId, page, pageSize, params)
                }
                is CarRetry.Search -> return answer({ LibraryResult.ofError(signedOut()) }) { era ->
                    try {
                        val found = fromTree(era) { it.search(again.query) }
                        era.searches.put(again.query, found)
                        // Told: every browser that made this search lately (not only the last).
                        for (who in era.searchedBy.of(again.query)) session.notifySearchResultChanged(who, again.query, found.size, null)
                        LibraryResult.ofItemList(ImmutableList.copyOf(slice(found, page, pageSize).map { carItem(it, era) }), params)
                    } catch (e: ApiError) {
                        if (CarTree.unreachable(e)) {
                            LibraryResult.ofItemList(ImmutableList.of(CarMedia.item(tryAgain(CarIds.retrySearch(again.query, retriesMade.incrementAndGet())), "")), params)
                        } else {
                            LibraryResult.ofError(trouble(browser, e))
                        }
                    }
                }
                null -> {}
            }
            askedFor(parentId)
            return children(session, browser, parentId, page, pageSize, params)
        }

        private fun children(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            answer({ LibraryResult.ofError(signedOut()) }) { era ->
                val node = CarIds.parse(parentId) ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                try {
                    // Made into what the car is sent off the main thread too: two hundred
                    // entries are two hundred bundles.
                    val items = fromTree(era) { tree ->
                        tree.children(node) { toldToLookAgain(era, parentId, params) }?.let { ImmutableList.copyOf(slice(it, page, pageSize).map { carItem(it, era) }) }
                    } ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    LibraryResult.ofItemList(items, params)
                } catch (e: ApiError) {
                    // A folder never fetched, and the server out of reach: one entry that says
                    // so and offers to try again, not the car's own "Something went wrong".
                    // (Not when the phone isn't allowed to reach it: trying again can't help,
                    // and the error's button opens the app, where it can be allowed.)
                    if (CarTree.unreachable(e) && !graph.localAccess.blocked.value) {
                        LibraryResult.ofItemList(ImmutableList.of(CarMedia.item(tryAgain(CarIds.retryFolder(parentId, retriesMade.incrementAndGet())), "")), params)
                    } else {
                        LibraryResult.ofError(trouble(browser, e))
                    }
                }
            }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            answer({ LibraryResult.ofError(signedOut()) }) { era ->
                val node = CarIds.parse(mediaId) ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                try {
                    fromTree(era) { it.item(node) }?.let { LibraryResult.ofItem(carItem(it, era), null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } catch (e: ApiError) {
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            answer({
                // A car asking the old way is only ever answered through this (Media3 sends
                // its search result when it is told the result has changed, and at no other
                // time): without it, a search made while signed out is never answered, and
                // the car waits out its own time limit.
                session.notifySearchResultChanged(browser, query, 0, params)
                LibraryResult.ofError(signedOut())
            }) { era ->
                era.searchedBy.made(browser, query)
                val search = graph.recording.search(query, era.account.account.key)
                var count = 0
                try {
                    val found = fromTree(era) { it.search(query) }
                    count = found.size
                    era.searches.put(query, found)
                    session.notifySearchResultChanged(browser, query, found.size, params)
                    LibraryResult.ofVoid()
                } catch (e: ApiError) {
                    if (CarTree.unreachable(e) && !graph.localAccess.blocked.value) {
                        // Out of reach: the one entry that says so, and tries again when tapped.
                        val retry = listOf(tryAgain(CarIds.retrySearch(query, retriesMade.incrementAndGet())))
                        era.searches.put(query, retry)
                        session.notifySearchResultChanged(browser, query, retry.size, params)
                        return@answer LibraryResult.ofVoid()
                    }
                    // A car asking the old way gets its answer only through this: say there's
                    // nothing, rather than leave it waiting, and say why.
                    era.searches.put(query, emptyList())
                    session.notifySearchResultChanged(browser, query, 0, params)
                    LibraryResult.ofError(trouble(browser, e))
                } finally {
                    graph.recording.found(search, count)
                }
            }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            answer({ LibraryResult.ofError(signedOut()) }) { era ->
                try {
                    val found = era.searches.get(query) ?: run {
                        val search = graph.recording.search(query, era.account.account.key)
                        var count = 0
                        try {
                            fromTree(era) { it.search(query) }.also { result ->
                                count = result.size
                                era.searches.put(query, result)
                            }
                        } finally {
                            graph.recording.found(search, count)
                        }
                    }
                    LibraryResult.ofItemList(ImmutableList.copyOf(slice(found, page, pageSize).map { carItem(it, era) }), params)
                } catch (e: ApiError) {
                    if (CarTree.unreachable(e) && !graph.localAccess.blocked.value) {
                        LibraryResult.ofItemList(ImmutableList.of(CarMedia.item(tryAgain(CarIds.retrySearch(query, retriesMade.incrementAndGet())), "")), params)
                    } else {
                        LibraryResult.ofError(trouble(browser, e))
                    }
                }
            }

        /**
         * One item asked for: a song chosen in the car, or a spoken request. Either way a
         * whole list is queued: the song's album or playlist from it, or what was asked for.
         */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> {
            // "Tap to try again": the car is asked to load that folder (or search) again, and
            // nothing about playback changes. Before anything else, so it can't overtake a
            // choice still being looked up.
            mediaItems.singleOrNull()?.mediaId?.let(CarIds::retryOf)?.let { target ->
                tryAgainNow(target)
                return com.google.common.util.concurrent.Futures.immediateFailedFuture(UnsupportedOperationException("Not something to play"))
            }
            // A start: it takes its ticket now, before anything is looked up (see
            // QueuePlayer.beginStart), and is dropped if a newer one has begun by the time it
            // is ready.
            val request = requests.begin(controller, player.beginStart())
            // And the service is in the foreground now, at the request's arrival, whoever
            // sent it. Android gives a command from a car a short allowance to enter the
            // foreground, and the sound goes only to a service that is in it: what follows
            // (who is signed in, the list, a search of up to seven seconds, the queue) could
            // use the allowance up, and used to be done first.
            holdForeground(why = if (mediaItems.singleOrNull()?.requestMetadata?.searchQuery != null) "for a spoken request" else "for a choice")
            return setMediaItems(request, controller, mediaItems, startIndex, startPositionMs)
        }

        private fun setMediaItems(request: PlayRequests.Request<MediaSession.ControllerInfo>, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> =
            answer(request, { throw refuse(controller, signedOut()) }) { era ->
                val mine = request.start ?: throw UnsupportedOperationException("Not a start")
                if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.tag is QueuePlayer.SongTag }) {
                    // Our own items, handed back: play them as they are.
                    return@answer MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                }
                val tree = era.tree
                val asked = mediaItems.singleOrNull() ?: throw refuse(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play)))
                val words = asked.requestMetadata.searchQuery
                try {
                    if (words != null) return@answer inTime { voice(mine, era, controller, words, asked.requestMetadata.extras) }
                    val list = when (val chosen = CarIds.parse(asked.mediaId)) {
                        is CarNode.Track -> fromTree(era) { it.play(chosen) }
                        // "Shuffle", at the top of a list: the whole list, shuffled.
                        is CarNode.Shuffle -> fromTree(era) { it.shuffled(chosen.list) }
                        else -> throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.nothing_to_play)))
                    } ?: throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.couldnt_play_song)))
                    staged(mine, era, list, carController(controller))
                } catch (e: ApiError) {
                    throw refuse(controller, trouble(controller, e))
                }
            }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            answer<List<MediaItem>>({ throw refuse(controller, signedOut()) }) { era ->
                val tree = era.tree
                mediaItems.mapNotNull { item ->
                    if (item.localConfiguration?.tag is QueuePlayer.SongTag) return@mapNotNull item
                    val track = CarIds.parse(item.mediaId) as? CarNode.Track
                    val song = try {
                        // (A song downloaded is had from the downloads' own record, not the server.)
                        track?.let { tree.play(it) }?.let { it.songs[it.start] } ?: era.account.catalogue.entry(item.mediaId)?.song ?: era.account.library.song(item.mediaId)
                    } catch (_: ApiError) {
                        null
                    } ?: return@mapNotNull null
                    MediaItem.Builder().setMediaId(song.id).setUri(songUri(song.id)).setTag(QueuePlayer.SongTag(song, 0)).setMediaMetadata(CarMedia.metadata(song, era.key)).build()
                }
            }

        /**
         * "Play", with nothing in the player: a headset or car button, or the system asking
         * what it would resume. The queue as it was left, and nothing else: read from the
         * phone, waited for only briefly, with no word to the server. (Looking for something
         * to play when there is nothing to carry on with is for a spoken "play music", which
         * can wait; a button that started the app cold can't.)
         *
         * With `isForPlayback` false the system only wants to know what would play, to draw
         * its "resume" card: it is told, and nothing is loaded or started.
         */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaItemsWithStartPosition> {
            // Play: in the foreground at once, before the saved sign-in and the saved queue
            // are waited for (three seconds each at most). Not a start: it carries on, and
            // gives way to any.
            val request = if (isForPlayback) requests.begin(controller, start = null) else null
            if (request != null) holdForeground(why = "for Play with nothing loaded")
            // A Play: its place in the order of choices is taken as it arrives; if anything
            // takes a later one while the saved queue is waited for (a Stop, a choice), it is
            // dropped (order of choices).
            val place = if (isForPlayback) Choices.take() else null
            return scope.future {
                try {
                    resumption(controller, isForPlayback, place)
                } finally {
                    request?.let(::requestEnded)
                }
            }
        }

        private suspend fun resumption(controller: MediaSession.ControllerInfo, isForPlayback: Boolean, place: Long?): MediaItemsWithStartPosition {
            val era = withTimeoutOrNull(RESUME_WAIT_MILLIS) { eraOrNull() }
            val ready = era != null && withTimeoutOrNull(RESUME_WAIT_MILLIS) { era.restoring.await() } != null && era === this@PlaybackService.era
            resumptionWaited()
            if (place != null && !Choices.stands(place)) {
                main.post { if (holding) standDown() }
                throw UnsupportedOperationException("Overtaken by a newer choice")
            }
            if (!isForPlayback) {
                val item = (if (ready) player.currentItem() else null) ?: throw UnsupportedOperationException("Nothing to resume")
                return MediaItemsWithStartPosition(listOf(item), 0, exo.currentPosition)
            }
            val (items, start) = (if (ready) player.stageCurrent() else null) ?: run {
                // Said in words to whoever asked, unless what's wrong is that nobody is
                // signed in, which the car is already being told.
                if (era != null) session.sendError(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play)))
                main.post { if (holding) standDown() }
                throw UnsupportedOperationException("Nothing to resume")
            }
            return MediaItemsWithStartPosition(items, start, exo.currentPosition)
        }

        /** Media3 has carried out everything a controller sent in one go (see [PlayRequests]). */
        override fun onPlayerInteractionFinished(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, playerCommands: Player.Commands) {
            requests.finished(controllerInfo)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            requests.gone(controller)
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            // Authorised here, where it is carried out, and not only by what the controller
            // was granted when it connected.
            val caller = callerOf(session, controller)
            if (!Access.mayCustom(caller, customCommand.customAction)) {
                return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            // (The app's own commands for the queue, and shuffle, are the person's doing.)
            if (customCommand.customAction in Access.APP_ONLY || customCommand.customAction == SHUFFLE.customAction) claimQueue()
            val done = when (customCommand.customAction) {
                in Access.BUTTONS -> press(customCommand.customAction)
                AppActions.PLAY -> {
                    // A choice made on the phone: anything still being looked up for the car
                    // has been overtaken. In the foreground first, as for any request that
                    // is to end in playing (the app is in front, so Android allows it).
                    holdForeground(why = "for a list chosen in the app")
                    // The list as the screen drew it, left for the player (see Drawn).
                    val drawn = Drawn.take(AppCommands.drawnOf(args))
                    // Its place in the order of choices is the tap's, carried with it: if
                    // anything has taken a later place since (the sound taken away, a choice
                    // in the car, offline mode switched on), it is dropped (order of choices).
                    val place = AppCommands.placeOf(args, player::beginStart)
                    if (AppCommands.byPage(args)) {
                        playPage(place, args, drawn)
                    } else {
                        // A short list, sent whole: the same arrangement and song as by page.
                        started(AppCommands.queueFor(player, args, drawn?.songs ?: AppCommands.songsOf(args), AppCommands.shuffleOf(args, ::shuffleFor), playsOffline(), ::addedDates, drawn), args.getString("source").orEmpty())
                            ?.let { player.playFor(place, it) } ?: false
                    }
                }
                else -> AppCommands.handle(player, customCommand, args, ::shuffleFor)
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                SessionResult(if (done) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED),
            )
        }
    }

    /** Carries out one of the buttons on the car's player and the notification: like,
     *  shuffle, repeat. False if it isn't one. */
    private fun press(action: String): Boolean {
        when (action) {
            LIKE.customAction -> {
                player.queue.current()?.let { song -> account?.likes?.toggle(song) }
                updateButtons()
            }
            SHUFFLE.customAction -> player.setShuffle(!player.queue.shuffle)
            REPEAT.customAction -> player.cycleRepeat()
            else -> return false
        }
        return true
    }

    /** Held here, in the tests that hold a named start between the tap and its list. */
    @androidx.annotation.VisibleForTesting
    internal var beforeNamedStart: suspend () -> Unit = {}

    /**
     * Starts a list the app's screens named rather than sent (a long album, playlist or Liked
     * songs, the Songs page), off the main thread: the list the screen drew, which it left
     * for the player (`drawn`); failing that, its songs as the library keeps them, where the
     * screen read them. Dropped if something else is chosen meanwhile.
     */
    private fun playPage(mine: Long, args: Bundle, drawn: DrawnList?): Boolean {
        val era = era ?: return false
        val source = args.getString("source").orEmpty()
        era.scope.launch {
            beforeNamedStart()
            val shuffle = AppCommands.shuffleOf(args, ::shuffleFor)
            // Read, and made into a queue, off the main thread: it is thousands of songs.
            val plays = playsOffline()
            val result = withContext(Dispatchers.Default) {
                // A list of the downloads is read from their own record, like the car's
                // Downloads tab; any other from the library's kept copy.
                val songs = drawn?.songs ?: era.account.downloads?.let { org.medianexus.music.downloads.DownloadedViews.songsFor(it.state.value, source) }
                    ?: era.account.library.keptSongs(source)
                // The arrangement the screen showed, from the song tapped (see StartPlace.pick).
                if (songs.isNullOrEmpty()) AppCommands.Started.Empty else AppCommands.queueFor(player, args, songs, shuffle, plays, ::addedDates, drawn)
            }
            // Something else has been chosen while this was being read (or the account has
            // gone, which overtakes every start): that stands.
            if (!player.stands(mine) || era !== this@PlaybackService.era) return@launch
            // (Asks once more, at the moment of playing.)
            started(result, source)?.let { player.playFor(mine, it) }
        }
        return true
    }

    /**
     * The queue a PLAY came to, or null if it came to nothing, in which case what was playing
     * carries on and the person is told why their tap did nothing: offline, nothing of it
     * plays; the song tapped isn't in the list as it was shown any more (it is never swapped
     * for another); or the list has nothing in it any more (emptied since the screen drew it,
     * or the phone has cleared its copy away).
     */
    private fun started(result: AppCommands.Started, source: String): Queue? = when (result) {
        is AppCommands.Started.Ready -> result.queue
        AppCommands.Started.NothingOffline -> null.also { graph.toasts.show(getString(R.string.cant_play_offline)) }
        AppCommands.Started.Gone -> null.also { graph.toasts.show(getString(R.string.song_not_in_list_now)) }
        AppCommands.Started.Empty -> null.also {
            graph.toasts.show(getString(R.string.list_is_empty_now))
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "nothing kept to start $source from")
        }
    }

    /** Who a controller is (see [Access]). */
    private fun callerOf(session: MediaSession, controller: MediaSession.ControllerInfo): Caller {
        val own = controller.uid == android.os.Process.myUid()
        // Media3's "trusted" is taken only from a caller whose name is its own: on
        // Android 12 nobody has checked the name a platform controller gives, and
        // "trusted" can rest on that name alone (see Callers.namedTruly).
        val trusted = controller.isTrusted && namedTruly(controller)
        // Asked of the system only for a caller that has no other standing: its package
        // name and its signing certificate, against Google's list (see KnownHosts).
        val knownHost = !own && !controller.isTrusted && graph.callers.knownHost(controller.packageName, controller.uid)
        return Access.caller(
            trusted = trusted,
            knownHost = knownHost,
            // Asked only of a caller with no other standing: whether the person has allowed
            // the app the system says this uid is, as it is signed now (see ControlApps).
            allowed = !own && !trusted && !knownHost && graph.controlApps.allowed(controller.packageName, controller.uid),
            sameUid = own,
            ownPackage = controller.isPackageNameVerified && controller.packageName == packageName,
            legacy = controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            notification = session.isMediaNotificationController(controller),
        )
    }

    /** Controllers that are connected and would now be refused are left with nothing. */
    private fun cutThoseNoLongerAllowed() {
        for (controller in session.connectedControllers) {
            if (callerOf(session, controller) != Caller.REFUSED) continue
            session.setAvailableCommands(controller, androidx.media3.session.SessionCommands.EMPTY, Player.Commands.EMPTY)
            graph.diagnostics.note("connection from ${controller.packageName}: cut, now that it is no longer allowed in Settings")
        }
    }

    private fun namedTruly(controller: MediaSession.ControllerInfo): Boolean =
        graph.callers.namedTruly(controller.packageName, controller.uid, checkedBySystem = controller.isPackageNameVerified)

    /** Notes who asked to connect and what became of it (see [org.medianexus.music.Diagnostics]):
     *  on a real phone in a real car this is the only account there is of whether Android
     *  Auto was let in, and why. */
    private fun noteConnection(controller: MediaSession.ControllerInfo, caller: Caller) {
        val legacy = controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
        val own = controller.uid == android.os.Process.myUid()
        val known = !own && graph.callers.knownHost(controller.packageName, controller.uid)
        val how = if (legacy) "Android's own media session and browser" else "Media3 (version ${controller.controllerVersion})"
        val outcome = when (caller) {
            Caller.REFUSED -> "refused"
            Caller.TRUSTED -> "let in"
            Caller.ALLOWED -> "let in as an app you allowed in Settings (transport and what is playing only)"
            Caller.NOTIFICATION -> "let in as the notification's own"
            Caller.OWN_INTERFACE -> "let in as the app's own screens"
        }
        if (!own) graph.recording.diagnostics.note(when {
            caller == Caller.REFUSED && carController(controller) -> DiagnosticNote.AUTO_REFUSED
            caller == Caller.REFUSED -> DiagnosticNote.CONTROLLER_REFUSED
            carController(controller) -> DiagnosticNote.AUTO_ADMITTED
            else -> DiagnosticNote.CONTROLLER_ADMITTED
        })
        graph.diagnostics.note(
            "connection from ${controller.packageName} by $how: $outcome. Android trusts it: ${yes(controller.isTrusted)}; known host: ${yes(known)}" +
                // (Said only when it is why: a caller on an older phone giving a name the
                // system says is not that caller's.)
                if (controller.isTrusted && !namedTruly(controller)) "; its name is not its own" else "",
            now = caller == Caller.REFUSED,
        )
        // Who a refused caller is, by its uid, on a line of its own: the packages Android
        // names for it and what they are signed with. It is remembered for Settings, where
        // the person can allow it; or, if Android names nothing, said to be unnameable.
        if (caller == Caller.REFUSED) graph.controlApps.refused(controller.packageName, controller.uid)
    }

    private fun yes(on: Boolean) = if (on) "yes" else "no"

    /** The account signed in, once the saved sign-in has been read; null if nobody is. */
    private suspend fun eraOrNull(): Era? {
        val now = graph.accounts.restored() ?: return null
        // The collector that takes the account on may not have run yet.
        if (era?.account !== now) signedIn(graph.session.state.value, now)
        return era?.takeIf { it.account === now }
    }

    /**
     * Answers something asked of the session, for the account signed in. The work belongs
     * to that account's generation: if the account goes while it is being done, the work is
     * dropped, and nothing of theirs is handed on afterwards. `signedOut` is the answer when
     * nobody is signed in; if someone else has signed in meanwhile, the question is answered
     * again for them (see [ForAccount]). One that is overtaken by a change of account every
     * time it is asked is given up without an answer (the future is cancelled, and Media3
     * then reports nothing for it): not "sign in", and not a success, either of which Media3
     * would copy to the car's session as how things stand.
     */
    private fun <T> answer(signedOut: () -> T, superseded: () -> T = { throw kotlinx.coroutines.CancellationException("Overtaken by a change of account") }, work: suspend (Era) -> T): ListenableFuture<T> = scope.future {
        ForAccount.answer(
            now = ::eraOrNull,
            isNow = { it === this@PlaybackService.era },
            signedOut = signedOut,
            superseded = superseded,
        ) { era -> era.scope.async { work(era) }.await() }
    }

    /** As [answer], for a request that is meant to end in playing: it is being worked on
     *  until this has its answer, whatever the answer is. */
    private fun <T> answer(request: PlayRequests.Request<MediaSession.ControllerInfo>, signedOut: () -> T, work: suspend (Era) -> T): ListenableFuture<T> = scope.future {
        val superseded: () -> T = { throw UnsupportedOperationException("Overtaken by a change of account") }
        try {
            ForAccount.answer(
                now = ::eraOrNull,
                isNow = { it === this@PlaybackService.era },
                signedOut = signedOut,
                superseded = superseded,
            ) { era -> era.scope.async { work(era) }.await() }
        } catch (e: Throwable) {
            // It came to nothing: overtaken, nothing found, the server out of reach. A car's
            // request ends there (Media3 sends nothing after a hand-over that failed). A
            // Media3 controller's doesn't: its "make ready" and "play" are commands of their
            // own, already on their way (the phone's voice request sends all three at once),
            // and would start whatever is in the player. They are this request's, and do
            // nothing.
            if (request.from.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) requests.drop(request.from)
            throw e
        } finally {
            requestEnded(request)
        }
    }

    /**
     * Runs something the car is waiting on, giving up after a few seconds as if the server
     * couldn't be reached: a car waits about ten seconds before showing an error of its own,
     * and one in our words is better.
     */
    private suspend fun <T> inTime(block: suspend () -> T): T = try {
        withTimeout(CAR_WAIT_MILLIS) { block() }
    } catch (_: TimeoutCancellationException) {
        throw ApiError.Network("(It didn't answer in time.)")
    }

    /**
     * Something worked out from the library for the car: a folder's entries, a search, the
     * list a chosen song plays. Off the main thread: laying out, sorting and weighing a
     * 20,000-song playlist or three thousand albums is work for every one of them, and done
     * on the main thread it held up the player, and could not be given up on either (a time
     * limit only takes effect where the work pauses, and this work doesn't). Given up after a
     * few seconds, as [inTime], whatever the work is doing; it is then left to stop by itself.
     */
    private suspend fun <T> fromTree(era: Era, work: suspend (CarTree) -> T): T = try {
        OffMain.within(era.scope, CAR_WAIT_MILLIS) { work(era.tree) }
    } catch (_: TimeoutCancellationException) {
        throw ApiError.Network("(It didn't answer in time.)")
    }

    /** A folder's kept copy turned out to differ from the server's: the car is told to ask
     *  for it again. (Called from wherever the folder was being worked out.) */
    private fun toldToLookAgain(era: Era, parentId: String, params: LibraryParams?) {
        scope.launch { if (era === this@PlaybackService.era) session.notifyChildrenChanged(parentId, Int.MAX_VALUE, params) }
    }

    /** "Tap to try again" entries made so far: each has an id of its own. */
    private val retriesMade = java.util.concurrent.atomic.AtomicLong()

    /**
     * The entry a folder or search shows while the server is out of reach. A folder, not
     * something to play: a car shows its player when something playable is chosen, which here
     * would be an empty one. Opened, it is the folder fetched again (see onGetChildren). Drawn
     * as a row, whatever its list is.
     */
    private fun tryAgain(id: String) = CarEntry(id, getString(R.string.cant_reach_server), getString(R.string.tap_to_try_again), icon = R.drawable.ic_refresh_cw, browsable = true, style = CarStyle.LIST)

    /** A "Tap to try again" was tapped: the car is told the folder, or the search, has
     *  changed, and asks for it again (fetching it afresh, now). */
    private fun tryAgainNow(target: CarRetry) {
        val era = era ?: return
        when (target) {
            is CarRetry.Folder -> session.notifyChildrenChanged(target.parentId, Int.MAX_VALUE, null)
            is CarRetry.Search -> {
                era.searches.forget(target.query)
                for (who in era.searchedBy.of(target.query)) session.notifySearchResultChanged(who, target.query, 1, null)
            }
        }
    }

    private fun signedOut() = SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, getString(R.string.car_sign_in), signInButton())

    /**
     * What to tell the car when the server couldn't help: one of a few fixed sentences, by
     * the kind of failure ([CarTrouble]). Never the error's own message: for a refusal that
     * is "The server reported: …" with the server's words in it, which a car shows on its
     * screen and every app with a controller can read. Those words are the person's, and
     * are kept for the phone's own screens ([org.medianexus.music.Refusals]).
     */
    private fun trouble(controller: MediaSession.ControllerInfo, error: ApiError): SessionError {
        val kind = CarTrouble.of(error, graph.localAccess.blocked.value)
        if (error is ApiError.Server) graph.refusals.said(error.message.orEmpty())
        val message = getString(
            when (kind) {
                CarTrouble.LOCAL_NETWORK -> R.string.car_local_network
                CarTrouble.UNREACHABLE -> R.string.car_unreachable
                CarTrouble.SIGN_IN -> R.string.car_sign_in_refused
                CarTrouble.NOT_ALLOWED -> R.string.car_not_allowed
                CarTrouble.NOT_FOUND -> R.string.car_not_found
                CarTrouble.REFUSED -> R.string.car_server_refused
                CarTrouble.NOT_UNDERSTOOD -> R.string.car_not_understood
            },
        )
        // What opening the app can put right comes with a button that opens it.
        val sessionError = when (kind) {
            CarTrouble.LOCAL_NETWORK, CarTrouble.SIGN_IN -> SessionError(SessionError.ERROR_IO, message, openAppButton())
            else -> SessionError(SessionError.ERROR_IO, message)
        }
        // Said on the car's screen too, not only in the list that failed.
        session.sendError(controller, sessionError)
        return sessionError
    }

    private fun refuse(controller: MediaSession.ControllerInfo, error: SessionError): Exception {
        if (error.code != SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED) session.sendError(controller, error)
        return UnsupportedOperationException(error.message)
    }

    /**
     * Whether a list plays shuffled, as on Windows: each list remembers (off until it's been
     * shuffled); songs that aren't from a list leave shuffle as it is.
     */
    private fun shuffleFor(source: String): Boolean =
        if (source.isEmpty()) player.queue.shuffle else graph.settings.value.lists[source]?.shuffle ?: false

    /** The person chose how the list `source` plays: it remembers, from now. */
    private fun rememberShuffle(source: String, shuffle: Boolean) {
        if (source.isEmpty()) return
        Remembered.listChoice(graph.settings.value, shuffle, source)?.let { graph.choose(it) }
    }

    /**
     * Makes a list into a queue and hands it to the session, for the start numbered `mine`
     * and the account it began under. Whether that start still stands is asked here, at the
     * moment of handing over, after the queue has been made (which, off the main thread, is
     * when something else can be chosen): one that has been overtaken hands over nothing and
     * disturbs nothing that a newer start has handed over.
     */
    private suspend fun staged(mine: Long, era: Era, list: PlayList, car: Boolean = false): MediaItemsWithStartPosition {
        val shuffle = list.shuffle ?: shuffleFor(list.source)
        // "Shuffle" at the top of a list is the person saying how that list plays: the list
        // remembers, as on Windows (with the time, so other devices take it as the newer).
        if (list.shuffleChosen && list.shuffle != null) rememberShuffle(list.source, list.shuffle)
        val plays = playsOffline()
        // Made into a queue off the main thread: a list can be thousands of songs.
        val next = withContext(Dispatchers.Default) {
            // Offline, only what is on the phone, each at its place in the whole list. A song
            // chosen that isn't starts nothing, not its neighbour.
            val cut = plays?.let { playableFrom(list.songs, list.start, list.chosen, it) }
            if (plays != null && cut == null) return@withContext null
            // A list with a source is that page's whole list, so each song's place is its origin.
            val origins = if (list.source.isNotEmpty()) cut?.origins ?: list.songs.indices.toList() else null
            player.prepare(cut?.songs ?: list.songs, cut?.start ?: list.start, list.context, list.source, origins, list.songs.map { it.id }, shuffle, list.anyStart)
        } ?: throw UnsupportedOperationException(getString(R.string.cant_play_offline)).also {
            session.controllerForCurrentRequest?.let { who -> session.sendError(who, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.cant_play_offline))) }
        }
        val (items, start) = (if (era === this.era) player.stage(next, mine) else null)
            ?: throw UnsupportedOperationException("Overtaken by a newer choice")
        if (car) journalCarEntry = entryOf(items.getOrNull(start))
        return MediaItemsWithStartPosition(items, start, 0)
    }

    private suspend fun resume(mine: Long, era: Era, controller: MediaSession.ControllerInfo): MediaItemsWithStartPosition {
        val tree = era.tree
        // The saved queue is put back when the account is taken on; wait for that rather
        // than read the file a second time.
        era.restoring.await()
        if (this.era !== era) throw UnsupportedOperationException("The account this was for has gone")
        // ("Carry on" from a request that something newer has overtaken carries nothing on.)
        if (!player.stands(mine)) throw UnsupportedOperationException("Overtaken by a newer choice")
        player.stageCurrent()?.let { (items, start) ->
            if (carController(controller)) journalCarEntry = entryOf(items.getOrNull(start))
            return MediaItemsWithStartPosition(items, start, exo.currentPosition)
        }
        val liked = try {
            fromTree(era) { it.shuffledLiked() }
        } catch (e: ApiError) {
            throw refuse(controller, trouble(controller, e))
        }
        return staged(mine, era, liked ?: throw refuse(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play))))
    }

    /** A spoken request: "play X on Nexus Music". */
    private suspend fun voice(mine: Long, era: Era, controller: MediaSession.ControllerInfo, words: String, extras: Bundle?): MediaItemsWithStartPosition {
        val tree = era.tree
        // Each is what a person said, however it got here: text, and no longer than a name.
        fun said(key: String) = runCatching { extras?.getString(key) }.getOrNull()?.let(VoiceAsk::tidy).orEmpty()
        val request = VoiceRequest(
            query = VoiceAsk.tidy(words),
            focus = VoiceRequest.focusOf(said(MediaStore.EXTRA_MEDIA_FOCUS).ifEmpty { null }),
            artist = said(MediaStore.EXTRA_MEDIA_ARTIST),
            album = said(MediaStore.EXTRA_MEDIA_ALBUM),
            title = said(MediaStore.EXTRA_MEDIA_TITLE),
            playlist = said(EXTRA_MEDIA_PLAYLIST),
            genre = said(EXTRA_MEDIA_GENRE),
        )
        if (request.unnamed) return resume(mine, era, controller)
        val search = graph.recording.search(request.query, era.account.account.key)
        var count = 0
        val list = try {
            withContext(Dispatchers.Default) { tree.forVoice(request) }.also { count = it?.songs?.size ?: 0 }
        } finally {
            graph.recording.found(search, count)
        }
            // A fixed sentence: what was asked for is the person's words, and the session's
            // error is read by every app with a controller (as a server's refusal is never put
            // there either).
            ?: throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.couldnt_find_that)))
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "voice: $request -> ${list.context} (${list.songs.size} songs)")
        return staged(mine, era, list, carController(controller))
    }

    /**
     * An entry of the car's tree as the car is given it. Offline, a cover that isn't on the
     * phone can't be fetched: the entry goes without one (the car draws its icon) rather than
     * with an address that names no picture.
     */
    private fun carItem(entry: CarEntry, era: Era): MediaItem {
        // Offline, or not yet known whether the server is there (the first seconds of a cold
        // start): a cover that isn't on the phone can't be fetched, or may not be, so the
        // entry goes without one rather than with an address that names no picture. (When it
        // turns out the server is there, the car is told to look again: see below.)
        val unsure = graph.watch.state.value.let { it.offline || !it.sure }
        val shown = if (entry.cover.isNotEmpty() && unsure && era.account.covers.kept(entry.cover) == null) entry.copy(cover = "") else entry
        return CarMedia.item(shown, era.key)
    }

    /** Offline (by hand, or the server out of reach), which songs a list started now may
     *  hold: only those on the phone. Null when anything may. */
    private fun playsOffline(): ((Song) -> Boolean)? {
        if (!graph.watch.offline) return null
        val availability = era?.account?.availability ?: return null
        return availability::plays
    }

    // ---- Fetching ahead -------------------------------------------------------------------

    /**
     * Has the next few songs of the queue fetched ahead (see [FetchAhead]), or stops: as many
     * as Settings says on Wi-Fi, the next one only on mobile data, none with Android's Data
     * Saver on, none offline, none when nothing is kept, none while paused.
     */
    /** Streaming quality has changed: the song playing is opened again where it is, at the
     *  new quality (see [Recovery.reopensForQuality]); what is fetched ahead is fetched in the
     *  new form. Stopping and making ready again keeps the queue and the place in the song,
     *  and whether it is playing. */
    private fun qualityChanged() {
        movedEntries.clear()
        aheadNow()
        val song = player.queue.current() ?: return
        // (From its download as the player has it open; if it isn't open yet, as it would be.)
        val downloaded = player.queue.currentUid()?.let { reading[it] }?.let { it is SongCopy.Download } ?: (era?.account?.availability?.isDownloaded(song.id) == true)
        if (!Recovery.reopensForQuality(exo.playbackState, downloaded, song.radioUrl.isNotEmpty(), waitingFor != null)) return
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "streaming quality changed: the song playing is opened again at ${graph.quality.limit()} kbps")
        exo.stop()
        exo.prepare()
    }

    /** The form a song would be asked of the server in now (what fetching ahead would fetch
     *  it in: see [FetchAhead]); null if it isn't known. On fetching ahead's thread. */
    private fun formNow(songId: String): StreamForm? {
        val era = current ?: return null
        val song = player.songFor(songId) ?: return null
        val suffix = song.suffix.lowercase()
        val native = (suffix.isEmpty() || suffix in NATIVE_SUFFIXES) && songId !in era.needsConverting && !era.account.catalogue.convertsFor(songId, song.size)
        return graph.quality.formOf(native, suffix, song.bitRate)
    }

    private fun aheadNow() {
        if (!::ahead.isInitialized) return
        val era = era
        val settings = graph.settings.value
        val count = when {
            era == null || !exo.playWhenReady || settings.cacheLimitMb <= 0 || graph.watch.offline -> 0
            else -> {
                val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
                val saver = connectivity?.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                when {
                    saver -> 0
                    connectivity?.isActiveNetworkMetered == true -> minOf(settings.cacheAhead, FetchAhead.ON_MOBILE_DATA)
                    else -> settings.cacheAhead
                }
            }
        }
        val availability = era?.account?.availability
        ahead.want(if (availability == null) emptyList() else FetchAhead.upcoming(player.queue, count) { availability.isDownloaded(it.id) || availability.isKept(it) })
    }

    /** The songs the player holds, for the sound processor's fallback levelling values. */
    private fun songsHeld(): List<org.medianexus.music.net.Song> =
        List(exo.mediaItemCount) { exo.getMediaItemAt(it) }.mapNotNull { (it.localConfiguration?.tag as? QueuePlayer.SongTag)?.song }

    /** The lyrics of the song playing and the next few are fetched ahead (see LyricsBook), as
     *  sparingly as songs are: five on Wi-Fi, the next one on mobile data, none with Data
     *  Saver on or offline (Windows fetches the next five). */
    private fun lyricsAhead() {
        val account = era?.account ?: return
        val now = player.queue.current() ?: return
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        // The network is looked at before each request, not once for the batch: place 0 is
        // the song playing, 1 the next.
        account.lyrics.ahead(listOf(now.id) + FetchAhead.upcoming(player.queue, 5) { false }) { place ->
            when {
                graph.watch.offline -> false
                connectivity?.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> false
                connectivity?.isActiveNetworkMetered == true -> place <= FetchAhead.ON_MOBILE_DATA
                else -> true
            }
        }
    }

    // ---- A call in progress -----------------------------------------------------------------

    /** Play was asked for, and refused the sound because of a call: it plays when the call
     *  ends, if nothing has taken a later place in the order of choices than this (a Pause, a
     *  Stop, a choice: order of choices). Null when nothing is waiting. */
    private var waitingForCall: Long? = null

    /** Whether a phone call (or a call in another app) is ringing or in progress, by the
     *  system's word: its audio mode. */
    private fun inCall(): Boolean {
        val mode = getSystemService(android.media.AudioManager::class.java)?.mode ?: return false
        return mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            mode == android.media.AudioManager.MODE_RINGTONE || mode == android.media.AudioManager.MODE_CALL_SCREENING ||
            (Build.VERSION.SDK_INT >= 33 && mode == android.media.AudioManager.MODE_CALL_REDIRECT) || (Build.VERSION.SDK_INT >= 33 && mode == android.media.AudioManager.MODE_COMMUNICATION_REDIRECT)
    }

    @androidx.annotation.VisibleForTesting
    internal fun waitForCall() {
        // The place as it stands once the refusal itself (the sound taken away, which is in
        // the order) has taken its own: after this event's other listeners have run.
        main.post { waitingForCall = Choices.latest }
    }

    /** Android says when the audio mode changes: that is when a Play waiting for a call to
     *  end is looked at again. */
    private fun listenForCalls() {
        runCatching {
            getSystemService(android.media.AudioManager::class.java)?.addOnModeChangedListener(androidx.core.content.ContextCompat.getMainExecutor(this)) { if (waitingForCall != null && !inCall()) callEnded() }
        }
    }

    /** The call has ended, and Play was asked for during it: it plays now, if what was asked
     *  for is still what is in the player (nothing else has been chosen, nobody paused). */
    @androidx.annotation.VisibleForTesting
    internal fun callEnded() {
        val place = waitingForCall ?: return
        waitingForCall = null
        if (!Choices.stands(place) || player.queue.isEmpty || exo.playWhenReady) return
        graph.diagnostics.note("audio focus: the call ended; playing as was asked")
        player.play()
    }

    private fun <T> slice(all: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return all
        val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
        return all.subList(from, to)
    }

    companion object {
        /** What the device linking of earlier builds marked a saved queue with when it handed it
         *  to another device. */
        private const val HANDED_BY_LINKING = "handed to another device"

        /**
         * The player commands that are the person doing something to the queue or to
         * playing it: transport, choosing what plays, changing the list, and shuffle (which
         * rearranges it). Not the volume, the speed, repeat, or anything that only reads.
         */
        internal val PRESSES: Set<Int> = (Access.TRANSPORT - Player.COMMAND_PREPARE) + setOf(
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SET_SHUFFLE_MODE,
        )

        private const val TAG = "NexusPlayback"

        val LIKE = SessionCommand("org.medianexus.music.LIKE", Bundle.EMPTY)
        val SHUFFLE = SessionCommand("org.medianexus.music.SHUFFLE", Bundle.EMPTY)
        val REPEAT = SessionCommand("org.medianexus.music.REPEAT", Bundle.EMPTY)

        /** In the session's extras (see `publishExtras`): how many songs straight after the
         *  current one are in "Next in queue"; how many are to come in all; the list the queue came from; the place in it
         *  of the song playing (-1 for none); whether the server is converting that song. */
        const val EXTRA_QUEUED_AHEAD = "org.medianexus.music.QUEUED_AHEAD"
        const val EXTRA_AHEAD = "org.medianexus.music.AHEAD"
        const val EXTRA_SOURCE = "org.medianexus.music.SOURCE"
        const val EXTRA_ORIGIN = "org.medianexus.music.ORIGIN"
        const val EXTRA_CONVERTED = "org.medianexus.music.CONVERTED"

        /** The bit rate the song playing was converted to, in the copy it is being played from
         *  (0: the file as it is; absent or -1: not opened yet). */
        const val EXTRA_PLAYING_KBPS = "org.medianexus.music.PLAYING_KBPS"
        const val EXTRA_ENTRY = "org.medianexus.music.PLAYING_ENTRY"
        /** In the session's extras: the saved queue has been read and put back, so what the
         *  player says is playing (nothing, perhaps) is so, not merely not known yet. */
        const val EXTRA_SETTLED = "org.medianexus.music.SETTLED"
        const val EXTRA_SONG = "org.medianexus.music.SONG"
        /** On a playback error: it is the lost connection to the server. */
        const val EXTRA_LOST = "org.medianexus.music.LOST"

        /** The action of the start intents Media3 makes for a notification's custom buttons
         *  (`CustomCommandPendingIntentBuilder.ACTION_CUSTOM`, which isn't public). */
        private const val MEDIA3_BUTTON_ACTION = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"

        /** The keys a media button intent can carry that a player answers to. */
        private val MEDIA_KEYS = mapOf(
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY to "MEDIA_PLAY",
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE to "MEDIA_PAUSE",
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "MEDIA_PLAY_PAUSE",
            android.view.KeyEvent.KEYCODE_HEADSETHOOK to "HEADSETHOOK",
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT to "MEDIA_NEXT",
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS to "MEDIA_PREVIOUS",
            android.view.KeyEvent.KEYCODE_MEDIA_STOP to "MEDIA_STOP",
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "MEDIA_FAST_FORWARD",
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND to "MEDIA_REWIND",
            android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to "MEDIA_SKIP_FORWARD",
            android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to "MEDIA_SKIP_BACKWARD",
        )

        /** The extras the Assistant names a playlist and a genre with (MediaStore has no
         *  constants for these two). */
        private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
        private const val EXTRA_MEDIA_GENRE = "android.intent.extra.genre"

        /** How often to try again once the player has given up waiting for the server. */
        private const val SERVER_WAIT_MILLIS = 15_000L
        private const val TICK_MILLIS = 1_000L
        private const val CAR_WAIT_MILLIS = 7_000L

        /** Folders the car asked for that are remembered, to tell it to look again. */
        private const val MOST_ASKED = 32

        /** How long "play" with nothing loaded waits for the saved sign-in, and then for the
         *  saved queue, to be read from the phone. */
        private const val RESUME_WAIT_MILLIS = 3_000L

        /** How long the foreground is held for a button's start with nothing playing yet
         *  (the saved sign-in and queue being read, the player getting ready) before the
         *  start is given up as having nothing to play. From the first button, once. */
        private const val HOLD_MILLIS = 7_000L

        /** How long after playing begins the audio track is given to start moving before it
         *  is noted that it has not. */
        private const val HEARD_WITHIN_MILLIS = 5_000L

        /** A pause for want of audio focus this soon after Play was the sound being refused,
         *  not another app starting up. */
        private const val FOCUS_REFUSED_MILLIS = 2_000L

        /** The song id in a kept-song key ("song:<id>:<rate>": see `cacheKey`). */
        fun songIdOfKey(key: String): String = key.removePrefix("song:").substringBeforeLast(':')

        /** A service made this soon after its process began was what the process began for. */
        private const val COLD_START_MILLIS = 3_000L
        private const val CHECKPOINT_MILLIS = 20_000L
    }
}
