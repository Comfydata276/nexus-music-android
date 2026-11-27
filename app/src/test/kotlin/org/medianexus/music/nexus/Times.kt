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
     * What the app as released took for each test and step, across every way the backend
     * was broken: the slowest. Null before E13a's numbers were kept (when the suite is first
     * run, to measure them).
     */
    val released: Map<Pair<String, String>, Double>? by lazy {
        val stream = Times::class.java.getResourceAsStream("/nexus/released-times.tsv") ?: return@lazy null
        stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .groupBy({ it[0] to it[2] }, { it[3].toDouble() })
            .mapValues { (_, all) -> all.max() }
    }

    /**
     * How much slower than the released app's slowest a step may be: three times it, and half
     * a second more. Room for what a shared build machine does to a test's wall-clock time
     * (the released app's own numbers for one step vary by half or more between runs, and the
     * first test in a fresh process is slower), and far below anything that waited on the
     * backend: its client's shortest limit is eight seconds, and every way of breaking it here
     * holds a request for longer than any test runs.
     */
    const val TIMES = 3.0
    const val MORE_MILLIS = 500.0

    /** The limit for a step, or null if there is no released number to hold it to. */
    fun held(test: String, step: String): Double? = released?.get(test to step)?.let { it * TIMES + MORE_MILLIS }
}
