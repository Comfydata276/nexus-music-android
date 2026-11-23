package org.medianexus.music

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.medianexus.music.ui.parts.coverImageLoader

class App : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    /** Coil's loader, made the first time a picture is shown: covers from the cover store. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = coverImageLoader(this, graph.accounts)
}

/** The app's long-lived objects, from anywhere that has a context. */
val Context.graph: Graph get() = (applicationContext as App).graph
