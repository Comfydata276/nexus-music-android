package org.medianexus.standin

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/**
 * FIXTURE. A stand-in for the playback service the real app will have: one ExoPlayer in a
 * Media3 `MediaLibraryService`, offering Android Auto what docs/DESIGN.md says the app
 * will offer. It exists so the test rig (scripts/carcheck, scripts/dhu, the car emulator)
 * can be proved end to end before that service is written, and so the rig's checks can be
 * shown to fail when they should (see Faults.kt). Nothing here is meant to be copied into
 * the app: it does the least that works.
 */
@OptIn(UnstableApi::class)
class StandinService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var catalogue: Catalogue
    private val liked = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        catalogue = Catalogue(this)
        player = ExoPlayer.Builder(this)
            // Handling audio focus is what makes it give way to a navigation prompt or a call.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) remember()
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                    session.setMediaButtonPreferences(buttons())
                }
            }
        })
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setMediaButtonPreferences(buttons())
            // When a browse request is answered with an error (not signed in, say), keep that
            // error standing on the session so the car shows it. Media3's default shows it
            // for an instant and withdraws it, which a car has no time to display.
            .setLibraryErrorReplicationMode(MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_FATAL)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, ListActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        session.release()
        player.release()
        super.onDestroy()
    }

    /** Where playback is, kept so "play" with nothing chosen can pick up after a restart. */
    private fun remember() {
        val id = player.currentMediaItem?.mediaId ?: return
        prefs.edit().putString("item", id).putLong("position", player.currentPosition).apply()
    }

    /** The extra buttons on the car's player: like, shuffle, repeat. */
    private fun buttons(): List<CommandButton> {
        val isLiked = player.currentMediaItem?.mediaId?.let { songKey(it) in liked } == true
        return listOf(
            CommandButton.Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (isLiked) "Unlike" else "Like").setSessionCommand(LIKE).build(),
            CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (player.shuffleModeEnabled) "Shuffle is on" else "Shuffle").setSessionCommand(SHUFFLE).build(),
            CommandButton.Builder(when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                else -> CommandButton.ICON_REPEAT_OFF
            }).setDisplayName(when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> "Repeat all"
                Player.REPEAT_MODE_ONE -> "Repeat one"
                else -> "Repeat"
            }).setSessionCommand(REPEAT).build(),
        )
    }

    private fun songKey(mediaId: String): String? = catalogue.queueFor(mediaId)?.let { it.first[it.second].localConfiguration?.uri?.lastPathSegment }

    private inner class Callback : MediaLibrarySession.Callback {
        private val faults get() = Faults.active(this@StandinService)

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Media3 sorts callers into trusted (this app, the system, anything allowed to read
            // notifications, which is what Android Auto is) and the rest. The builder gives the
            // trusted ones every command and the rest only the ones that read. Kept as it is:
            // a car gets full control, an unknown app may look but not touch.
            val accept = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (!controller.isTrusted) return accept.build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(LIKE).add(SHUFFLE).add(REPEAT)
            if ("no_search" in faults) commands.remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
            return accept.setAvailableSessionCommands(commands.build()).build()
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            // Unless a folder says otherwise: folders in a grid, songs in a list.
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(catalogue.root, LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                   page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if ("signed_out" in faults) {
                // How a car is told something is wrong: the answer to a browse request is an
                // error with words, which Media3 also puts on the session for the car to show.
                return Futures.immediateFuture(LibraryResult.ofError(
                    SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Open Stand-in on your phone to sign in")))
            }
            val all = catalogue.children(parentId) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
            val answer = LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            if ("slow" in faults && parentId == "tab/library") {
                val later = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                Handler(Looper.getMainLooper()).postDelayed({ later.set(answer) }, 12_000)
                return later
            }
            return Futures.immediateFuture(answer)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(catalogue.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, catalogue.search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                       page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(catalogue.search(query)), params))

        /** One item asked for: a chosen song, or a spoken request. Either way a whole list is queued. */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
                                     startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> {
            if (mediaItems.size != 1) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            if ("signed_out" in faults) return Futures.immediateFailedFuture(IllegalStateException("signed out"))
            val asked = mediaItems[0]
            val words = asked.requestMetadata.searchQuery
            val queue = when {
                words == null -> catalogue.queueFor(asked.mediaId)
                "deaf" in faults -> return Futures.immediateFailedFuture(UnsupportedOperationException())
                words.isBlank() -> resumePoint()?.let { Triple(it.mediaItems, it.startIndex, it.startPositionMs) }?.let { it.first to it.second }
                else -> catalogue.forVoice(words, asked.requestMetadata.extras)
            }
            if (queue == null) {
                if (words != null) session.sendError(SessionError(SessionError.ERROR_BAD_VALUE, "Couldn't find \"$words\""))
                return Futures.immediateFailedFuture(IllegalArgumentException("nothing matches"))
            }
            return Futures.immediateFuture(MediaItemsWithStartPosition(queue.first, queue.second, C.TIME_UNSET))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { asked -> catalogue.queueFor(asked.mediaId)?.let { it.first[it.second] } })

        /** "Play", with nothing in the player: the last thing played, or the liked songs. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaItemsWithStartPosition> {
            if ("no_resume" in faults || "signed_out" in faults) return Futures.immediateFailedFuture(UnsupportedOperationException())
            return resumePoint()?.let { Futures.immediateFuture(it) } ?: Futures.immediateFailedFuture(UnsupportedOperationException())
        }

        private fun resumePoint(): MediaItemsWithStartPosition? {
            val last = prefs.getString("item", null)?.let { catalogue.queueFor(it) }
            if (last != null) return MediaItemsWithStartPosition(last.first, last.second, prefs.getLong("position", 0))
            return catalogue.queueOfList("liked")?.let { MediaItemsWithStartPosition(it.first, it.second, C.TIME_UNSET) }
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                LIKE.customAction -> player.currentMediaItem?.mediaId?.let { songKey(it) }?.let { if (!liked.add(it)) liked.remove(it) }
                SHUFFLE.customAction -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                REPEAT.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            session.setMediaButtonPreferences(buttons())
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        val LIKE = SessionCommand("org.medianexus.standin.LIKE", Bundle.EMPTY)
        val SHUFFLE = SessionCommand("org.medianexus.standin.SHUFFLE", Bundle.EMPTY)
        val REPEAT = SessionCommand("org.medianexus.standin.REPEAT", Bundle.EMPTY)
    }
}
