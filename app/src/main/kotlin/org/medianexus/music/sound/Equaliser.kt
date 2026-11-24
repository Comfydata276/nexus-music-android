package org.medianexus.music.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The six-band equaliser, as Windows has it (src/audio/dsp.rs): the same bands, the same
 * filter shapes and the same arithmetic, so a preset sounds the same on both. Its tests are
 * Windows' tests.
 */
object Eq {
    /** Centre frequencies of the bands, in Hz. The first and last are shelves. */
    val BANDS = floatArrayOf(60f, 150f, 400f, 1000f, 2400f, 15000f)

    /** Furthest a band can be raised or lowered, in dB. */
    const val RANGE = 12f

    /** Keeps peaks under full scale by rounding them off instead of chopping them flat. */
    fun softClip(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= KNEE) return x
        val over = (magnitude - KNEE) / (1f - KNEE)
        val rounded = KNEE + (1f - KNEE) * tanh(over)
        return if (x < 0f) -rounded else rounded
    }

    private const val KNEE = 0.8f
}

/**
 * The bands' filters for one stream: coefficients and memory, in arrays made once for a
 * format, so running them makes nothing. Biquads from the Audio EQ Cookbook (Robert
 * Bristow-Johnson) in transposed direct form II, as Windows' `Biquad`.
 */
class EqFilters {
    private val b0 = FloatArray(BANDS)
    private val b1 = FloatArray(BANDS)
    private val b2 = FloatArray(BANDS)
    private val a1 = FloatArray(BANDS)
    private val a2 = FloatArray(BANDS)
    private val sinW = FloatArray(BANDS)
    private val cosW = FloatArray(BANDS)

    /** Filter memory, band by band, a pair for each channel. */
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)
    private var channels = 0

    /** Sets the stream's sample rate and channel count: the only time anything is made. */
    fun format(rate: Int, channels: Int) {
        val r = maxOf(rate, 8000).toFloat()
        for (band in 0 until BANDS) {
            val w = 2f * PI.toFloat() * minOf(Eq.BANDS[band], r * 0.45f) / r
            sinW[band] = sin(w)
            cosW[band] = cos(w)
        }
        if (channels * BANDS > z1.size) {
            z1 = FloatArray(channels * BANDS)
            z2 = FloatArray(channels * BANDS)
        }
        this.channels = channels
        clear()
        for (band in 0 until BANDS) set(band, 0f)
    }

    /** Forgets what has passed through: after a seek, or a new format. */
    fun clear() {
        z1.fill(0f)
        z2.fill(0f)
    }

    /** Sets one band's gain, in dB. Windows' `Biquad::set`. */
    fun set(band: Int, gainDb: Float) {
        val a = 10f.pow(gainDb / 40f)
        val sin = sinW[band]
        val cos = cosW[band]
        val nb0: Float
        val nb1: Float
        val nb2: Float
        val na0: Float
        val na1: Float
        val na2: Float
        when (band) {
            0 -> {
                val beta = 2f * sqrt(a) * (sin / 2f * sqrt(2f))
                nb0 = a * ((a + 1f) - (a - 1f) * cos + beta)
                nb1 = 2f * a * ((a - 1f) - (a + 1f) * cos)
                nb2 = a * ((a + 1f) - (a - 1f) * cos - beta)
                na0 = (a + 1f) + (a - 1f) * cos + beta
                na1 = -2f * ((a - 1f) + (a + 1f) * cos)
                na2 = (a + 1f) + (a - 1f) * cos - beta
            }
            BANDS - 1 -> {
                val beta = 2f * sqrt(a) * (sin / 2f * sqrt(2f))
                nb0 = a * ((a + 1f) + (a - 1f) * cos + beta)
                nb1 = -2f * a * ((a - 1f) + (a + 1f) * cos)
                nb2 = a * ((a + 1f) + (a - 1f) * cos - beta)
                na0 = (a + 1f) - (a - 1f) * cos + beta
                na1 = 2f * ((a - 1f) - (a + 1f) * cos)
                na2 = (a + 1f) - (a - 1f) * cos - beta
            }
            else -> {
                val alpha = sin / 2f // Q of 1: bands about an octave and a third apart
                nb0 = 1f + alpha * a
                nb1 = -2f * cos
                nb2 = 1f - alpha * a
                na0 = 1f + alpha / a
                na1 = -2f * cos
                na2 = 1f - alpha / a
            }
        }
        b0[band] = nb0 / na0
        b1[band] = nb1 / na0
        b2[band] = nb2 / na0
        a1[band] = na1 / na0
        a2[band] = na2 / na0
    }

    /** One sample of one channel through all six bands. */
    fun run(channel: Int, input: Float): Float {
        var x = input
        var at = channel * BANDS
        for (band in 0 until BANDS) {
            val y = b0[band] * x + z1[at]
            z1[at] = b1[band] * x - a1[band] * y + z2[at]
            z2[at] = b2[band] * x - a2[band] * y
            x = y
            at++
        }
        return x
    }

    private companion object {
        const val BANDS = 6
    }
}
