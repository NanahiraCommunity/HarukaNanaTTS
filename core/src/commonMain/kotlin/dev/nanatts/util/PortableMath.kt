package dev.nanatts.util

import kotlin.math.floor

/**
 * `exp`, `ln`, `sin` and `cos` from bit manipulation and short polynomials, accurate to a few ulp. Kotlin/Wasm
 * computes `kotlin.math`'s versions in slower software routines (50–200 ns per call in wasmtime, against a few
 * ns for these), and the vocoder needs millions of them. Arguments of sin/cos beyond ±1e6 fall back to `kotlin.math`.
 */
internal object PortableMath {

    private const val LN2_HI = 6.93147180369123816490e-01
    private const val LN2_LO = 1.90821492927058770002e-10
    private const val INV_LN2 = 1.44269504088896338700e+00
    private const val SQRT2 = 1.41421356237309504880
    private const val TWO_OVER_PI = 6.36619772367581382433e-01
    private const val PIO2_1 = 1.57079632673412561417e+00
    private const val PIO2_2 = 6.07710050630396597660e-11
    private const val PIO2_3 = 2.02226624871116645580e-21
    private const val TWO_54 = 1.80143985094819840000e+16

    fun exp(x: Double): Double {
        if (x.isNaN()) {
            return x
        }
        if (x > 709.782712893384) {
            return Double.POSITIVE_INFINITY
        }
        if (x < -745.1332191019412) {
            return 0.0
        }
        val k = floor(x * INV_LN2 + 0.5)
        val r = (x - k * LN2_HI) - k * LN2_LO // |r| <= ln2 / 2
        // Taylor series to r^13 / 13!: the remainder is below 1e-17
        var p = 1.0 / 6227020800.0
        p = p * r + 1.0 / 479001600.0
        p = p * r + 1.0 / 39916800.0
        p = p * r + 1.0 / 3628800.0
        p = p * r + 1.0 / 362880.0
        p = p * r + 1.0 / 40320.0
        p = p * r + 1.0 / 5040.0
        p = p * r + 1.0 / 720.0
        p = p * r + 1.0 / 120.0
        p = p * r + 1.0 / 24.0
        p = p * r + 1.0 / 6.0
        p = p * r + 0.5
        p = p * r + 1.0
        p = p * r + 1.0
        val ki = k.toInt()
        return when {
            ki > 1023 -> p * 2.0 * twoTo(ki - 1)
            ki < -1022 -> p * twoTo(ki + 1000) * twoTo(-1000)
            else -> p * twoTo(ki)
        }
    }

    /** 2^e for -1022 <= e <= 1023. */
    private fun twoTo(e: Int): Double = Double.fromBits((e + 1023).toLong() shl 52)

    fun ln(x: Double): Double {
        if (!(x > 0)) {
            return if (x == 0.0) Double.NEGATIVE_INFINITY else Double.NaN
        }
        if (x == Double.POSITIVE_INFINITY) {
            return x
        }
        var bits = x.toRawBits()
        var e = 0
        if (bits < 0x0010000000000000L) { // subnormal
            bits = (x * TWO_54).toRawBits()
            e = -54
        }
        e += ((bits ushr 52) - 1023).toInt()
        var m = Double.fromBits((bits and 0x000FFFFFFFFFFFFFL) or 0x3FF0000000000000L) // [1, 2)
        if (m > SQRT2) {
            m *= 0.5
            e++
        }
        // ln m = 2 atanh(f) = 2 (f + f^3/3 + ...), |f| <= 0.172: terms to f^21 leave a remainder below 1e-19
        val f = (m - 1) / (m + 1)
        val z = f * f
        var s = 1.0 / 21
        s = s * z + 1.0 / 19
        s = s * z + 1.0 / 17
        s = s * z + 1.0 / 15
        s = s * z + 1.0 / 13
        s = s * z + 1.0 / 11
        s = s * z + 1.0 / 9
        s = s * z + 1.0 / 7
        s = s * z + 1.0 / 5
        s = s * z + 1.0 / 3
        s = s * z
        val le = e.toDouble()
        return le * LN2_HI + (2 * f + (2 * f * s + le * LN2_LO))
    }

    fun sin(x: Double): Double {
        if (!(x > -1e6 && x < 1e6)) {
            return kotlin.math.sin(x)
        }
        val k = floor(x * TWO_OVER_PI + 0.5)
        val r = ((x - k * PIO2_1) - k * PIO2_2) - k * PIO2_3
        return when (k.toInt() and 3) {
            0 -> kernelSin(r)
            1 -> kernelCos(r)
            2 -> -kernelSin(r)
            else -> -kernelCos(r)
        }
    }

    fun cos(x: Double): Double {
        if (!(x > -1e6 && x < 1e6)) {
            return kotlin.math.cos(x)
        }
        val k = floor(x * TWO_OVER_PI + 0.5)
        val r = ((x - k * PIO2_1) - k * PIO2_2) - k * PIO2_3
        return when (k.toInt() and 3) {
            0 -> kernelCos(r)
            1 -> -kernelSin(r)
            2 -> -kernelCos(r)
            else -> kernelSin(r)
        }
    }

    // fdlibm's __kernel_sin / __kernel_cos polynomials for |r| <= pi/4
    private fun kernelSin(r: Double): Double {
        val z = r * r
        val p = -1.66666666666666324348e-01 + z * (8.33333333332248946124e-03 + z * (-1.98412698298579493134e-04 +
            z * (2.75573137070700676789e-06 + z * (-2.50507602534068634195e-08 + z * 1.58969099521155010221e-10))))
        return r + r * z * p
    }

    private fun kernelCos(r: Double): Double {
        val z = r * r
        val p = z * (4.16666666666666019037e-02 + z * (-1.38888888888741095749e-03 + z * (2.48015872894767294178e-05 +
            z * (-2.75573143513906633035e-07 + z * (2.08757232129817482790e-09 + z * -1.13596475577881948265e-11)))))
        val hz = 0.5 * z
        val w = 1.0 - hz
        return w + (((1.0 - w) - hz) + z * p)
    }
}
