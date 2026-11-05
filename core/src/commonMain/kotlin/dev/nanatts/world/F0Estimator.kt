package dev.nanatts.world

import dev.nanatts.util.jround
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Robust F0 estimator (YIN on a decimated signal, two-pass range restriction around the median).
 * Stands in for WORLD's Harvest/DIO: voicebank recordings are sung at a level pitch, which makes a
 * median-constrained YIN both fast and reliable.
 */
class F0Estimator(private val f0Floor: Double, private val f0Ceil: Double) {

    /** Result of an estimation: f0 per frame (0 = unvoiced) and the YIN aperiodicity per frame. */
    class Result internal constructor(val f0: DoubleArray, val yinMin: DoubleArray)

    private var threshold = 0.18
    private var silenceDb = -45.0
    private var medianSpread = 2.0

    fun threshold(t: Double): F0Estimator {
        threshold = t
        return this
    }

    /**
     * Second-pass search range around the first-pass median, as a ratio (default 2 = one octave each way).
     * Recordings sung at a level pitch can use a tighter range to rule out octave errors.
     */
    fun medianSpread(ratio: Double): F0Estimator {
        medianSpread = ratio
        return this
    }

    /** Frames whose RMS is this many dB below the loudest frame are treated as unvoiced. */
    fun silenceDb(db: Double): F0Estimator {
        silenceDb = db
        return this
    }

    /** Estimates f0 for frames centred at `i * framePeriod` seconds. */
    fun estimate(x: FloatArray, fs: Int, framePeriod: Double): Result {
        val factor = maxOf(1, fs / 11025)
        val fsd = fs / factor.toDouble()
        val y = decimate(x, factor)
        val frames = (x.size / fs.toDouble() / framePeriod).toInt() + 1

        val rms = DoubleArray(frames)
        val w = ceil(fsd * 2.0 / f0Floor).toInt()
        var maxRms = 1e-12
        for (i in 0 until frames) {
            val c = jround(i * framePeriod * fsd)
            var s = 0.0
            for (j in c - w / 2 until c + w / 2) {
                val v = if (j >= 0 && j < y.size) y[j] else 0.0
                s += v * v
            }
            rms[i] = sqrt(s / w)
            maxRms = maxOf(maxRms, rms[i])
        }
        val silence = maxRms * 10.0.pow(silenceDb / 20)

        val f0 = DoubleArray(frames)
        val ap = DoubleArray(frames)
        pass(y, fsd, framePeriod, rms, silence, f0Floor, f0Ceil, f0, ap)
        val median = median(f0)
        if (median > 0) {
            val lo = maxOf(f0Floor, median / medianSpread)
            val hi = minOf(f0Ceil, median * medianSpread)
            pass(y, fsd, framePeriod, rms, silence, lo, hi, f0, ap)
        }
        cleanup(f0)
        return Result(f0, ap)
    }

    private fun pass(
        y: DoubleArray, fsd: Double, framePeriod: Double, rms: DoubleArray, silence: Double,
        lo: Double, hi: Double, f0: DoubleArray, ap: DoubleArray,
    ) {
        val tauMin = maxOf(2, floor(fsd / hi).toInt())
        val tauMax = ceil(fsd / lo).toInt()
        val w = ceil(fsd * 2.0 / lo).toInt()
        val d = DoubleArray(tauMax + 2)
        val cm = DoubleArray(tauMax + 2)
        for (i in f0.indices) {
            f0[i] = 0.0
            ap[i] = 1.0
            if (rms[i] < silence) {
                continue
            }
            val start = jround(i * framePeriod * fsd) - (w + tauMax) / 2
            for (tau in 1..tauMax + 1) {
                var s = 0.0
                for (j in 0 until w) {
                    val diff = at(y, start + j) - at(y, start + j + tau)
                    s += diff * diff
                }
                d[tau] = s
            }
            var running = 0.0
            cm[0] = 1.0
            for (tau in 1..tauMax + 1) {
                running += d[tau]
                cm[tau] = if (running > 0) d[tau] * tau / running else 1.0
            }
            var best = -1
            var tau = tauMin
            while (tau <= tauMax) {
                if (cm[tau] < threshold) {
                    while (tau + 1 <= tauMax && cm[tau + 1] < cm[tau]) {
                        tau++
                    }
                    best = tau
                    break
                }
                tau++
            }
            if (best < 0) {
                var m = Double.MAX_VALUE
                for (t in tauMin..tauMax) {
                    if (cm[t] < m) {
                        m = cm[t]
                        best = t
                    }
                }
            }
            val a = cm[best - 1]
            val b = cm[best]
            val c = cm[best + 1]
            val denom = a - 2 * b + c
            var shift = if (denom > 1e-12) 0.5 * (a - c) / denom else 0.0
            shift = maxOf(-1.0, minOf(1.0, shift))
            ap[i] = b
            if (b < threshold * 2) {
                f0[i] = fsd / (best + shift)
            }
        }
    }

    companion object {
        /** Median of the voiced (positive) values, or 0 if there are none. */
        fun median(f0: DoubleArray): Double {
            val v = f0.filter { it > 0 }.sorted()
            return if (v.isEmpty()) 0.0 else v[v.size / 2]
        }

        private fun cleanup(f0: DoubleArray) {
            // drop very short voiced runs
            val n = f0.size
            var i = 0
            while (i < n) {
                if (f0[i] <= 0) {
                    i++
                    continue
                }
                var j = i
                while (j < n && f0[j] > 0) {
                    j++
                }
                if (j - i < 3) {
                    f0.fill(0.0, i, j)
                } else {
                    // 3-point median inside the run
                    val copy = f0.copyOfRange(i, j)
                    for (k in 1 until copy.size - 1) {
                        f0[i + k] = med3(copy[k - 1], copy[k], copy[k + 1])
                    }
                }
                i = j
            }
        }

        private fun med3(a: Double, b: Double, c: Double): Double = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

        private fun at(y: DoubleArray, i: Int): Double = if (i >= 0 && i < y.size) y[i] else 0.0

        /** Low-pass (windowed sinc) and decimate by an integer factor. */
        internal fun decimate(x: FloatArray, factor: Int): DoubleArray {
            if (factor == 1) {
                return DoubleArray(x.size) { x[it].toDouble() }
            }
            val half = 16 * factor
            val h = DoubleArray(2 * half + 1)
            val cutoff = 0.9 / (2.0 * factor)
            var sum = 0.0
            for (k in -half..half) {
                val sinc = if (k == 0) 2 * cutoff else sin(2 * PI * cutoff * k) / (PI * k)
                val win = 0.42 + 0.5 * cos(PI * k / half) + 0.08 * cos(2 * PI * k / half)
                h[k + half] = sinc * win
                sum += h[k + half]
            }
            val n = x.size / factor
            val y = DoubleArray(n)
            for (i in 0 until n) {
                val c = i * factor
                var s = 0.0
                for (k in -half..half) {
                    val j = c + k
                    if (j >= 0 && j < x.size) {
                        s += x[j] * h[k + half]
                    }
                }
                y[i] = s / sum
            }
            return y
        }
    }
}
