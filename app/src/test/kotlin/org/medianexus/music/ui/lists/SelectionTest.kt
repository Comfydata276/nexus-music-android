package org.medianexus.music.ui.lists

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A selection belongs to the list it was made on (M3b fixes, B1). The reviewer's case: "b"
 * ticked at place 1 of [a, b, c]; a refresh brings [b, a, c]; a bulk removal then took "a".
 * Now the refresh is another list, and the selection goes with the one it was made on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SelectionTest {
    private fun songs(text: String) = text.split(' ').map { Song(id = it) }

    @Test
    fun aSelectionGoesWhenTheListBehindItIsReplacedEvenByOneTheSameLength() {
        var list by mutableStateOf(songs("a b c"))
        lateinit var selection: Selection
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { selection = rememberSelection(list) }
        shadowOf(Looper.getMainLooper()).idle()
        selection.toggle(1)
        assertTrue(selection.active)
        // The same list drawn again: the selection stays.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(1), selection.places)
        // A refresh: another list, the same length (it used to keep place 1, now "a").
        list = songs("b a c")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the selection outlived its list", selection.active)
    }
}
