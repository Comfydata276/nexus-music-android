package org.medianexus.music.nexus

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The limits the suite's timings are held to, against the released app's own numbers. */
class TimesTest {
    @Test
    fun everyReleasedSampleIsWithinItsLimitAndAWaitOfFourHundredMillisecondsIsNot() {
        val medians = Times.released
        assumeTrue("E13a's numbers are not kept yet", medians != null)
        val samples = Times::class.java.getResourceAsStream("/nexus/released-times.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.filter { it[1] != "WARM_UP" }
        for (sample in samples) {
            val limit = Times.held(sample[0], sample[2])!!
            assertTrue("${sample.joinToString(" ")}: over its own limit ${"%.0f".format(limit)}", sample[3].toDouble() <= limit)
        }
        for ((key, median) in medians!!) {
            assertTrue("$key: a 400 ms wait on a median run would pass", median + 400 > Times.held(key.first, key.second)!!)
        }
    }
}
