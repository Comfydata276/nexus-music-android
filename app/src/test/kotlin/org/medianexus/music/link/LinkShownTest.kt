package org.medianexus.music.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.medianexus.music.net.Playlist

/**
 * The playlist the linking record is kept in is left out of the lists shown **only where
 * linking exists in the build, is running and is wanted**; everywhere else every list is
 * the very list it was.
 */
class LinkShownTest {
    private val lists = listOf(Playlist(id = "p1", name = "Road trip"), Playlist(id = "k", name = "Nexus Music link", comment = "the record"), Playlist(id = "p2", name = "Evening"))

    @After
    fun tidy() {
        LinkBuild.forTests = null
        LinkShown.wanted = { false }
    }

    @Test
    fun withoutLinkingEveryListIsAsItWas() {
        // Linking does not exist (the build without the property, or switched off whatever the
        // build). Nothing is asked, nothing is left out.
        LinkBuild.forTests = false
        LinkShown.wanted = { throw AssertionError("asked whether linking is wanted in a build without it") }
        assertSame(lists, LinkShown.of(lists))
        // Existing, and not running or not wanted: the same.
        LinkBuild.forTests = true
        LinkShown.wanted = { false }
        assertSame(lists, LinkShown.of(lists))
    }

    @Test
    fun whereLinkingRunsTheRecordsPlaylistIsLeftOutOfWhatIsShown() {
        LinkBuild.forTests = true
        LinkShown.wanted = { true }
        assertEquals(listOf("p1", "p2"), LinkShown.of(lists).map { it.id })
        // A list without it is the list itself.
        val plain = lists.filter { it.id != "k" }
        assertSame(plain, LinkShown.of(plain))
    }
}
