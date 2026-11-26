package org.medianexus.music.link

import org.medianexus.music.net.Playlist

/**
 * **Where linking exists in this build, is running and is wanted**, the playlist the
 * linking record is kept in (a playlist of the account's, named [NAME], whose description
 * is the secret) is left out of the lists of playlists the person is shown: the app's own
 * list, the list songs are added to, and the car's. **In memory, as the list is shown, and
 * nothing more**: nothing kept on the phone is changed or deleted, nothing is reserved, and
 * what is downloaded is as it was. Where linking does not exist, or is not running or not
 * wanted, every list is exactly as it is without this: the playlist is one like any other.
 *
 * (How that playlist is kept out of sight and out of caches in every copy of the app is
 * for when linking is switched on for everyone.)
 */
object LinkShown {
    /** The shared library's `link::secret::PLAYLIST_NAME`. */
    const val NAME = "Nexus Music link"

    /** Whether linking is running and wanted now. Set by `playback/Linking` while it
     *  runs, and by nothing else. */
    @Volatile var wanted: () -> Boolean = { false }

    fun of(playlists: List<Playlist>): List<Playlist> =
        if (LinkBuild.on && playlists.any { it.name == NAME } && wanted()) playlists.filter { it.name != NAME } else playlists
}
