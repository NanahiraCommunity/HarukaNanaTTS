package dev.nanatts.world

import dev.nanatts.dsp.FFT
import dev.nanatts.util.JavaRandom
import dev.nanatts.util.fastCos
import dev.nanatts.util.fastExp
import dev.nanatts.util.fastLn
import dev.nanatts.util.fastSin
import dev.nanatts.util.jround
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pulse-synchronous source-filter synthesis after WORLD's `Synthesis`: each pulse gets a minimum-phase
 * periodic response (envelope * (1 - ap^2)) plus filtered white noise (envelope * ap^2) spanning the pulse
 * interval, overlap-added at sub-sample accurate pulse positions.
 *
 * Envelope scaling matches [CheapTrick]: a flat envelope of value `p` synthesises a signal of
 * mean power `p`. Not thread-safe.
 */
class WorldSynthesizer(private val fs: Int, private val framePeriod: Double, fftSize: Int) {

    /** Frame-wise parameters supplied lazily, so long utterances need not be fully decoded in memory. */
    interface Frames {
        val count: Int

        /** F0 in Hz; values `<= 0` mark unvoiced frames. */
        fun f0(frame: Int): Double

        /** Linear power envelope, `fftSize/2+1` bins. */
        fun spectrum(frame: Int, out: DoubleArray)

        /** Aperiodic power fraction `ap^2` in [0, 1] per bin, `fftSize/2+1` bins. */
        fun aperiodicity(frame: Int, out: DoubleArray)
    }

    val fftSize: Int = fftSize
    private val n = fftSize
    private val bins = fftSize / 2 + 1
    private val fft = FFT(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val nre = DoubleArray(fftSize)
    private val nim = DoubleArray(fftSize)
    private val specA = DoubleArray(bins)
    private val specB = DoubleArray(bins)
    private val apA = DoubleArray(bins)
    private val apB = DoubleArray(bins)
    private val spec = DoubleArray(bins)
    private val ap = DoubleArray(bins)
    private val outRe = DoubleArray(bins)
    private val outIm = DoubleArray(bins)
    private var cachedA = -1
    private var cachedB = -1

    fun synthesize(frames: Frames, seed: Long): FloatArray {
        val count = frames.count
        if (count == 0) {
            return FloatArray(0)
        }
        resetCache()
        val rnd = JavaRandom(seed)
        val samplesPerFrame = framePeriod * fs
        val total = jround(count * samplesPerFrame)
        val out = DoubleArray(total + n + 1)

        // 1. pulse positions from the interpolated f0 contour
        var pulses = DoubleArray(maxOf(16, (total * 1.2 * DEFAULT_F0 / fs).toInt() + 16))
        var np = 0
        var phase = 0.0
        for (s in 0 until total) {
            val f = interpolatedF0(frames, s / samplesPerFrame, count)
            val inc = f / fs
            var next = phase + inc
            if (next >= 1) {
                if (np == pulses.size) {
                    pulses = pulses.copyOf(pulses.size * 2)
                }
                pulses[np++] = s + (1 - phase) / inc
                next -= 1
            }
            phase = next
        }

        // 2. one response per pulse
        for (p in 0 until np) {
            val tp = pulses[p]
            val pos = floor(tp).toInt()
            val frac = tp - pos
            var len = if (p + 1 < np) maxOf(1, jround(pulses[p + 1] - tp)) else jround(fs / DEFAULT_F0)
            len = minOf(len, n / 2)
            val fpos = tp / samplesPerFrame
            val nearest = minOf(count - 1, jround(fpos))
            val voiced = frames.f0(nearest) > 0
            interpolateFrame(frames, fpos, count)
            pulseResponse(voiced, frac, len, rnd)
            for (i in 0 until n) {
                val o = pos + i
                if (o >= 0 && o < out.size) {
                    out[o] += re[i]
                }
            }
        }

        // 3. DC blocker and conversion
        val result = FloatArray(total)
        var prevIn = 0.0
        var prevOut = 0.0
        val r = exp(-2 * PI * 30.0 / fs)
        for (i in 0 until total) {
            val y = out[i] - prevIn + r * prevOut
            prevIn = out[i]
            prevOut = y
            result[i] = y.toFloat()
        }
        return result
    }

    private fun interpolatedF0(frames: Frames, fpos: Double, count: Int): Double {
        val i0 = minOf(count - 1, floor(fpos).toInt())
        val i1 = minOf(count - 1, i0 + 1)
        val a = frames.f0(i0)
        val b = frames.f0(i1)
        if (a <= 0 && b <= 0) {
            return DEFAULT_F0
        }
        if (a <= 0) {
            return b
        }
        if (b <= 0) {
            return a
        }
        val t = fpos - i0
        return a + (b - a) * t
    }

    private fun resetCache() {
        cachedA = -1
        cachedB = -1
    }

    private fun interpolateFrame(frames: Frames, fpos: Double, count: Int) {
        val i0 = minOf(count - 1, floor(fpos).toInt())
        val i1 = minOf(count - 1, i0 + 1)
        val t = minOf(1.0, maxOf(0.0, fpos - i0))
        load(frames, i0, i1)
        for (k in 0 until bins) {
            spec[k] = specA[k] * (1 - t) + specB[k] * t
            ap[k] = apA[k] * (1 - t) + apB[k] * t
        }
    }

    private fun load(frames: Frames, i0: Int, i1: Int) {
        if (cachedA == i0 && cachedB == i1) {
            return
        }
        if (cachedB == i0) {
            specB.copyInto(specA, 0, 0, bins)
            apB.copyInto(apA, 0, 0, bins)
        } else {
            frames.spectrum(i0, specA)
            frames.aperiodicity(i0, apA)
        }
        frames.spectrum(i1, specB)
        frames.aperiodicity(i1, apB)
        cachedA = i0
        cachedB = i1
    }

    /** Leaves the time-domain response of one pulse in `re[0..n)`. */
    private fun pulseResponse(voiced: Boolean, frac: Double, len: Int, rnd: JavaRandom) {
        // log magnitudes: periodic in re, aperiodic in im (both real & even -> one complex FFT for both cepstra)
        for (k in 0 until bins) {
            val a2 = minOf(1.0, maxOf(0.0, ap[k]))
            val p = maxOf(0.0, spec[k])
            if (voiced) {
                re[k] = 0.5 * fastLn(p * (1 - a2) + EPS)
                im[k] = 0.5 * fastLn(p * a2 + EPS)
            } else {
                re[k] = 0.5 * fastLn(EPS)
                im[k] = 0.5 * fastLn(p + EPS)
            }
        }
        for (k in bins until n) {
            re[k] = re[n - k]
            im[k] = im[n - k]
        }
        fft.inverse(re, im)
        for (q in 1 until n / 2) {
            re[q] *= 2.0
            im[q] *= 2.0
        }
        for (q in n / 2 + 1 until n) {
            re[q] = 0.0
            im[q] = 0.0
        }
        fft.forward(re, im)

        // white noise for the aperiodic part
        nre.fill(0.0)
        nim.fill(0.0)
        for (i in 0 until len) {
            nre[i] = rnd.nextGaussian()
        }
        fft.forward(nre, nim)

        val sqrtLen = sqrt(len.toDouble())
        // separate the two minimum-phase log spectra and build the combined spectrum (bins 0..n/2)
        for (k in 0 until bins) {
            val m = (n - k) % n
            val xr = re[k]
            val xi = im[k]
            val yr = re[m]
            val yi = im[m]
            val aRe = 0.5 * (xr + yr)
            val aIm = 0.5 * (xi - yi)
            val bRe = 0.5 * (xi + yi)
            val bIm = -0.5 * (xr - yr)
            var cr = 0.0
            var ci = 0.0
            if (voiced) {
                val mag = fastExp(aRe) * sqrtLen
                val ph = aIm - 2 * PI * k * frac / n
                cr += mag * fastCos(ph)
                ci += mag * fastSin(ph)
            }
            val magB = fastExp(bRe)
            val hr = magB * fastCos(bIm)
            val hi = magB * fastSin(bIm)
            cr += hr * nre[k] - hi * nim[k]
            ci += hr * nim[k] + hi * nre[k]
            outRe[k] = cr
            outIm[k] = ci
        }
        outRe[0] = 0.0
        outIm[0] = 0.0
        outIm[n / 2] = 0.0
        for (k in 0 until bins) {
            re[k] = outRe[k]
            im[k] = outIm[k]
        }
        for (k in bins until n) {
            re[k] = outRe[n - k]
            im[k] = -outIm[n - k]
        }
        fft.inverse(re, im)
    }

    private companion object {
        const val DEFAULT_F0 = 500.0
        const val EPS = 1e-30
    }
}
