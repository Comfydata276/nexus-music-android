package org.medianexus.music.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * One confirmation, one action (M3b fixes 3, item 5). A sheet's button pressed twice while
 * the sheet closes, as two quick taps or a tap and the keyboard's tick, did it twice: two
 * playlists from one name. The real sheet, drawn by Compose, its button pressed through what
 * TalkBack would press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class SheetOnceTest {
    private fun frames(millis: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    private fun composeRoots(view: View): List<ViewRootForTest> = when {
        view is ViewRootForTest -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { composeRoots(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    /** The button with these words, in the sheet's window. */
    private fun button(words: String): SemanticsNode {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the sheet is up", dialog)
        return composeRoots(dialog.window!!.decorView)
            .flatMap { nodes(it.semanticsOwner.rootSemanticsNode) }
            .first { node -> node.config.getOrNull(SemanticsActions.OnClick) != null && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == words } == true }
    }

    @Test
    fun aConfirmationIsUsedOnce() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var confirmed = 0
        var closed = 0
        activity.setContent { SheetHost(Sheet.Confirm("Delete playlist?", "", "Delete") { confirmed++ }, onClose = { closed++ }) }
        frames(500)
        val delete = button("Delete")
        delete.config[SemanticsActions.OnClick].action!!.invoke()
        delete.config[SemanticsActions.OnClick].action!!.invoke()
        frames(1_000)
        assertEquals(1, confirmed)
    }

    @Test
    fun aDrawnSheetsChoiceIsUsedOnce() {
        // The name sheet's way: its content is given `choose`, and "Create" and the keyboard's
        // tick each call it.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var created = 0
        activity.setContent {
            SheetHost(Sheet.Drawn { choose -> org.medianexus.music.ui.parts.TextButton("Create", onClick = { choose { created++ } }) }, onClose = {})
        }
        frames(500)
        val create = button("Create")
        create.config[SemanticsActions.OnClick].action!!.invoke()
        create.config[SemanticsActions.OnClick].action!!.invoke()
        frames(1_000)
        assertEquals(1, created)
    }
}
