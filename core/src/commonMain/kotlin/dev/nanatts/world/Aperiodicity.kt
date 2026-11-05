package dev.nanatts.world

import dev.nanatts.dsp.FFT
import dev.nanatts.util.jround
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

/**
 * Band aperiodicity estimation (a simplified stand-in for WORLD's D4C).
 *
 * For voiced frames, a Blackman window spanning eight periods resolves individual harmonics; per harmonic,
 * the sinusoid power (from the peak) and the noise power (from the inter-harmonic valley) are compared.
 * The result per band is the aperiodic power fraction `ap^2 = noise / (noise + harmonic)`, which is
 * exactly how [WorldSynthesizer] splits the envelope into periodic and aperiodic parts.
 * Not thread-safe.
 */
class Aperiodicity(private val fs: Int, bands: DoubleArray, minApDb: Double) {

    private val bands = bands.copyOf()
    private val minAp2 = 10.0.pow(minApDb / 10)
    private var re = DoubleArray(0)
    private var im = DoubleArray(0)
    private var fft: FFT? = null

    fun bands(): DoubleArray = bands.copyOf()

    /** Writes `ap^2` per band to [out]. Unvoiced frames (`f0 <= 0`) are fully aperiodic. */
    fun estimate(x: FloatArray, t: Double, f0: Double, out: DoubleArray) {
        if (f0 <= 0) {
            out.fill(1.0)
            return
        }
        val nw = jround(8.0 * fs / f0) or 1
        val n = FFT.nextPow2(nw * 2)
        var fft = this.fft
        if (fft == null || fft.size != n) {
            fft = FFT(n)
            this.fft = fft
            re = DoubleArray(n)
            im = DoubleArray(n)
        }
        val re = re
        val im = im
        re.fill(0.0)
        im.fill(0.0)
        val center = jround(t * fs)
        val half = nw / 2
        var s1 = 0.0
        var s2 = 0.0
        var mean = 0.0
        val w = DoubleArray(nw)
        for (i in 0 until nw) {
            val a = 2 * PI * i / (nw - 1)
            w[i] = 0.42 - 0.5 * cos(a) + 0.08 * cos(2 * a)
            val j = minOf(maxOf(0, center - half + i), x.size - 1)
            re[i] = x[j].toDouble()
            mean += x[j] * w[i]
            s1 += w[i]
            s2 += w[i] * w[i]
        }
        mean /= s1
        for (i in 0 until nw) {
            re[i] = (re[i] - mean) * w[i]
        }
        fft.forward(re, im)
        val bins = n / 2 + 1
        val p = re // reuse: power in re[0..bins)
        for (k in 0 until bins) {
            p[k] = re[k] * re[k] + im[k] * im[k]
        }
        val binsPerHarmonic = f0 * n / fs
        val noise = DoubleArray(bands.size)
        val total = DoubleArray(bands.size)
        val nyq = minOf(fs / 2.0, bands[bands.size - 1] + 2000)
        var h = 1
        while (h * f0 < nyq - f0) {
            val kc = h * binsPerHarmonic
            var peak = 0.0
            val lo = floor(kc - 0.25 * binsPerHarmonic).toInt()
            val hi = ceil(kc + 0.25 * binsPerHarmonic).toInt()
            for (k in maxOf(0, lo)..minOf(bins - 1, hi)) {
                peak = maxOf(peak, p[k])
            }
            var valley = 0.0
            var cnt = 0
            val vlo = jround(kc + 0.4 * binsPerHarmonic)
            val vhi = jround(kc + 0.6 * binsPerHarmonic)
            for (k in vlo..minOf(bins - 1, vhi)) {
                valley += p[k]
                cnt++
            }
            if (cnt == 0) {
                break
            }
            valley /= cnt
            val noisePow = valley / s2 * 2.0 * binsPerHarmonic / n
            val harmPow = maxOf(0.0, 2.0 * peak / (s1 * s1) - noisePow)
            val freq = h * f0
            for (b in bands.indices) {
                val wgt = triangle(freq, b)
                if (wgt > 0) {
                    noise[b] += wgt * noisePow
                    total[b] += wgt * (noisePow + harmPow)
                }
            }
            h++
        }
        for (b in bands.indices) {
            val ap2 = if (total[b] > 0) noise[b] / total[b] else if (b == 0) minAp2 else 1.0
            out[b] = maxOf(minAp2, minOf(1.0, ap2))
        }
        // bands without any harmonic (e.g. below f0) inherit from their neighbour
        for (b in bands.indices) {
            if (total[b] <= 0 && b + 1 < bands.size && total[b + 1] > 0) {
                out[b] = out[b + 1]
            }
        }
    }

    private fun triangle(f: Double, b: Int): Double {
        val c = bands[b]
        val left = if (b == 0) 0.0 else bands[b - 1]
        val right = if (b == bands.size - 1) Double.MAX_VALUE else bands[b + 1]
        if (f <= c) {
            return if (b == 0) 1.0 else maxOf(0.0, (f - left) / (c - left))
        }
        return if (b == bands.size - 1) 1.0 else maxOf(0.0, (right - f) / (right - c))
    }

    companion object {
        /** Default band centre frequencies (Hz). */
        val DEFAULT_BANDS: DoubleArray
            get() = doubleArrayOf(250.0, 750.0, 1500.0, 2500.0, 3500.0, 5000.0, 7000.0, 9500.0, 12000.0)
    }
}
