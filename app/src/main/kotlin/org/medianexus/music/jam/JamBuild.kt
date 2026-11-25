package org.medianexus.music.jam

/**
 * Whether jams and linked devices exist in this build. **They do not**: the one switch is
 * `jamsExist` in app/build.gradle.kts, which gives [EXISTS] and decides whether their
 * activity and service are in the APK's manifest at all (see there for why).
 *
 * With them off nothing of them shows (no Jam in Settings, Library or the strip, no "Connect
 * to a device", no route to the jam page) and nothing of them runs: [org.medianexus.music.Graph.jams]
 * is never made, so no relay is ever asked anything, no room joined, no session opened, no
 * timer set, no key asked for, kept or read, and nothing about them written. The player's
 * other player ([org.medianexus.music.playback.Steered]) is never shown, so the media session,
 * the notification, the car and a headset act on the phone's own queue, as before M6.
 */
object JamBuild {
    /** The switch, as the build was made with it. */
    val EXISTS: Boolean = org.medianexus.music.BuildConfig.JAMS

    /** Set only by tests of jams themselves, so that their code goes on being tested while
     *  they are switched off. Nothing in the app sets it. */
    @Volatile internal var forTests = false

    /** Whether they run: [EXISTS], or a test of them. */
    val on: Boolean get() = EXISTS || forTests
}
