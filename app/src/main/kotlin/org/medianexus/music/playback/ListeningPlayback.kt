package org.medianexus.music.playback

import org.medianexus.music.net.Song
import org.medianexus.music.nexus.DiagnosticFault
import org.medianexus.music.nexus.EventId
import org.medianexus.music.nexus.JournalScope
import org.medianexus.music.nexus.Listening
import org.medianexus.music.nexus.PlayBegan
import org.medianexus.music.nexus.PlayOrigin
import org.medianexus.music.nexus.PlaySound
import org.medianexus.music.nexus.StopReason

/** Only the player's application thread touches this. Its sink only offers records. */
internal class ListeningPlayback(
    private val clock: () -> Long,
    private val record: (Listening, JournalScope) -> EventId?,
) {
    private data class Play(
        val id: EventId,
        val scope: JournalScope,
        val entry: Long,
        val duration: Long?,
        var played: Long,
        var runningSince: Long?,
        var paused: Boolean = false,
    )

    private var play: Play? = null
    private var checkedAt = 0L

    fun sounding(
        scope: JournalScope,
        entry: Long,
        song: Song,
        from: PlayOrigin,
        began: PlayBegan,
        sound: PlaySound,
        position: Long,
        duration: Long?,
    ) {
        val existing = play
        if (existing?.entry == entry && existing.scope == scope) {
            running(true)
            resume(entry, position)
            return
        }
        if (existing != null) return // The old entry must be ended with its own position first.
        val id = record(Listening.Start(song, from, began, sound, position), scope) ?: return
        play = Play(id, scope, entry, duration, 0, clock())
        checkedAt = 0
    }

    fun running(on: Boolean) {
        val current = play ?: return
        val at = clock()
        if (on) {
            if (current.runningSince == null) current.runningSince = at
        } else {
            current.runningSince?.let { current.played += (at - it).coerceAtLeast(0) }
            current.runningSince = null
        }
    }

    fun resume(entry: Long?, position: Long) {
        val current = play?.takeIf { it.entry == entry && it.paused } ?: return
        current.paused = false
        record(Listening.Resume(current.id, position), current.scope)
    }

    fun pause(entry: Long?, position: Long) {
        val current = play?.takeIf { it.entry == entry && !it.paused } ?: return
        running(false)
        current.paused = true
        record(Listening.Pause(current.id, position), current.scope)
        checkpoint(entry, position, force = true)
    }

    fun seek(entry: Long?, from: Long, to: Long) {
        val current = play?.takeIf { it.entry == entry } ?: return
        if (from == to) return
        record(Listening.Seek(current.id, from, to), current.scope)
        checkpoint(entry, to, force = true)
    }

    fun stop(entry: Long?, position: Long, reason: StopReason, fault: DiagnosticFault? = null) {
        val current = play?.takeIf { it.entry == entry } ?: return
        running(false)
        play = null
        record(Listening.Stop(current.id, position, current.duration ?: position, current.played, reason, fault), current.scope)
    }

    fun retire(scope: JournalScope?, entry: Long?, position: Long) {
        val existing = play ?: return
        if (existing.scope == scope) return
        if (existing.entry == entry) stop(entry, position, StopReason.STOPPED)
        else play = null
    }

    fun checkpoint(entry: Long?, position: Long, force: Boolean = false) {
        val current = play?.takeIf { it.entry == entry } ?: return
        val at = clock()
        if (!force && at - checkedAt < CHECKPOINT_MILLIS) return
        checkedAt = at
        record(Listening.Position(current.id, position), current.scope)
    }

    companion object {
        private const val CHECKPOINT_MILLIS = 5_000L
    }
}
