package org.medianexus.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import org.medianexus.music.BuildConfig
import org.medianexus.music.net.Song
import org.medianexus.music.store.CoverProvider

/** Turns songs and the car's entries into what Media3 hands to the car and the system. */
@OptIn(UnstableApi::class)
object CarMedia {
    /** The details of a song as the notification, the lock screen and the car show them.
     *  `account` is the artwork id of the account the song is from (`AccountGraph.artwork`): its
     *  cover's address names it, so the address is no good to anyone once another account
     *  is signed in (see [CoverProvider]). Empty for no account, and then no cover. */
    fun metadata(song: Song, account: String): MediaMetadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artistLabel)
        .setAlbumTitle(song.album.ifEmpty { null })
        .setAlbumArtist(song.artist.ifEmpty { null })
        .setArtworkUri(CoverProvider.uriFor(account, song.coverArt))
        .setTrackNumber(song.track.takeIf { it > 0 })
        .setDiscNumber(song.discNumber.takeIf { it > 0 })
        .setReleaseYear(song.year.takeIf { it > 0 })
        .setGenre(song.genre.ifEmpty { null })
        .setDurationMs(song.duration.takeIf { it > 0 }?.let { it * 1000L })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()

    /** A browse tree entry as the car gets it, for the account with this key (see [metadata]). */
    fun item(entry: CarEntry, account: String): MediaItem {
        val extras = Bundle()
        if (entry.group.isNotEmpty()) extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, entry.group)
        entry.inside?.let {
            if (entry.browsable) {
                extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style(it))
                extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style(it))
            }
        }
        entry.style?.let { extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, style(it)) }
        val art = CoverProvider.uriFor(account, entry.cover) ?: entry.icon.takeIf { it != 0 }?.let(::iconUri)
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setSubtitle(entry.subtitle.ifEmpty { null })
            .setArtworkUri(art)
            .setIsPlayable(entry.playable)
            .setIsBrowsable(entry.browsable)
            .setMediaType(
                when {
                    entry.playable -> MediaMetadata.MEDIA_TYPE_MUSIC
                    entry.id.startsWith("album/") || entry.id.contains("~album/") -> MediaMetadata.MEDIA_TYPE_ALBUM
                    entry.id.startsWith("artist/") -> MediaMetadata.MEDIA_TYPE_ARTIST
                    entry.id.startsWith("playlist/") -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                    entry.id.startsWith("genre/") -> MediaMetadata.MEDIA_TYPE_GENRE
                    else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                },
            )
            .setExtras(extras)
        entry.song?.let { song ->
            metadata.setArtist(song.artistLabel).setAlbumTitle(song.album.ifEmpty { null })
                .setDurationMs(song.duration.takeIf { it > 0 }?.let { it * 1000L })
        }
        return MediaItem.Builder().setMediaId(entry.id).setMediaMetadata(metadata.build()).build()
    }

    /** One of the app's drawables as an address the car can open. By number, not by name:
     *  a release build's resources may not keep their names. */
    fun iconUri(drawable: Int): Uri = "android.resource://${BuildConfig.APPLICATION_ID}/$drawable".toUri()

    private fun style(style: CarStyle): Int = when (style) {
        CarStyle.LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        CarStyle.GRID -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        CarStyle.CATEGORY_LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
        CarStyle.CATEGORY_GRID -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
    }
}
