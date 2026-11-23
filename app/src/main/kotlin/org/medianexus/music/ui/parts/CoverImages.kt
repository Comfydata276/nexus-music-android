package org.medianexus.music.ui.parts

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.crossfade
import java.io.IOException
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.medianexus.music.Accounts
import org.medianexus.music.store.CoverStore

/** What a screen asks Coil for: one cover by its id, at the usual size or, for Now Playing
 *  only, the large one. */
data class CoverArt(val id: String, val large: Boolean = false)

/**
 * The app's one image loader. Every picture comes through the cover store of whoever is
 * signed in, which fetches and keeps it; Coil only decodes the store's file at the size it
 * is shown and holds a few recent ones in memory. Its own disk cache is off, so no picture is
 * kept on disk twice.
 */
fun coverImageLoader(context: Context, accounts: Accounts): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(CoverFetcher.Factory(accounts), CoverArt::class)
            add(CoverKeyer(accounts), CoverArt::class)
        }
        .diskCache(null)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .crossfade(120)
        .build()

private class CoverFetcher(private val art: CoverArt, private val covers: CoverStore) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = covers.file(art.id, if (art.large) CoverStore.LARGE else CoverStore.SIZE)
            // Coil shows nothing for a failure, and the cover's stand-in stays.
            ?: throw IOException("No cover")
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    class Factory(private val accounts: Accounts) : Fetcher.Factory<CoverArt> {
        override fun create(data: CoverArt, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.id.isEmpty()) return null
            val covers = accounts.state.value?.covers ?: return null
            return CoverFetcher(data, covers)
        }
    }
}

/** Pictures held in memory are told apart by account as well as cover: two servers can give
 *  the same id to different pictures. */
private class CoverKeyer(private val accounts: Accounts) : Keyer<CoverArt> {
    override fun key(data: CoverArt, options: Options): String? {
        val account = accounts.state.value?.account?.key ?: return null
        return "$account/${data.id}/${if (data.large) CoverStore.LARGE else CoverStore.SIZE}"
    }
}
