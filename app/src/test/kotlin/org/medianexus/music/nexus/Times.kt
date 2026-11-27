package org.medianexus.music.nexus

import java.io.File

/**
 * The times the tests of "what never waits for the backend" (docs/backend-design.md, 5.3)
 * took, and what they are held to.
 *
 * Every run writes what it measured to `build/nexus-times/<tag>.tsv` (tag: the system property
 * `nexus.times`, or "run"): one line per test, case and step, in milliseconds. The numbers
 * measured on the app as released (experiment E13a) are kept in the test resources,
 * `nexus/released-times.tsv`; each later run is held to them (see [held]).
 */
object Times {
    private val tag: String = System.getProperty("nexus.times") ?: "run"

    private val file: File by lazy {
        File(System.getProperty("user.dir"), "build/nexus-times/$tag.tsv").apply { parentFile!!.mkdirs() }
    }

    @Synchronized
    fun record(test: String, case: String, step: String, millis: Double) {
        file.appendText("$test\t$case\t$step\t${"%.1f".format(java.util.Locale.ROOT, millis)}\n")
    }

    /**
     * What the app as released took for each test and step: the median of its samples across
     * every way the backend was broken and every run, the first case (WARM_UP, the first pass in
     * a fresh process) left out. Null before E13a's numbers were kept.
     */
    val released: Map<Pair<String, String>, Double>? by lazy {
        val stream = Times::class.java.getResourceAsStream("/nexus/released-times.tsv") ?: return@lazy null
        stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it[1] != "WARM_UP" }
            .groupBy({ it[0] to it[2] }, { it[3].toDouble() })
            .mapValues { (_, all) -> all.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 } }
    }

    /**
     * How much slower than the released app's median a step may be: 300 ms. Every one of the
     * released app's own samples (but the warm-up's) is within it, the slowest by 243 ms; and a
     * step held up by 400 ms or more is over it (`aWaitOfFourHundredMillisecondsInAnEntryPointIsCaught`,
     * and `TimesTest`). This is the second alarm: the proof that nothing waits on the backend is
     * the holds (see `ComponentHolds`).
     */
    const val MORE_MILLIS = 300.0

    /** The limit for a step; null before E13a's numbers were kept. A step with no released
     *  number, once they are kept, fails: every step the suite times must have one. */
    fun held(test: String, step: String): Double? {
        val kept = released ?: return null
        val median = kept[test to step] ?: throw AssertionError("$test, $step: no number from the released app to hold it to")
        return median + MORE_MILLIS
    }
}
