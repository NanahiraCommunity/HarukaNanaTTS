package dev.nanatts.bankc

import dev.nanatts.bank.MelGrid
import dev.nanatts.io.AudioClip
import dev.nanatts.io.WavIO
import dev.nanatts.synth.ParamTrack
import dev.nanatts.world.Aperiodicity
import dev.nanatts.world.CheapTrick
import dev.nanatts.world.F0Estimator
import dev.nanatts.world.WorldSynthesizer
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Diagnostic: analyse a wav, resynthesise it through the mel-grid representation and report level and
 * log-spectral distance. Usage: `RoundTrip <in.wav> <out.wav> [outRate=24000]`
 */
object RoundTrip {

    @JvmStatic
    fun main(args: Array<String>) {
        val clip = WavIO.read(File(args[0]).readBytes())
        val outFs = if (args.size > 2) args[2].toInt() else 24000
        val x = clip.samples
        val fs = clip.sampleRate
        val fp = VoiceCompiler.FRAME_PERIOD
        val f0r = F0Estimator(VoiceCompiler.F0_FLOOR, VoiceCompiler.F0_CEIL).estimate(x, fs, fp)
        val median = F0Estimator.median(f0r.f0)
        val ct = CheapTrick(fs, VoiceCompiler.F0_FLOOR)
        val apEst = Aperiodicity(fs, Aperiodicity.DEFAULT_BANDS, -60.0)
        val grid = MelGrid(80, minOf(12000.0, outFs / 2.0))
        val frames = f0r.f0.size
        val track = ParamTrack(frames, grid.size, Aperiodicity.DEFAULT_BANDS.size)
        val power = DoubleArray(ct.fftSize / 2 + 1)
        val ap = DoubleArray(Aperiodicity.DEFAULT_BANDS.size)
        var voiced = 0
        for (i in 0 until frames) {
            val f0 = f0r.f0[i]
            track.f0[i] = f0
            if (f0 > 0) {
                voiced++
            }
            ct.estimate(x, i * fp, if (f0 > 0) f0 else median, power)
            grid.fromLinear(power, ct.fftSize, fs, 2.0 / fs, track.envDb(i))
            apEst.estimate(x, i * fp, f0, ap)
            for (b in ap.indices) {
                track.apDb(i)[b] = (10 * log10(ap[b])).toFloat()
            }
        }
        val fft = 1024
        val syn = WorldSynthesizer(outFs, fp, fft)
        val t0 = System.nanoTime()
        val y = syn.synthesize(track.asFrames(grid, Aperiodicity.DEFAULT_BANDS, outFs, fft), 1)
        val secs = (System.nanoTime() - t0) / 1e9
        File(args[1]).writeBytes(WavIO.encode(AudioClip(y, outFs)))
        System.out.printf("frames=%d voiced=%.0f%% median f0=%.1f%n", frames, 100.0 * voiced / frames, median)
        System.out.printf("rms in=%.4f out=%.4f (ratio %.2f dB)%n", rms(x), rms(y), 20 * log10(rms(y) / rms(x)))
        System.out.printf(
            "synthesis %.3fs for %.2fs audio (RTF %.3f)%n", secs, y.size / outFs.toDouble(),
            secs / (y.size / outFs.toDouble()),
        )
        // per-frame envelope check: analyse the output again and compare mel envelopes
        val ct2 = CheapTrick(outFs, VoiceCompiler.F0_FLOOR)
        val p2 = DoubleArray(ct2.fftSize / 2 + 1)
        val e2 = FloatArray(grid.size)
        var sum = 0.0
        var n = 0
        for (i in 0 until frames) {
            if (track.f0[i] <= 0) {
                continue
            }
            ct2.estimate(y, i * fp, track.f0[i], p2)
            grid.fromLinear(p2, ct2.fftSize, outFs, 2.0 / outFs, e2)
            var s = 0.0
            for (b in 0 until grid.size) {
                val d = (e2[b] - track.envDb(i)[b]).toDouble()
                s += d * d
            }
            sum += sqrt(s / grid.size)
            n++
        }
        System.out.printf("voiced log-spectral distance (re-analysis): %.2f dB%n", sum / maxOf(1, n))
    }

    private fun rms(v: FloatArray): Double {
        var s = 0.0
        for (f in v) {
            s += f * f
        }
        return sqrt(s / v.size)
    }
}
