package org.medianexus.music.jam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

class WireTest {
    /** Windows' `describes_a_song_for_guests`. */
    @Test
    fun describesASongForGuests() {
        val song = Song(id = "s1", title = "Blue", artist = "A", displayArtist = "A & B", album = "Sky", coverArt = "al-1", duration = 185)
        assertEquals(Json.parseToJsonElement("""{"id": "s1", "title": "Blue", "artist": "A & B", "album": "Sky", "cover": "al-1", "duration": 185}"""), songJson(song))
    }

    @Test
    fun readsWhatAWindowsHostSends() {
        // As Windows' jam_sync writes it.
        val heard = Heard.read(Json.parseToJsonElement("""
            {"v": 7, "host": "Jo", "away": false, "age": 0.5, "guests": [{"id": "g1", "name": "Sam"}],
             "you": {"id": "g1", "name": "Sam"},
             "state": {"playing": true, "position": 12.5, "control": true,
                "now": {"id": "s1", "title": "Blue", "artist": "A", "album": "Sky", "cover": "al-1", "duration": 185},
                "queue": [{"id": "s2", "title": "Red", "artist": "B", "album": "", "cover": "", "duration": 100, "queued": true, "by": "Sam", "by_id": "g1"},
                          {"id": "s3", "title": "Green", "artist": "C", "album": "Sea", "cover": "al-3", "duration": 200}],
                "more": 3, "context": "Sky", "shuffle": false, "repeat": 1, "volume": 0.8}}
        """.trimIndent()))
        assertEquals(7L, heard.version)
        assertEquals("Jo", heard.host)
        assertEquals(listOf(JamGuest("g1", "Sam")), heard.guests)
        val state = heard.state
        assertTrue(state.playing)
        assertEquals(12.5, state.position, 0.0)
        assertEquals("Blue", state.now?.title)
        assertEquals(2, state.queue.size)
        assertEquals(1, state.queuedAhead)
        assertEquals("g1", state.queue[0].byId)
        assertEquals(3, state.more)
        assertEquals(1, state.repeat)
        assertEquals(0.8, state.volume!!, 0.0)
    }

    /** Rule 5 (a jam joined is someone else's server talking to us): nothing it sends is
     *  trusted. */
    @Test
    fun nothingAJamSendsIsTrusted() {
        val long = "x".repeat(10_000)
        val queue = (0 until 500).joinToString(",") { """{"id": "s$it", "title": "t"}""" }
        val heard = Heard.read(Json.parseToJsonElement("""
            {"v": -3, "host": "$long", "age": 1e300,
             "state": {"playing": "yes", "position": -50, "repeat": 9, "volume": 7, "more": 99999999999,
               "now": {"id": "../../rest/stream?id=1", "title": "a‮b\u0000c", "cover": "http://evil/x", "duration": 99999999999},
               "queue": [$queue]}}
        """.trimIndent()))
        assertEquals(0L, heard.version)
        assertEquals(24, heard.host.length)
        assertEquals(24.0 * 3600, heard.age, 0.0)
        assertFalse(heard.state.playing)
        assertEquals(0.0, heard.state.position, 0.0)
        assertEquals(2, heard.state.repeat)
        assertEquals(1.0, heard.state.volume!!, 0.0)
        assertEquals(10_000_000, heard.state.more)
        // An id that isn't one is dropped; a cover that is an address is no cover.
        assertEquals("", heard.state.now?.id)
        assertEquals("", heard.state.now?.cover)
        assertEquals("abc", heard.state.now?.title)
        assertEquals(7 * 24 * 3600, heard.state.now?.duration)
        assertEquals(SHOWN, heard.state.queue.size)
        // Of the wrong kind altogether: read as nothing, never an error.
        assertEquals(JamState(), Heard.read(JsonPrimitive("hello")).state)
        assertNull(Heard.read(buildJsonObject { put("state", JsonPrimitive(3)) }).state.now)
    }

    @Test
    fun devicesAreReadWithCare() {
        val devices = Device.list(Json.parseToJsonElement("""
            {"v": 2, "devices": [
              {"id": "a1b2c3d4", "name": "Desk", "status": {"session": "abc234", "title": "Blue", "playing": true, "steering": null}},
              {"id": "bad id", "name": "x", "status": {}},
              {"id": "e5f6", "name": "Laptop", "status": {"session": "not/a/session", "steering": "Jo's jam"}}]}
        """.trimIndent()))
        assertEquals(2, devices.size)
        assertEquals("abc234", devices[0].session)
        assertTrue(devices[0].playing)
        assertNull(devices[1].session)
        assertEquals("Jo's jam", devices[1].steering)
    }

    @Test
    fun textIsMadeFitToShow() {
        assertEquals("one two", shownText("one\ntwo"))
        assertEquals("ab", shownText("a​b"))
        // A cut never leaves half a character.
        val face = "😀"
        assertEquals(face, shownText(face + face, most = 3))
    }
}
