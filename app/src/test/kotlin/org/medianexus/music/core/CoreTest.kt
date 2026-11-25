package org.medianexus.music.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary with the shared library, run against the real library (built for this
 * machine by the same step that builds it for the phone: see app/build.gradle.kts).
 */
class CoreTest {
    @Test
    fun `text crosses exactly, whatever is in it`() {
        // What Java's own encoding of text damages on the way (a zero character beside one
        // outside the basic plane), and what is merely unusual.
        for (text in listOf("", "plain", "Été & 夜 — odd", "zero\u0000and 🎶 together", "\"quoted\" \\ and\nlines\t", "   ﻿", "a".repeat(300_000))) {
            assertEquals(text, Core.echo(text))
        }
    }

    @Test
    fun `the playlist that holds the record is the lowest id the server says is the account's own`() {
        val name = "Nexus Music sync"
        val listed = listOf(
            Stated("z9", name, owner = "tester", comment = "{}"),
            Stated("b2", name, owner = "Tester"),
            Stated("a1", name, owner = "guest", comment = "theirs"),
            Stated("a0", "Road trip", owner = "tester"),
        )
        assertEquals(Found.Ours("b2"), Core.find(listed, "tester"))
        // None of that name is the account's: there is none.
        assertEquals(Found.None, Core.find(listed, "nobody"))
        assertEquals(Found.None, Core.find(emptyList(), "tester"))
        // One whose owner the server left out may be anybody's: with none known to be the
        // account's, the answer is "not stated", not "none".
        assertEquals(Found.NotStated, Core.find(listOf(Stated("q", name)), "tester"))
        assertEquals(Found.Ours("b2"), Core.find(listed + Stated("a", name), "tester"))
    }

    @Test
    fun `a panic in the library is an exception here with its words, and the next call works`() {
        val thrown = assertThrows(CoreException::class.java) { Core.stopForTheTest("stopped on purpose: Été 🎶") }
        assertTrue(thrown.message, thrown.message!!.contains("stopped on purpose: Été 🎶"))
        assertEquals("still here", Core.echo("still here"))
    }

    @Test
    fun `an answer the door has no name for is an exception, never taken for one it has`() {
        // Whether a save of the queue begins by reading the server's: "read" or "save". A
        // later library that says something else must not be taken to have said "save".
        assertTrue(Core.readsFirst("read"))
        assertTrue(!Core.readsFirst("save"))
        val thrown = assertThrows(CoreException::class.java) { Core.readsFirst("wait") }
        assertTrue(thrown.message, thrown.message!!.contains("wait"))
    }

    @Test
    fun `something the library doesn't take is an exception with the reason, not a crash`() {
        for (bytes in listOf("not json", "{\"listed\": 7}", "", "{\"listed\":[],\"username\":\"a\"} and more").map { it.encodeToByteArray() } + byteArrayOf(0xFF.toByte(), 0xFE.toByte())) {
            val thrown = assertThrows(CoreException::class.java) { Core.findRaw(bytes) }
            assertTrue(thrown.message, thrown.message!!.startsWith("The shared library was handed something it doesn't take"))
        }
    }
}
