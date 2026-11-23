package org.medianexus.music

import android.app.Application
import android.content.Context

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

/** The app's long-lived objects, from anywhere that has a context. */
val Context.graph: Graph get() = (applicationContext as App).graph
