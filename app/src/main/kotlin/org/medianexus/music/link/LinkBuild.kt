package org.medianexus.music.link

/**
 * Whether linking the person's devices by the scheme of the Windows repository's
 * `docs/linking-design.md` exists in this build. **It does not, yet.** The deciding is the
 * shared core's (`core/Core.kt`, the `link…` calls); the phone's side of it is
 * `playback/Linking` and `playback/LinkSide`, built to that document's section 8.1.
 *
 * **With it off, nothing of it exists**: no object of it is made, no listener is added to
 * the player, nothing ticks, no request is made, and the playback service behaves as it
 * did before any of this was written. (What is there whether or not it exists is one
 * number the player keeps, `QueuePlayer.whole`, which nothing else reads.)
 */
object LinkBuild {
    /** The one switch, as the build was made with it (`linkExists` in `app/build.gradle.kts`:
     *  off, unless the build was asked for it by name). */
    val EXISTS: Boolean = org.medianexus.music.BuildConfig.LINK

    /**
     * Set only by tests, and by a debug build told to by the test rig as it starts
     * (`DebugLink`, which a release build has only an empty version of). Null, as it always
     * is in a release build: the build's own answer.
     */
    @Volatile internal var forTests: Boolean? = null

    /** Whether it runs: what a test says, or [EXISTS]. */
    val on: Boolean get() = forTests ?: EXISTS
}
