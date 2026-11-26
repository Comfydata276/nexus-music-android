package org.medianexus.music.requests

/**
 * Whether requests (asking Lidarr for music that isn't in the library) exist in this build.
 * **They do, from 0.6.0**, and are **off until the person switches them on** in Settings:
 * until then nothing of requests is made for an account, read from the phone, kept or
 * asked of anyone (see [RequestsOwners]; one look for a file at each sign-in is all).
 *
 * The switch stays, one line as the other two are
 * ([org.medianexus.music.sync.InStepBuild], [org.medianexus.music.jam.JamBuild]), so that a
 * build can be made without them. With it off an account has not even a seat at its
 * requests: [org.medianexus.music.AccountGraph.requests] is null, what owns requests is
 * never asked for, no screen of them is reachable, the client for the library's requests
 * is never made and the library is never told of it.
 */
object RequestsBuild {
    /** The one switch, for the whole build. */
    const val EXISTS = true

    /** Set only by tests: `false` by the tests of a build made without requests; `true`
     *  by tests written while no build had them, and by a debug build told to by the test
     *  rig (which changes nothing now). Null, as it always is in a release build: the
     *  build's own answer. */
    @Volatile internal var forTests: Boolean? = null

    /** Whether they run: what a test says, or [EXISTS]. */
    val on: Boolean get() = forTests ?: EXISTS
}
