package org.medianexus.music.sound

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.library.CopyStamp
import org.medianexus.music.library.LibrarySnapshot
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.store.SongTable
import org.robolectric.RobolectricTestRunner

/**
 * Rule 5 of the fixes: what the phone falls back on for a song that came without levelling
 * values (read from the library copy) belongs to an account. It goes the moment another
 * account takes over, a look still going for the last one is called off, and it is filled
 * when the copy arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerSoundTest {
    private val gains = ReplayGain(trackGain = -7f, trackPeak = 0.9f)

    /** The same id on two servers: an old download with no values of its own. */
    private val bare = Song(id = "s1", title = "S")

    private fun copyWith(vararg songs: Song) = LibrarySnapshot(
        SongTable.Builder().apply { songs.forEach { add(it) } }.build(),
        emptyList(), emptyList(), CopyStamp(0, "", 0), complete = true,
    )

    @Test
    fun the_fallback_values_are_the_accounts_and_go_with_it() = runTest {
        val sound = PlayerSound(this, StandardTestDispatcher(testScheduler))
        val first = Any()
        val second = Any()
        sound.account(first)
        // No copy yet: nothing to go by; the copy arrives: the song's values are found.
        sound.songsHeld(listOf(bare), null)
        advanceUntilIdle()
        assertNull(sound.tagsOf(bare))
        sound.songsHeld(listOf(bare), copyWith(bare.copy(replayGain = gains)))
        advanceUntilIdle()
        assertEquals(gains, sound.tagsOf(bare))
        // Another account takes over: at once, nothing of the first one's.
        sound.account(second)
        assertNull(sound.tagsOf(bare))
        // A look begun for one account and overtaken by another doesn't land for the second.
        sound.account(first)
        sound.songsHeld(listOf(bare), copyWith(bare.copy(replayGain = gains)))
        sound.account(second)
        advanceUntilIdle()
        assertNull(sound.tagsOf(bare))
    }
}
