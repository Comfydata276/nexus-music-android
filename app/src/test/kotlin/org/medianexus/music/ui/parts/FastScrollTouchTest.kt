package org.medianexus.music.ui.parts

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The fast-scroll handle takes only the touches meant for it: real touches, given to a real
 * Compose list with the handle over it. A row's "…" sits under the edge the handle moves in;
 * a tap on it opens its menu with the handle hidden and with it showing, and a drag on the
 * handle moves the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class FastScrollTouchTest {
    private lateinit var view: View
    private lateinit var list: LazyListState
    private val menus = ArrayList<Int>()
    private var clock = SystemClock.uptimeMillis()

    @Before
    fun start() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            val state = rememberLazyListState().also { list = it }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(500) { row ->
                        Row(Modifier.fillMaxWidth().height(ROW.dp)) {
                            Spacer(Modifier.weight(1f))
                            // The row's "…", at its right edge: under the handle's edge.
                            Box(Modifier.size(MORE.dp).clickable { menus += row })
                        }
                    }
                }
                FastScroll(state, first = 0, count = 500) { "$it" }
            }
        }
        frames()
        view = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
    }

    /** Lets Compose draw and handle what it has been given (a few frames). */
    private fun frames(millis: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        clock += 10
        val event = MotionEvent.obtain(down, clock, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
        frames(10)
    }

    private fun tap(x: Float, y: Float) {
        val down = ++clock
        touch(MotionEvent.ACTION_DOWN, x, y, down)
        touch(MotionEvent.ACTION_UP, x, y, down)
        frames()
    }

    private fun drag(x: Float, fromY: Float, toY: Float, steps: Int = 10) {
        val down = ++clock
        touch(MotionEvent.ACTION_DOWN, x, fromY, down)
        for (i in 1..steps) touch(MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / steps, down)
        // Held still a moment before lifting: no fling after.
        clock += 60
        touch(MotionEvent.ACTION_MOVE, x, toY, down)
        touch(MotionEvent.ACTION_UP, x, toY, down)
    }

    /** Where a row's "…" is, on the screen as it stands (its middle). */
    private fun moreOf(row: Int): Pair<Float, Float> {
        val y = (row - list.firstVisibleItemIndex) * ROW - list.firstVisibleItemScrollOffset + ROW / 2f
        return view.width - MORE / 2f to y
    }

    @Test
    fun aTapOnARowsMoreWhileTheHandleIsHiddenOpensItsMenu() {
        val (x, y) = moreOf(5)
        tap(x, y)
        // The strip over the edge used to take it, hidden as it was.
        assertEquals(listOf(5), menus)
    }

    @Test
    fun aTapOnARowsMoreWhileTheHandleShowsOpensItsMenu() {
        // The list moved by a finger: the handle shows (at the top of the edge, the list being
        // near its top).
        drag(100f, 500f, 200f)
        assertTrue(list.firstVisibleItemIndex > 0)
        // A row well below the handle, while it shows.
        val row = list.firstVisibleItemIndex + 6
        val (x, y) = moreOf(row)
        tap(x, y)
        assertEquals(listOf(row), menus)
    }

    @Test
    fun aDragOnTheHandleMovesTheList() {
        // Shown by moving the list a little, then the handle (at the top of the edge) taken
        // down to the middle.
        drag(100f, 300f, 250f)
        val before = list.firstVisibleItemIndex
        drag(view.width - 16f, 20f, view.height / 2f)
        frames(200)
        assertTrue("the list didn't follow: ${list.firstVisibleItemIndex}", list.firstVisibleItemIndex > before + 150)
        // Nothing was opened on the way.
        assertEquals(emptyList<Int>(), menus)
    }

    @Test
    fun theLastMovementOfADragIsAppliedBeforeTheReleaseResetsIt() {
        drag(100f, 300f, 250f)
        // Down on the handle, one movement to the foot of the edge, and lifted straight away,
        // before a frame: the list goes there all the same (it used to be dropped).
        val down = ++clock
        touch(MotionEvent.ACTION_DOWN, view.width - 16f, 20f, down)
        for ((action, y) in listOf(MotionEvent.ACTION_MOVE to view.height - 1f, MotionEvent.ACTION_UP to view.height - 1f)) {
            clock += 10
            val event = MotionEvent.obtain(down, clock, action, view.width - 16f, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        frames(200)
        assertTrue("the list stopped short: ${list.firstVisibleItemIndex}", list.firstVisibleItemIndex > 480)
    }

    private companion object {
        const val ROW = 56
        const val MORE = 48
    }
}
