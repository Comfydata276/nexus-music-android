package org.medianexus.music.sync

/**
 * Whether keeping in step with the person's other devices exists in this build. **It does
 * not, yet.** The code for it is built (this package; "Keep my devices in step" under
 * Settings, Account; the queue saved on the server and "Continue here"), and its rules are
 * no longer the phone's own: they are the shared core's, the same code the Windows app runs
 * (`core/Core.kt`, docs/DESIGN.md "The shared core"). It stays switched off until that has
 * been reviewed; until then nothing of it may run on a phone.
 *
 * With it off: the setting is not shown; the account makes no exchange, no saved queue and
 * no "Continue here" ([org.medianexus.music.AccountGraph.step] and `queueStep` are null), so
 * nothing starts one, nothing is scheduled for one, and nothing is asked of the server for
 * one: the sync playlist is never looked for, read, made or written, and the server's saved
 * queue is neither written nor read. What stays on whether or not it exists, because it
 * protects the person: the sync playlist is never listed, played, downloaded or edited here,
 * and its name is reserved ([PLAYLIST_NAME]). The pins, each list's choices and the theme are
 * the phone's own settings, as they always were; a change to one is also kept, as the
 * change, by the shared core ([Chosen]), where it simply waits for the day it is shared.
 * And the notes of when songs were added to playlists are kept by the core's rules either
 * way (`store/Added.kt`): "Date added" does not depend on this switch.
 */
object InStepBuild {
    /** The one switch, for the whole build. */
    const val EXISTS = false

    /**
     * Set only by tests: `true` by the tests of the feature itself, so that its code goes
     * on being tested while it is switched off (the unit tests, and a debug build told to
     * by the test rig, as it starts: `DebugStep`, which a release build has only an empty
     * version of); `false` by the tests of what "switched off" means, so that they go on
     * proving it once [EXISTS] is true. Null, as it always is in a release build: the
     * build's own answer.
     */
    @Volatile internal var forTests: Boolean? = null

    /** Whether it runs: what a test says, or [EXISTS]. */
    val on: Boolean get() = forTests ?: EXISTS
}
