package org.medianexus.music.ui.lists

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A drag takes its copy of the playlist when the finger goes down (M3b fixes 3, item 1). The
 * page is drawn again from another copy while the finger is down (another device's change,
 * read meanwhile); the drop is still the move of the song that was under the finger, on the
 * copy that was drawn then, so the strict rule can compare that copy with the server's.
 * Real touches, on a real Compose list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class DragCopyTest {
    private lateinit var view: View
    private var clock = SystemClock.uptimeMillis()
    /** The copy of the playlist the page draws from (only its name matters here). */
    private var copy by mutableStateOf("drawn first")
    private val drops = ArrayList<Triple<String, Int, Int>>()

    private fun frames(millis: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        clock += 10
        val event = MotionEvent.obtain(down, clock, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
        // (Long enough for a frame: the rows are laid out again between moves.)
        frames(20)
    }

    @Test
    fun theDropIsMadeOnTheCopyDrawnWhenTheFingerWentDown() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            val drag = remember { PlaylistDrag() }
            val list = rememberLazyListState()
            // What the page draws its rows from, read as it draws them (as SongListPage's
            // `editable` is).
            val drawn = copy
            // The rows as SongListPage draws them: in the order being made, the lifted one
            // following the finger.
            val shown = remember { IntArray(20) { it } }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(shown.size, key = { row -> "${drag.at(shown, row)}/s" }) { row ->
                    val place = drag.at(shown, row)
                    val lifted = drag.lifted == place
                    Row(Modifier.fillMaxWidth().height(ROW.dp).then(if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = drag.offset } else Modifier)) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(ROW.dp).dragRows(drag, list, place) { from, to -> drops += Triple(drawn, from, to); true })
                    }
                }
            }
        }
        frames()
        view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val x = view.width - ROW / 2f
        val down = ++clock
        // The fourth row's handle (clear of the edges, where the list scrolls), down a row.
        val y = 3 * ROW + ROW / 2f
        touch(MotionEvent.ACTION_DOWN, x, y, down)
        for (i in 1..10) touch(MotionEvent.ACTION_MOVE, x, y + ROW * i / 10f, down)
        // The page is drawn again from a fresh copy while the finger is still down (its songs
        // the same: a change to them stops the drag, as SongListPage resets it).
        copy = "read meanwhile"
        frames()
        touch(MotionEvent.ACTION_UP, x, y + ROW, down)
        frames()
        assertEquals(listOf(Triple("drawn first", 3, 4)), drops)
    }

    private companion object {
        const val ROW = 60
    }
}
