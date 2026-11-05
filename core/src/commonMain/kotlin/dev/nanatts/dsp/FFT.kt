package dev.nanatts.dsp

import dev.nanatts.util.reverseBits
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT with precomputed twiddles.
 * Forward transform is unnormalised; [inverse] scales by 1/n.
 * Instances are immutable and thread-safe.
 */
class FFT(val size: Int) {

    private val rev: IntArray
    private val cos: DoubleArray
    private val sin: DoubleArray

    init {
        require(size >= 2 && size.countOneBits() == 1) { "FFT size must be a power of two: $size" }
        val bits = size.countTrailingZeroBits()
        rev = IntArray(size) { reverseBits(it) ushr (32 - bits) }
        cos = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
        sin = DoubleArray(size / 2) { sin(-2 * PI * it / size) }
    }

    fun forward(re: DoubleArray, im: DoubleArray) {
        transform(re, im, false)
    }

    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, true)
        val s = 1.0 / size
        for (i in 0 until size) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        val n = size
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val wr = cos[k * step]
                    val wi = if (inv) -sin[k * step] else sin[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** Smallest power of two >= v. */
        fun nextPow2(v: Int): Int {
            var n = 1
            while (n < v) {
                n = n shl 1
            }
            return n
        }
    }
}
