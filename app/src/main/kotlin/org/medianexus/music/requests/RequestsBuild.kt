package org.medianexus.music.requests

/**
 * Whether requests (asking Lidarr for music that isn't in the library) exist in this build.
 * **They do not, yet**: what is below the screens is here (the connection to Lidarr and the
 * sign-in that can sit in front of it, and what the screens will ask of them), and the
 * screens are still to come. The switch is one line, as the other two are
 * ([org.medianexus.music.sync.InStepBuild], [org.medianexus.music.jam.JamBuild]).
 *
 * With it off: [org.medianexus.music.AccountGraph.requests] is null, so nothing of requests
 * is read from the phone, nothing is kept, no address is ever asked anything, the client
 * for the library's requests is never made and the library is never told of it.
 *
 * With it on, they are still **off until the person switches them on** in Settings and
 * gives an address: until then the same holds.
 */
object RequestsBuild {
    /** The one switch, for the whole build. */
    const val EXISTS = false

    /** Set only by tests: `true` by the tests of requests themselves and by a debug build
     *  told to by the test rig, `false` by the tests of what "switched off" means. Null, as
     *  it always is in a release build: the build's own answer. */
    @Volatile internal var forTests: Boolean? = null

    /** Whether they run: what a test says, or [EXISTS]. */
    val on: Boolean get() = forTests ?: EXISTS
}
