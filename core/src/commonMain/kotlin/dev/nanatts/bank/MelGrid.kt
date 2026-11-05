package dev.nanatts.bank

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Log-spectral envelope sampled on a mel-spaced frequency grid from 0 Hz to [maxHz]. */
class MelGrid(val size: Int, val maxHz: Double) {

    private val hz: DoubleArray

    init {
        val melMax = mel(maxHz)
        hz = DoubleArray(size) { hzFromMel(melMax * it / (size - 1)) }
    }

    fun hz(i: Int): Double = hz[i]

    /**
     * Samples a linear-frequency power spectrum (bins `0..fftSize/2`) at the grid points.
     * [scale] is applied before converting to dB.
     */
    fun fromLinear(power: DoubleArray, fftSize: Int, fs: Int, scale: Double, outDb: FloatArray) {
        val df = fs / fftSize.toDouble()
        val last = fftSize / 2
        for (i in 0 until size) {
            val pos = min(last.toDouble(), hz[i] / df)
            val k = floor(pos).toInt()
            val t = pos - k
            val a = ln(max(power[k], 1e-300))
            val b = ln(max(power[min(last, k + 1)], 1e-300))
            val l = a + (b - a) * t
            outDb[i] = (10.0 / ln(10.0) * l + 10 * log10(scale)).toFloat()
        }
    }

    /** Precomputed interpolation from this grid to the linear bins of an FFT. */
    fun mapper(fftSize: Int, fs: Int): Mapper = Mapper(fftSize, fs)

    inner class Mapper internal constructor(fftSize: Int, fs: Int) {
        private val idx: IntArray
        private val frac: FloatArray

        init {
            val bins = fftSize / 2 + 1
            idx = IntArray(bins)
            frac = FloatArray(bins)
            val melMax = mel(maxHz)
            for (k in 0 until bins) {
                val f = k * fs / fftSize.toDouble()
                val pos = min(size - 1.0, mel(f) / melMax * (size - 1))
                val i = min(size - 2, floor(pos).toInt())
                idx[k] = i
                frac[k] = (pos - i).toFloat()
            }
        }

        val bins: Int
            get() = idx.size

        /** Interpolates grid values (any unit, e.g. dB) to linear bins. */
        fun map(grid: FloatArray, out: DoubleArray) {
            for (k in idx.indices) {
                val i = idx[k]
                out[k] = (grid[i] + (grid[i + 1] - grid[i]) * frac[k]).toDouble()
            }
        }
    }

    companion object {
        fun mel(f: Double): Double = 1127.0 * ln(1 + f / 700.0)

        fun hzFromMel(m: Double): Double = 700.0 * (exp(m / 1127.0) - 1)
    }
}
