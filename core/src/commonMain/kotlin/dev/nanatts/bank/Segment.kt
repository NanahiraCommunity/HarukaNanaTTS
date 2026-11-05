package dev.nanatts.bank

import dev.nanatts.util.jround
import dev.nanatts.util.jroundLong
import kotlin.math.log10
import kotlin.math.pow

/**
 * Analysed frames of one region of a voicebank recording, stored quantised to keep packs small
 * and memory use low on mobile devices. Frames are decoded on demand.
 */
class Segment internal constructor(
    val file: String,
    /** Time of frame 0 in the original recording (seconds). */
    val startSec: Double,
    val frames: Int,
    internal val melBins: Int,
    internal val apBands: Int,
    internal val f0q: CharArray,
    internal val envTop: ShortArray,
    internal val env: ByteArray,
    internal val ap: ByteArray,
) {

    // float division like the Java original: f0 values are float precision
    fun f0(i: Int): Double = (f0q[clamp(i)].code / F0_SCALE).toDouble()

    fun envDb(i: Int, out: FloatArray) {
        val c = clamp(i)
        val topDb = envTop[c] / ENV_TOP_SCALE
        val base = c * melBins
        for (b in 0 until melBins) {
            out[b] = topDb - (env[base + b].toInt() and 0xFF) * ENV_STEP_DB
        }
    }

    fun apDb(i: Int, out: FloatArray) {
        val c = clamp(i)
        val base = c * apBands
        for (b in 0 until apBands) {
            out[b] = -(ap[base + b].toInt() and 0xFF) * AP_STEP_DB
        }
    }

    /** Mean envelope level in dB over the grid (a cheap loudness proxy). */
    fun levelDb(i: Int): Float {
        val c = clamp(i)
        val topDb = envTop[c] / ENV_TOP_SCALE
        val base = c * melBins
        var sum = 0.0
        for (b in 0 until melBins) {
            sum += 10.0.pow((topDb - (env[base + b].toInt() and 0xFF) * ENV_STEP_DB) / 10.0)
        }
        return (10 * log10(sum / melBins + 1e-30)).toFloat()
    }

    private fun clamp(i: Int): Int = maxOf(0, minOf(frames - 1, i))

    companion object {
        /** Envelope quantisation step in dB (one byte per grid point below the per-frame maximum). */
        internal const val ENV_STEP_DB = 0.5f
        internal const val ENV_TOP_SCALE = 50f
        internal const val AP_STEP_DB = 0.25f
        internal const val F0_SCALE = 16f

        /** Quantises analysed frames. `envDb[frame][melBin]`, `apDb[frame][band]` (<= 0). */
        fun encode(file: String, startSec: Double, f0: DoubleArray, envDb: Array<FloatArray>, apDb: Array<FloatArray>): Segment {
            val frames = f0.size
            val mel = envDb[0].size
            val bands = apDb[0].size
            val f0q = CharArray(frames)
            val top = ShortArray(frames)
            val env = ByteArray(frames * mel)
            val ap = ByteArray(frames * bands)
            for (i in 0 until frames) {
                f0q[i] = maxOf(0L, minOf(65535L, jroundLong(f0[i] * F0_SCALE))).toInt().toChar()
                var max = -Float.MAX_VALUE
                for (v in envDb[i]) {
                    max = maxOf(max, v)
                }
                val t = maxOf(Short.MIN_VALUE.toInt(), minOf(Short.MAX_VALUE.toInt(), jround(max * ENV_TOP_SCALE))).toShort()
                top[i] = t
                val topDb = t / ENV_TOP_SCALE
                for (b in 0 until mel) {
                    val q = jround((topDb - envDb[i][b]) / ENV_STEP_DB)
                    env[i * mel + b] = maxOf(0, minOf(255, q)).toByte()
                }
                for (b in 0 until bands) {
                    val q = jround(-apDb[i][b] / AP_STEP_DB)
                    ap[i * bands + b] = maxOf(0, minOf(255, q)).toByte()
                }
            }
            return Segment(file, startSec, frames, mel, bands, f0q, top, env, ap)
        }

    }
}
