package org.medianexus.music.ui.mixes

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.library.MixRules
import org.medianexus.music.library.Mixes
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The app's strings put a mix's rules in Windows' words (`Rules::summary`'s test). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MixTextTest {
    @Test
    fun theRulesInWindowsWords() {
        val words = MixText.words(RuntimeEnvironment.getApplication().resources)
        assertEquals("rock, not in the last month, most played first", Mixes.summary(MixRules(genre = "rock", played = 2, order = 1), words))
        assertEquals("never played, shuffled", Mixes.summary(MixRules(played = 1), words))
        assertEquals("Any song, shuffled", Mixes.summary(MixRules(), words))
        assertEquals("5 stars, liked, from 2010 on, added in the last six months, newest first", Mixes.summary(MixRules(rating = 5, liked = true, fromYear = 2010, added = 2, order = 2), words))
        assertEquals("3 stars or more, 1990 to 1999, not in the last year, longest since played first", Mixes.summary(MixRules(rating = 3, fromYear = 1990, toYear = 1999, played = 4, order = 3), words))
    }
}
