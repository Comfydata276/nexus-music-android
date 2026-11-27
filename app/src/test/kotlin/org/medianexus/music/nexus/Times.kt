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

    /** This run's own lines carry the process's number, so a run is told from those before it. */
    private val run: Long = ProcessHandle.current().pid()

    @Synchronized
    fun record(test: String, case: String, step: String, millis: Double) {
        file.appendText("$test\t$case\t$step\t${"%.1f".format(java.util.Locale.ROOT, millis)}\t$run\n")
    }

    /** What this run has taken for `step` of `test` so far, in every case but the warm-up. */
    @Synchronized
    fun thisRun(test: String, step: String): List<Double> = file.readLines()
        .map { it.split('\t') }
        .filter { it.size >= 5 && it[4] == run.toString() && it[0] == test && it[2] == step && it[1] != "WARM_UP" }
        .map { it[3].toDouble() }

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
            .mapValues { (_, all) -> middle(all) }
    }

    /**
     * **The alarm, for a run.** A dependency on the backend's client would slow a step in every
     * case in which the client is held, not in one: so the median of a step across the cases of
     * a run (once three are in) is held to the released app's median and 300 ms. A wait of 400
     * ms in an entry point is over it (`TimesTest`, and the suite's own
     * `aWaitOfFourHundredMillisecondsInAnEntryPointIsCaught`). Every one of the released app's
     * own samples is within its median and 300 ms, the farthest by 184 ms.
     */
    const val MORE_MILLIS = 300.0

    /**
     * **The backstop, for one case:** the released median and one second. One case alone can be
     * slowed by the machine (the build machine is shared: a step that takes 20 ms has taken 444
     * once, and a 107 ms one 424, with nothing of the client's in the way: see the report of P1's
     * second round), and anything that waited on the client's own limits (8 seconds and more)
     * is far over it.
     */
    const val ONE_CASE_MORE_MILLIS = 1_000.0

    /** The run's limit for a step: its released median and [MORE_MILLIS]. Null before E13a's
     *  numbers were kept; a step with no released number, once they are, fails. */
    fun held(test: String, step: String): Double? = median(test, step)?.let { it + MORE_MILLIS }

    /** One case's limit for a step: its released median and [ONE_CASE_MORE_MILLIS]. */
    fun heldOneCase(test: String, step: String): Double? = median(test, step)?.let { it + ONE_CASE_MORE_MILLIS }

    private fun median(test: String, step: String): Double? {
        val kept = released ?: return null
        return kept[test to step] ?: throw AssertionError("$test, $step: no number from the released app to hold it to")
    }

    /** Whether `samples` of a step, from the cases of one run, are over the run's limit: their
     *  median, once there are three. */
    fun overRun(test: String, step: String, samples: List<Double>): Boolean {
        val limit = held(test, step) ?: return false
        if (samples.size < 3) return false
        return middle(samples) > limit
    }

    fun middle(values: List<Double>): Double = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
}
