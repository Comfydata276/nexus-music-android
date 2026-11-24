package org.medianexus.music.ui.lists

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.test.utils.robolectric.RobolectricUtil
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.library.CopyStamp
import org.medianexus.music.library.ListView
import org.medianexus.music.library.LibrarySnapshot
import org.medianexus.music.net.Song
import org.medianexus.music.store.SongTable
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * During the first fetch of the library a newer copy is published every second. The Songs
 * page grows in place: the rows in sight stay where they are while more arrive, above them
 * and below. On a real Compose list, keyed as the page keys it, with what is shown worked out
 * by the page's own [rememberSongsShown]. (The rows used to be taken away for each new copy,
 * the list showing its "loading" row meanwhile, and the place was lost once a second.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class SongsGrowTest {
    private fun song(id: String, day: Int) = Song(id = id, title = id, album = "A", created = java.time.Instant.ofEpochSecond(1_700_000_000L + day * 86_400L).toString())

    private fun copyOf(songs: List<Song>) = LibrarySnapshot(
        SongTable.Builder().apply { songs.forEach { add(it) } }.build(),
        emptyList(), emptyList(), CopyStamp(0, "", 0), complete = false,
    )

    @Test
    fun rowsInSightStayWhereTheyAreWhileMoreArrive() {
        // 200 songs have arrived, added on days 100 to 299 (so newest first: day 299 first).
        val arrived = (100 until 300).map { song("s$it", it) }
        val copies = MutableStateFlow(copyOf(arrived))
        lateinit var list: LazyListState
        var drawn = false
        var gone = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            val copy by copies.collectAsStateWithLifecycle()
            val shown by rememberSongsShown(copy, ListView())
            val state = rememberLazyListState().also { list = it }
            val now = shown
            if (now != null) drawn = true else if (drawn) gone++
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                // As the page: a row saying it is loading while there is no arrangement.
                if (now == null) {
                    item(key = "loading") { Box(Modifier.fillMaxWidth().height(400.dp)) }
                } else {
                    items(now.order.size, key = { now.list.songs.id(now.order[it]) }) { Box(Modifier.fillMaxWidth().height(56.dp)) }
                }
            }
        }
        fun until(what: () -> Boolean) = RobolectricUtil.runMainLooperUntil({ shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS); what() }, 60_000, androidx.media3.common.util.Clock.DEFAULT)
        until { list.layoutInfo.totalItemsCount == 200 }
        // Scrolled to the 80th song, newest first: added on day 220.
        list.requestScrollToItem(80)
        until { list.layoutInfo.visibleItemsInfo.firstOrNull()?.key == "s219" }
        // Three more copies arrive, each with songs added before and after those in sight.
        for (step in 1..3) {
            val more = (0 until 30).map { song("new$step-$it", 300 + step * 30 + it) } + (0 until 30).map { song("old$step-$it", it + step * 30 - 30) }
            copies.value = copyOf(copies.value.let { c -> (0 until c.songs.size).map { c.songs.song(it) } } + more)
            until { list.layoutInfo.totalItemsCount == 200 + 60 * step }
            // The row in sight is the same song, now further down the list.
            assertEquals("s219", list.layoutInfo.visibleItemsInfo.firstOrNull()?.key)
            assertEquals(80 + 30 * step, list.firstVisibleItemIndex)
        }
        assertEquals("the rows were taken away while a newer copy was arranged", 0, gone)
    }
}
