package org.medianexus.music.playback

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.test.utils.FakeClock
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

/**
 * A player on a made-up clock, as Media3's `TestExoPlayerBuilder` makes one (the same clock
 * that moves on by itself, track selector, load control and looper), playing through
 * `renderers`, with **Media3's stuck-player detector switched off**.
 *
 * The detector calls a player stuck when ten minutes pass, *by the player's clock*, with it
 * buffering and nothing more loaded. On a made-up clock that moves on by itself those ten
 * minutes are the playback thread asking "ready yet?" sixty thousand times, every ten made-up
 * milliseconds, which it can do in well under a second of real time; and the loading thread
 * is real and on no made-up clock, so on a busy machine it can go that long without being
 * given the processor. The player then failed with `StuckPlayerException`
 * (STUCK_BUFFERING_NO_PROGRESS) though nothing was stuck. `TestExoPlayerBuilder` has no way
 * to set that time, so tests that run a player on a made-up clock make it here. The app's
 * own player is on the real clock, where ten minutes are ten minutes, and keeps the detector.
 */
fun madeUpClockPlayer(
    context: Context,
    mediaSources: MediaSource.Factory,
    vararg renderers: Renderer,
    clock: FakeClock = FakeClock(/* isAutoAdvancing = */ true),
): ExoPlayer = ExoPlayer.Builder(context, { _, _, _, _, _ -> arrayOf(*renderers) }, mediaSources)
    .setClock(clock)
    .setTrackSelector(DefaultTrackSelector(context))
    .setLoadControl(DefaultLoadControl())
    .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).build())
    .setAnalyticsCollector(DefaultAnalyticsCollector(clock))
    .setLooper(Looper.myLooper() ?: Looper.getMainLooper())
    .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
    .setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
    .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE)
    .setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE)
    .build()
