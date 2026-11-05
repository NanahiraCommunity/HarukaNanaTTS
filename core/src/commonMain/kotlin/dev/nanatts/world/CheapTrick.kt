package dev.nanatts.world

import dev.nanatts.dsp.FFT
import dev.nanatts.util.jround
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * F0-adaptive spectral envelope estimation after WORLD's CheapTrick
 * (pitch-synchronous Hann window, DC correction, linear smoothing, cepstral liftering with q1 compensation).
 *
 * Output is a power spectrum normalised so that white noise of variance s^2 yields a flat envelope of
 * value s^2, i.e. independent of window length and FFT size. Not thread-safe; use one instance per thread.
 */
class CheapTrick(private val fs: Int, f0Floor: Double) {

    val fftSize: Int = FFT.nextPow2(ceil(3.0 * fs / f0Floor).toInt() + 1)
    private val fft = FFT(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val work = DoubleArray(fftSize / 2 + 1)

    /** Estimates the envelope at [t] seconds with fundamental [f0] (must be > 0). */
    fun estimate(x: FloatArray, t: Double, f0: Double, out: DoubleArray) {
        val half = jround(1.5 * fs / f0)
        val center = jround(t * fs)
        var sumSeg = 0.0
        var sumWin = 0.0
        var sumWin2 = 0.0
        re.fill(0.0)
        im.fill(0.0)
        val win = DoubleArray(2 * half + 1)
        for (i in -half..half) {
            val w = 0.5 * cos(PI * i / fs.toDouble() * f0 / 1.5) + 0.5
            val j = minOf(maxOf(0, center + i), x.size - 1)
            val v = x[j] * w
            win[i + half] = w
            re[i + half] = v
            sumSeg += v
            sumWin += w
            sumWin2 += w * w
        }
        val dc = sumSeg / sumWin
        for (i in win.indices) {
            re[i] -= win[i] * dc
        }
        fft.forward(re, im)
        val bins = fftSize / 2 + 1
        val power = out
        for (k in 0 until bins) {
            power[k] = (re[k] * re[k] + im[k] * im[k]) / sumWin2
        }
        dcCorrection(power, f0)
        linearSmoothing(power, f0 * 2.0 / 3.0)
        for (k in 0 until bins) {
            power[k] += 1e-16
        }
        cepstralSmoothing(power, f0)
    }

    /** Folds energy below f0 back so that the DC region is not underestimated. */
    private fun dcCorrection(power: DoubleArray, f0: Double) {
        val bins = fftSize / 2 + 1
        val df = fs / fftSize.toDouble()
        val limit = (f0 / df).toInt() + 1
        power.copyInto(work, 0, 0, bins)
        for (k in 0 until minOf(limit, bins)) {
            val pos = (f0 - k * df) / df
            val i0 = floor(pos).toInt()
            val frac = pos - i0
            if (i0 + 1 < bins && i0 >= 0) {
                power[k] = work[k] + work[i0] * (1 - frac) + work[i0 + 1] * frac
            }
        }
    }

    /** Rectangular smoothing of the given width (Hz) using an integrated, mirrored spectrum. */
    private fun linearSmoothing(power: DoubleArray, width: Double) {
        val bins = fftSize / 2 + 1
        val df = fs / fftSize.toDouble()
        val pad = (width / df).toInt() + 2
        val len = bins + 2 * pad
        val ext = DoubleArray(len)
        for (i in 0 until len) {
            var k = i - pad
            if (k < 0) {
                k = -k
            } else if (k >= bins) {
                k = 2 * (bins - 1) - k
            }
            ext[i] = power[maxOf(0, minOf(bins - 1, k))]
        }
        val cum = DoubleArray(len + 1)
        for (i in 0 until len) {
            cum[i + 1] = cum[i] + ext[i]
        }
        val halfW = width / df / 2.0
        for (k in 0 until bins) {
            val a = k + pad - halfW + 0.5
            val b = k + pad + halfW + 0.5
            power[k] = (interp(cum, b) - interp(cum, a)) / (b - a)
        }
    }

    private fun interp(arr: DoubleArray, pos: Double): Double {
        val i = floor(pos).toInt()
        if (i < 0) {
            return arr[0]
        }
        if (i >= arr.size - 1) {
            return arr[arr.size - 1]
        }
        val f = pos - i
        return arr[i] * (1 - f) + arr[i + 1] * f
    }

    private fun cepstralSmoothing(power: DoubleArray, f0: Double) {
        val bins = fftSize / 2 + 1
        for (k in 0 until bins) {
            re[k] = ln(power[k])
            im[k] = 0.0
        }
        for (k in bins until fftSize) {
            re[k] = re[fftSize - k]
            im[k] = 0.0
        }
        fft.inverse(re, im)
        for (q in 1..fftSize / 2) {
            val quef = q / fs.toDouble()
            val x = PI * f0 * quef
            val smoothing = sin(x) / x
            val compensation = (1.0 - 2.0 * Q1) + 2.0 * Q1 * cos(2.0 * PI * quef * f0)
            val l = smoothing * compensation
            re[q] *= l
            if (q < fftSize / 2) {
                re[fftSize - q] *= l
            }
        }
        im.fill(0.0)
        fft.forward(re, im)
        for (k in 0 until bins) {
            power[k] = exp(re[k])
        }
    }

    private companion object {
        const val Q1 = -0.15
    }
}
