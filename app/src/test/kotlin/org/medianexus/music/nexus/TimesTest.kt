package org.medianexus.music.nexus

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The limits the suite's timings are held to, against the released app's own numbers. */
class TimesTest {
    @Test
    fun everyReleasedSampleIsWithinItsLimitsAndAWaitOfFourHundredMillisecondsIsNot() {
        val medians = Times.released
        assumeTrue("E13a's numbers are not kept yet", medians != null)
        val samples = Times::class.java.getResourceAsStream("/nexus/released-times.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.filter { it[1] != "WARM_UP" }
        for (sample in samples) {
            val limit = Times.held(sample[0], sample[2])!!
            assertTrue("${sample.joinToString(" ")}: over its own limit ${"%.0f".format(limit)}", sample[3].toDouble() <= limit)
        }
        val byStep = samples.groupBy({ it[0] to it[2] }, { it[3].toDouble() })
        for ((key, values) in byStep) {
            // The released app's own cases are not over the run's limit; 400 ms more in each is.
            assertTrue("$key: the released app's own over its limit", !Times.overRun(key.first, key.second, values))
            assertTrue("$key: 400 ms more in every case not caught", Times.overRun(key.first, key.second, values.map { it + 400 }))
            // One case slowed by the machine, alone, is not a run over its limit.
            assertTrue("$key: one slow case taken for a run over its limit", !Times.overRun(key.first, key.second, values.take(9).mapIndexed { i, v -> if (i == 0) v + 900 else v }))
        }
    }
}
