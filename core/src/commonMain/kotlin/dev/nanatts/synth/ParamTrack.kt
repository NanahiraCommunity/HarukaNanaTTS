package dev.nanatts.synth

import dev.nanatts.bank.MelGrid
import dev.nanatts.util.fastPow10
import dev.nanatts.world.WorldSynthesizer

/**
 * Frame-wise vocoder parameters of an utterance at the pack's frame period: f0 (Hz, 0 = unvoiced),
 * envelope (PSD in dB on the mel grid) and band aperiodicity (dB).
 */
class ParamTrack(frames: Int, melBins: Int, apBands: Int) {

    val f0 = DoubleArray(frames)
    internal val envDb: Array<FloatArray> = Array(frames) { FloatArray(melBins) }
    internal val apDb: Array<FloatArray> = Array(frames) { FloatArray(apBands) }

    val frames: Int
        get() = f0.size

    fun envDb(i: Int): FloatArray = envDb[i]

    fun apDb(i: Int): FloatArray = apDb[i]

    /** Adapts this track to the synthesizer's linear-frequency frame interface. */
    fun asFrames(grid: MelGrid, apBands: DoubleArray, fs: Int, fftSize: Int): WorldSynthesizer.Frames {
        val mapper = grid.mapper(fftSize, fs)
        val bins = fftSize / 2 + 1
        val bandIdx = IntArray(bins)
        val bandFrac = FloatArray(bins)
        for (k in 0 until bins) {
            val f = k * fs / fftSize.toDouble()
            if (f <= apBands[0]) {
                bandIdx[k] = 0
                bandFrac[k] = 0f
            } else if (f >= apBands[apBands.size - 1]) {
                bandIdx[k] = apBands.size - 2
                bandFrac[k] = 1f
            } else {
                var b = 0
                while (apBands[b + 1] < f) {
                    b++
                }
                bandIdx[k] = b
                bandFrac[k] = ((f - apBands[b]) / (apBands[b + 1] - apBands[b])).toFloat()
            }
        }
        // PSD (one-sided, per Hz) -> synthesizer power normalisation at this sample rate
        val toPower = fs / 2.0
        val maxHz = grid.maxHz
        return object : WorldSynthesizer.Frames {
            override val count: Int
                get() = f0.size

            override fun f0(frame: Int): Double = f0[frame]

            override fun spectrum(frame: Int, out: DoubleArray) {
                mapper.map(envDb[frame], out)
                for (k in out.indices) {
                    val f = k * fs / fftSize.toDouble()
                    var db = out[k]
                    if (f > maxHz) {
                        db -= 24 * (f - maxHz) / 1000.0 // roll off beyond the stored range
                    }
                    out[k] = fastPow10(db / 10.0) * toPower
                }
            }

            override fun aperiodicity(frame: Int, out: DoubleArray) {
                val ap = apDb[frame]
                for (k in out.indices) {
                    val b = bandIdx[k]
                    val db = ap[b] + (ap[b + 1] - ap[b]) * bandFrac[k]
                    out[k] = fastPow10(db / 10.0)
                }
            }
        }
    }
}
