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
        graph = Graph(this, boxForTheJvm() ?: org.medianexus.music.store.KeystoreBox())
        graph.recording.diagnostics.installCrashHandler()
        // (Only now is the Nexus server's part of the app made: see Graph.started.)
        graph.started()
    }

    /** Coil's loader, made the first time a picture is shown: covers from the cover store. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = coverImageLoader(this, graph.accounts, offline = { graph.watch.state.value.manual })
}

/** The app's long-lived objects, from anywhere that has a context. */
val Context.graph: Graph get() = (applicationContext as App).graph

/**
 * **For the unit tests on the JVM only**, which have no Android Keystore: what the app's own
 * graph seals with there (a debug build, run by Gradle's unit-test task, which sets
 * `nexus.unsealed`). Never on a phone: a release build has no debug flag, and nothing on Android
 * sets that property.
 */
private fun boxForTheJvm(): org.medianexus.music.store.SecretBox? =
    if (BuildConfig.DEBUG && System.getProperty("nexus.unsealed") == "1") {
        object : org.medianexus.music.store.SecretBox {
            override fun seal(plain: ByteArray) = plain.copyOf()
            override fun open(sealed: ByteArray) = sealed.copyOf()
        }
    } else {
        null
    }
