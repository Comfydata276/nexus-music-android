package org.medianexus.music.playback

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song
import org.medianexus.music.nexus.DiagnosticFault
import org.medianexus.music.nexus.DiagnosticPlace
import org.medianexus.music.nexus.DiagnosticShape
import org.medianexus.music.nexus.EventId
import org.medianexus.music.nexus.JournalScope
import org.medianexus.music.nexus.Listening
import org.medianexus.music.nexus.PlayBegan
import org.medianexus.music.nexus.PlayOrigin
import org.medianexus.music.nexus.PlaySound
import org.medianexus.music.nexus.StopReason

class ListeningPlaybackTest {
    private val one = JournalScope(JsonPrimitive("01".repeat(16)), JsonPrimitive(1), JsonPrimitive(1))
    private val two = one.copy(number = JsonPrimitive(2))
    private val song = Song(id = "first", title = "First", artist = "Artist", album = "Album")
    private var time = 1_000L
    private val events = mutableListOf<Pair<JournalScope, Listening>>()
    private val playback = ListeningPlayback({ time }) { event, scope ->
        events.add(scope to event)
        EventId(0, events.size.toLong())
    }

    @Test fun gaplessTransitionPairsEachStartAndExcludesPauseAndBufferingFromHeardTime() {
        playback.sounding(one, 10, song, PlayOrigin.from("album:m"), PlayBegan.HERE, PlaySound.DOWNLOAD, 100, 10_000)
        time += 1_000
        playback.running(false) // Buffering: pauses the timer without a Pause event.
        time += 500
        playback.running(true)
        time += 1_000
        playback.pause(10, 2_100)
        time += 1_500
        playback.seek(10, 2_100, 7_000)
        playback.sounding(one, 10, song, PlayOrigin.from("album:m"), PlayBegan.HERE, PlaySound.DOWNLOAD, 7_000, 10_000)
        time += 3_000
        playback.stop(10, 10_000, StopReason.FINISHED)
        playback.sounding(one, 11, song.copy(id = "second"), PlayOrigin.from("album:m"), PlayBegan.NEXT, PlaySound.KEPT, 0, 5_000)
        val result = events.map { it.second }
        assertEquals(2, result.filterIsInstance<Listening.Start>().size)
        assertEquals(1, result.filterIsInstance<Listening.Pause>().size)
        assertEquals(1, result.filterIsInstance<Listening.Resume>().size)
        val seek = result.filterIsInstance<Listening.Seek>().single()
        assertEquals(2_100L, seek.from)
        assertEquals(7_000L, seek.to)
        val stop = result.filterIsInstance<Listening.Stop>().single()
        assertEquals(EventId(0, 1), stop.start)
        assertEquals(5_000L, stop.heard)
        assertEquals(StopReason.FINISHED, stop.reason)
        assertEquals(PlayBegan.NEXT, result.filterIsInstance<Listening.Start>().last().began)
    }

    @Test fun signOutFinishesOldPlayUnderItsOwnAccountAndNewCarPlayIsSeparate() {
        playback.sounding(one, 10, song, PlayOrigin.from("liked"), PlayBegan.HERE, PlaySound.STREAM, 0, 10_000)
        time += 1_000
        playback.retire(two, 10, 1_000)
        playback.sounding(two, 20, song.copy(id = "different"), PlayOrigin.from("playlist:b"), PlayBegan.CAR, PlaySound.DOWNLOAD, 0, 10_000)
        val fault = DiagnosticFault(DiagnosticPlace.PLAYBACK, DiagnosticShape.DECODE)
        playback.stop(20, 2_000, StopReason.ERROR, fault)
        assertEquals(listOf(one, one, two, two), events.map { it.first })
        assertEquals(StopReason.STOPPED, (events[1].second as Listening.Stop).reason)
        assertEquals(PlayBegan.CAR, (events[2].second as Listening.Start).began)
        assertEquals(fault, (events[3].second as Listening.Stop).fault)
        assertFalse(events.any { it.first == two && (it.second as? Listening.Start)?.songId == "first" })
    }

    @Test fun aPreviousThatRewindsIsASeekAndAChangedEntryCanStopAsNextOrPrevious() {
        playback.sounding(one, 10, song, PlayOrigin.from(""), PlayBegan.HERE, PlaySound.STREAM, 0, null)
        playback.seek(10, 4_000, 0)
        assertTrue(events.any { it.second is Listening.Seek })
        assertFalse(events.any { it.second is Listening.Stop })
        playback.stop(10, 0, StopReason.NEXT)
        playback.sounding(one, 11, song, PlayOrigin.from(""), PlayBegan.NEXT, PlaySound.STREAM, 0, null)
        playback.stop(11, 600, StopReason.PREVIOUS)
        assertEquals(listOf(StopReason.NEXT, StopReason.PREVIOUS), events.mapNotNull { (it.second as? Listening.Stop)?.reason })
    }
}
