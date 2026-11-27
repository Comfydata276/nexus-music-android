package org.medianexus.music.nexus

/**
 * Whether the Nexus server's client (`nexus/Nexus.kt`) exists in this build. **It does not**, in
 * any build that is published, until the checks only a real phone, a car and a real proxy can
 * make have been made (docs/DESIGN.md, "The Nexus server's client").
 *
 * **With it off, nothing of it exists**: no launcher is made, no thread started, no row shown
 * under Settings, no request made: the app as released.
 */
object NexusBuild {
    /** The one switch, as the build was made with it (`nexusExists` in `app/build.gradle.kts`:
     *  off, unless the build was asked for it by name). */
    val EXISTS: Boolean = org.medianexus.music.BuildConfig.NEXUS

    /** Set only by tests. Null, as it always is in a build: the build's own answer. */
    @Volatile internal var forTests: Boolean? = null

    /** Whether it runs: what a test says, or [EXISTS]. */
    val on: Boolean get() = forTests ?: EXISTS
}
