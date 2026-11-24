package org.medianexus.music.sound

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Windows' tests of the equaliser (src/audio/dsp.rs), ported: the level of a sine after the
 * filters, against the numbers Windows holds its own to. And each preset's response at each
 * band, so a preset is known to do on the phone what it does on Windows.
 */
class EqualiserTest {
    private fun response(gains: FloatArray, frequency: Float) = responseWith(gains, 0f, frequency)

    /** Level of a sine at `frequency` after the equaliser, in dB relative to the input. As
     *  Windows' `response_with`: the preamp before the bands, then the bands, then the soft
     *  limiter; two seconds at 48 kHz, the first second skipped while the filters settle. */
    private fun responseWith(gains: FloatArray, preampDb: Float, frequency: Float, rate: Int = 48_000): Float {
        val filters = EqFilters()
        filters.format(rate, 2)
        for (band in 0 until 6) filters.set(band, gains[band].coerceIn(-Eq.RANGE, Eq.RANGE))
        val preamp = 10f.pow(preampDb.coerceIn(-Eq.RANGE, 0f) / 20f)
        var sum = 0.0
        var count = 0
        for (n in 0 until rate * 2) {
            val v = 0.1f * sin(2.0 * PI * frequency * n / rate).toFloat()
            for (channel in 0 until 2) {
                val y = Eq.softClip(filters.run(channel, v * preamp))
                if (n >= rate) {
                    sum += y.toDouble() * y
                    count++
                }
            }
        }
        val rms = sqrt(sum / count).toFloat()
        return 20f * log10(rms / (0.1f / sqrt(2f)))
    }

    @Test
    fun flat_settings_leave_audio_untouched() {
        assertTrue(kotlin.math.abs(response(FloatArray(6), 1000f)) < 0.01f)
    }

    @Test
    fun a_band_boosts_its_own_frequency_and_little_else() {
        val gains = floatArrayOf(0f, 0f, 0f, 6f, 0f, 0f)
        assertEquals(6f, response(gains, 1000f), 0.3f)
        assertEquals(0f, response(gains, 60f), 0.5f)
        assertEquals(0f, response(gains, 12_000f), 0.5f)
    }

    @Test
    fun shelves_reach_their_gain_at_the_extremes() {
        assertEquals(-9f, response(floatArrayOf(-9f, 0f, 0f, 0f, 0f, 0f), 25f), 0.6f)
        assertEquals(8f, response(floatArrayOf(0f, 0f, 0f, 0f, 0f, 8f), 20_000f), 0.6f)
    }

    @Test
    fun soft_clip_stays_below_full_scale() {
        assertEquals(0.5f, Eq.softClip(0.5f))
        assertTrue(Eq.softClip(3f) <= 1f && Eq.softClip(3f) > 0.95f)
        assertTrue(Eq.softClip(-3f) >= -1f)
    }

    @Test
    fun the_preamp_turns_everything_down_before_the_bands() {
        // Flat bands with 6 dB of preamp: 6 dB quieter everywhere.
        assertEquals(-6f, responseWith(FloatArray(6), -6f, 1000f), 0.2f)
        // A 6 dB boost at 1 kHz with 6 dB of preamp comes out level there, and quieter elsewhere.
        val boosted = floatArrayOf(0f, 0f, 0f, 6f, 0f, 0f)
        assertEquals(0f, responseWith(boosted, -6f, 1000f), 0.3f)
        assertEquals(-6f, responseWith(boosted, -6f, 60f), 0.5f)
        // It only ever turns down.
        assertEquals(0f, responseWith(FloatArray(6), 6f, 1000f), 0.2f)
    }

    /**
     * Every preset at every band's own frequency, against the response worked out from the
     * same filters in double precision. That's what Windows plays (its filters are these, in
     * 32-bit floats as here); the bands overlap, so a band's level is not simply its own
     * setting, and the designed number is the one to hold the phone to. Also at 44.1 kHz,
     * which moves nothing that matters.
     */
    @Test
    fun each_preset_measures_as_designed_at_each_band() {
        for (preset in org.medianexus.music.store.EQ_PRESETS) {
            val gains = preset.toFloatArray()
            for (band in 0 until 6) {
                val f = Eq.BANDS[band]
                val designed = designedDb(gains, f, 48_000.0)
                assertEquals("preset $preset at $f Hz", designed, responseWith(gains, 0f, f).toDouble(), 0.15)
            }
            val designed = designedDb(gains, 1000f, 44_100.0)
            assertEquals("preset $preset at 1 kHz, 44.1 kHz", designed, responseWith(gains, 0f, 1000f, 44_100).toDouble(), 0.15)
        }
    }

    companion object {
        /** The magnitude response of the six biquads at `f`, in dB, worked out exactly from
         *  the cookbook's formulas (independently of [EqFilters]). */
        fun designedDb(gains: FloatArray, f: Float, rate: Double): Double {
            var total = 0.0
            for (band in 0 until 6) {
                val a = 10.0.pow(gains[band] / 40.0)
                val w0 = 2 * PI * minOf(Eq.BANDS[band].toDouble(), rate * 0.45) / rate
                val sn = kotlin.math.sin(w0)
                val cs = kotlin.math.cos(w0)
                val c: DoubleArray = when (band) {
                    0 -> {
                        val beta = 2 * sqrt(a) * (sn / 2 * sqrt(2.0))
                        doubleArrayOf(a * ((a + 1) - (a - 1) * cs + beta), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - beta), (a + 1) + (a - 1) * cs + beta, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - beta)
                    }
                    5 -> {
                        val beta = 2 * sqrt(a) * (sn / 2 * sqrt(2.0))
                        doubleArrayOf(a * ((a + 1) + (a - 1) * cs + beta), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - beta), (a + 1) - (a - 1) * cs + beta, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - beta)
                    }
                    else -> {
                        val alpha = sn / 2
                        doubleArrayOf(1 + alpha * a, -2 * cs, 1 - alpha * a, 1 + alpha / a, -2 * cs, 1 - alpha / a)
                    }
                }
                val w = 2 * PI * f / rate
                // |H(e^jw)| for b0 + b1 z^-1 + b2 z^-2 over a0 + a1 z^-1 + a2 z^-2.
                fun mag(x0: Double, x1: Double, x2: Double): Double {
                    val re = x0 + x1 * kotlin.math.cos(w) + x2 * kotlin.math.cos(2 * w)
                    val im = -x1 * kotlin.math.sin(w) - x2 * kotlin.math.sin(2 * w)
                    return sqrt(re * re + im * im)
                }
                total += 20 * log10(mag(c[0], c[1], c[2]) / mag(c[3], c[4], c[5]))
            }
            return total
        }
    }
}
