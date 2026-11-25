package org.medianexus.music.sync

/**
 * Whether keeping in step with the person's other devices exists in this build. **It does
 * not.** The code for it is built (this package; "Keep my devices in step" under Settings,
 * Account; the queue saved on the server and "Continue here"), but it is not yet safe to let
 * it write into the record the person's PCs trust, and its rules are to be rewritten once, in
 * Rust shared with the Windows app, which will replace the Kotlin logic here. Until then
 * nothing of it may run on a phone.
 *
 * With it off: the setting is not shown; the account makes no exchange, no saved queue and
 * no "Continue here" ([org.medianexus.music.AccountGraph.step] and `queueStep` are null), so
 * nothing starts one, nothing is scheduled for one, and nothing is asked of the server for
 * one: the sync playlist is never looked for, read, made or written, and the server's saved
 * queue is neither written nor read. What stays on whether or not it exists, because it
 * protects the person: the sync playlist is never listed, played, downloaded or edited here,
 * and its name is reserved ([PLAYLIST_NAME]). The pins, each list's choices and the theme are
 * the phone's own settings, as they always were (see [Changes]: what is shown is the
 * settings' own; the changes waiting to be shared simply wait).
 */
object InStepBuild {
    /** The one switch, for the whole build. */
    const val EXISTS = false

    /** Set only by tests of the feature itself, so that its code goes on being tested while
     *  it is switched off. Nothing in the app sets it. */
    @Volatile internal var forTests = false

    /** Whether it runs: [EXISTS], or a test of it. */
    val on: Boolean get() = EXISTS || forTests
}
