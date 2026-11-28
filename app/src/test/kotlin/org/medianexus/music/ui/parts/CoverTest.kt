package org.medianexus.music.ui.parts

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.fetch.Fetcher
import coil3.request.allowHardware
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.store.CoverStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w800dp-h920dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class CoverTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() {
        scope.cancel()
        SingletonImageLoader.reset()
    }

    @Test
    fun aKeptSmallerCoverDrawsWhileTheRequestedTierLoadsAndSurvivesResize() {
        val small = png(Color.BLUE)
        val large = png(Color.GREEN)
        folder.newFile("album.160").writeBytes(png(Color.RED))
        folder.newFile("album.320").writeBytes(small)
        val fetches = CopyOnWriteArrayList<Int>()
        val wanted = CompletableDeferred<ByteArray>()
        val bigger = CompletableDeferred<ByteArray>()
        val covers = CoverStore(folder.root, scope, Dispatchers.IO, { _, size ->
            fetches += size
            when (size) {
                512 -> wanted.await()
                640 -> bigger.await()
                else -> error("unexpected request for ${size}px")
            }
        })
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val requests = CopyOnWriteArrayList<CoverArt>()
        val loader = ImageLoader.Builder(activity)
            .allowHardware(false)
            .diskCache(null)
            .components {
                add(Fetcher.Factory<CoverArt> { art, _, _ ->
                    requests += art
                    CoverFetcher(art, covers)
                })
            }
            .build()
        SingletonImageLoader.setUnsafe(loader)
        val width = mutableIntStateOf(360)
        activity.setContent { Cover("album", Modifier.size(width.intValue.dp), shape = CoverShape.SQUARE) }
        try {
            frames()
            val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            until("the largest kept cover drew before the 512 px reply") {
                fetches == listOf(512) && color(view) == Color.BLUE
            }
            assertNull(covers.kept("album", 512))

            val beforeResize = requests.toList()
            width.intValue = 400 // Still the 512 px tier.
            frames()
            assertEquals(Color.BLUE, color(view))
            assertEquals(beforeResize, requests)

            wanted.complete(large)
            until("the requested cover replaced the preview") { color(view) == Color.GREEN }
            assertArrayEquals(large, covers.kept("album", 512)!!.readBytes())
            assertArrayEquals(small, covers.kept("album", 320)!!.readBytes())

            width.intValue = 580
            until("the resized cover asked for the next tier") { fetches == listOf(512, 640) }
            assertEquals(Color.GREEN, color(view))
            assertNull(covers.kept("album", 640))
        } finally {
            controller.pause().stop().destroy()
            loader.shutdown()
        }
    }

    private fun frames() = shadowOf(Looper.getMainLooper()).idleFor(64, TimeUnit.MILLISECONDS)

    private fun until(description: String, check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) {
            frames()
            if (check()) return
            Thread.sleep(5)
        }
        assertTrue(description, check())
    }

    private fun color(view: View): Int {
        val pixels = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(pixels))
            pixels.getPixel(32, 32)
        } finally {
            pixels.recycle()
        }
    }

    private fun png(color: Int): ByteArray {
        val pixels = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().also { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            pixels.recycle()
        }
    }
}
