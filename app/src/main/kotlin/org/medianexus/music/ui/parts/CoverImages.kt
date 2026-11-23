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
import coil3.bitmapFactoryMaxParallelism
import coil3.request.crossfade
import java.io.IOException
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.medianexus.music.Accounts
import org.medianexus.music.store.CoverStore

/** What a screen asks Coil for: one cover by its id, at the usual size or, for Now Playing
 *  only, the large one. */
data class CoverArt(
    val id: String,
    val large: Boolean = false,
    /** Which retry this is (see [CoverRetry]): a new number is a new request to Coil, which
     *  doesn't ask again for what has failed. Not part of the picture's key. */
    val attempt: Int = 0,
)

/**
 * The app's one image loader. Every picture comes through the cover store of whoever is
 * signed in, which fetches and keeps it; Coil only decodes the store's file at the size it
 * is shown and holds a few recent ones in memory. Its own disk cache is off, so no picture is
 * kept on disk twice.
 *
 * What it holds in memory is decoded pictures, which are most of the app's memory once a few
 * pages have been looked at: a fifteenth of a large phone's allowance is 75 MB of them. So
 * it is held to [MEMORY_CACHE_BYTES] at most (or 15% of the allowance, on a phone that gives
 * less). That is a screenful of a grid several times over, Home's shelves, and Now Playing's
 * cover: going back to a page finds its covers, and what was looked at a while ago is
 * decoded again from the phone's own copy, which is quick.
 */
fun coverImageLoader(context: Context, accounts: Accounts): ImageLoader {
    val allowance = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass * 1024L * 1024L
    return ImageLoader.Builder(context)
        .components {
            add(CoverFetcher.Factory(accounts), CoverArt::class)
            add(CoverKeyer(accounts), CoverArt::class)
        }
        .diskCache(null)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(minOf(MEMORY_CACHE_BYTES, (allowance * 0.15).toLong())).build() }
        // A fling through a grid asks for dozens at once: a few are read and decoded at a
        // time, and those the grid has scrolled past by then are never started (Coil drops a
        // request whose place has left the screen; the store drops a fetch nobody waits for).
        .fetcherCoroutineContext(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(FETCHES_AT_ONCE))
        .bitmapFactoryMaxParallelism(DECODES_AT_ONCE)
        .crossfade(120)
        .build()
}

/** Decoded pictures held in memory at most. See [coverImageLoader]. */
const val MEMORY_CACHE_BYTES = 24L * 1024 * 1024

/** Covers read from the store (and, if need be, fetched) at once; and decoded at once. */
private const val FETCHES_AT_ONCE = 6
private const val DECODES_AT_ONCE = 2

/**
 * Covers that failed to load are asked for again when this changes: the server can be
 * reached again, or the phone has just been allowed to reach the home network. Bumped by
 * [org.medianexus.music.Graph]; read by `Cover`.
 */
object CoverRetry {
    private val count = kotlinx.coroutines.flow.MutableStateFlow(0)
    val tries: kotlinx.coroutines.flow.StateFlow<Int> = count

    fun now() {
        count.value++
    }
}

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

/**
 * Pictures held in memory are told apart by account as well as cover (two servers can give
 * the same id to different pictures), and by the width they were decoded at. Without the
 * width, Coil hands a picture decoded large (an album's header) to a small place that shows
 * the same cover (a row), which then holds the large one in memory for as long as it is on
 * screen.
 */
private class CoverKeyer(private val accounts: Accounts) : Keyer<CoverArt> {
    override fun key(data: CoverArt, options: Options): String? {
        val account = accounts.state.value?.account?.key ?: return null
        val width = (options.size.width as? coil3.size.Dimension.Pixels)?.px ?: 0
        return "$account/${data.id}/${if (data.large) CoverStore.LARGE else CoverStore.SIZE}/$width"
    }
}
