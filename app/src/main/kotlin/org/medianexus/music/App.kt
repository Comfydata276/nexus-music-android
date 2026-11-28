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
        // (A debug build can be told to run what the build has switched off: sync/DebugStep.kt.
        // Before anything is made, since what exists is decided as it is.)
        org.medianexus.music.sync.DebugStep.atStart(this)
        org.medianexus.music.requests.DebugRequests.atStart(this)
        graph = Graph(this)
        // (Only now is the Nexus server's part of the app made: see Graph.started.)
        graph.started()
    }

    /** Coil's loader, made the first time a picture is shown: covers from the cover store. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = coverImageLoader(this, graph.accounts, offline = { graph.watch.state.value.manual })
}

/** The app's long-lived objects, from anywhere that has a context. */
val Context.graph: Graph get() = (applicationContext as App).graph
