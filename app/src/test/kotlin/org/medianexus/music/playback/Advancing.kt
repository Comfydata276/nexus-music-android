package org.medianexus.music.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper

/**
 * Media3's `advance(player)`, which runs a player on its own made-up clock until something
 * has happened, without the ten seconds of the *real* clock Media3 gives it to get there.
 *
 * The player's clock is made up, but its threads are real (playback, loading), and so is the
 * time the machine takes to run them. On a machine busy with something else (an emulator
 * playing, another build) ten seconds for a hundred seconds of a song has been too few, and
 * the test failed with a `TimeoutException` though nothing was wrong. No test here is about
 * how long the machine takes, so none passes or fails by it: the limit that is left is only
 * there so that a player which will never get there fails the test and does not hang the
 * build.
 */
fun advance(exo: ExoPlayer): TestPlayerRunHelper.ExoPlayerRunResult = TestPlayerRunHelper.advance(exo).withTimeoutMs(NOT_FOR_EVER_MILLIS)

/** Long past anything a working test takes on the slowest machine it is run on. */
const val NOT_FOR_EVER_MILLIS = 5 * 60_000L
