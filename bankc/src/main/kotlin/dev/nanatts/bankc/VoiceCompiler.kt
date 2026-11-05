package dev.nanatts.bankc

import dev.nanatts.bank.MelGrid
import dev.nanatts.bank.OtoEntry
import dev.nanatts.bank.OtoIni
import dev.nanatts.bank.Segment
import dev.nanatts.bank.VoicePack
import dev.nanatts.bank.VoiceUnit
import dev.nanatts.bank.write
import dev.nanatts.io.WavIO
import dev.nanatts.world.Aperiodicity
import dev.nanatts.world.CheapTrick
import dev.nanatts.world.F0Estimator
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Compiles an UTAU voicebank directory (oto.ini + wav files) into a [VoicePack].
 *
 * Usage: `VoiceCompiler <voicebankDir> <out.vpk> [melBins] [maxHz]`
 */
class VoiceCompiler(private val melBins: Int, private val maxHz: Double) {

    /** Intermediate per-file analysis result. */
    internal class Analysed(
        val file: String,
        val entries: List<OtoEntry>,
        val regionStart: Double,
        val fileMedianF0: Double,
        val f0: DoubleArray,
        val env: Array<FloatArray>,
        val ap: Array<FloatArray>,
    )

    fun compile(dir: File): VoicePack {
        val oto = File(dir, "oto.ini").inputStream().use { OtoIni.parse(it) }
        val byFile = LinkedHashMap<String, MutableList<OtoEntry>>()
        for (e in oto) {
            byFile.getOrPut(e.file) { ArrayList() }.add(e)
        }

        val pool = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors()))
        val futures = ArrayList<Future<Analysed>>()
        for ((name, entries) in byFile) {
            val wav = File(dir, name)
            if (!wav.isFile) {
                System.err.println("warning: missing ${wav.name}, skipping its aliases")
                continue
            }
            futures.add(pool.submit<Analysed> { analyse(wav, entries) })
        }
        val results = futures.map { it.get() }
        pool.shutdown()

        normaliseLoudness(results)

        val medians = results.map { it.fileMedianF0 }.filter { it > 0 }.sorted()
        val medianF0 = if (medians.isEmpty()) 0.0 else medians[medians.size / 2]

        val segments = ArrayList<Segment>()
        val units = ArrayList<VoiceUnit>()
        for (a in results) {
            val segIndex = segments.size
            segments.add(Segment.encode(a.file, a.regionStart, a.f0, a.env, a.ap))
            for (e in a.entries) {
                toUnit(e, a, segIndex)?.let { units.add(it) }
            }
        }

        val meta = readCharacterTxt(dir)
        meta["source"] = "UTAU voicebank compiled by nana-tts bankc"
        val grid = MelGrid(melBins, maxHz)
        return VoicePack(meta, FRAME_PERIOD, grid, Aperiodicity.DEFAULT_BANDS, medianF0, segments, units)
    }

    private fun analyse(wav: File, entries: List<OtoEntry>): Analysed {
        val clip = WavIO.read(wav.readBytes())
        val x = clip.samples
        val fs = clip.sampleRate
        val lengthMs = x.size * 1000.0 / fs

        var from = Double.MAX_VALUE
        var to = 0.0
        for (e in entries) {
            from = minOf(from, e.offset)
            to = maxOf(to, e.endMs(lengthMs))
        }
        from = maxOf(0.0, from - 50) / 1000.0
        to = minOf(lengthMs, to + 50) / 1000.0

        val f0r = F0Estimator(F0_FLOOR, F0_CEIL).medianSpread(1.6).estimate(x, fs, FRAME_PERIOD)
        val fileMedian = F0Estimator.median(f0r.f0)
        val fallbackF0 = if (fileMedian > 0) fileMedian else 400.0

        val first = floor(from / FRAME_PERIOD).toInt()
        val last = minOf(f0r.f0.size - 1, ceil(to / FRAME_PERIOD).toInt())
        val frames = last - first + 1

        val ct = CheapTrick(fs, F0_FLOOR)
        val apEst = Aperiodicity(fs, Aperiodicity.DEFAULT_BANDS, -60.0)
        val grid = MelGrid(melBins, maxHz)
        val power = DoubleArray(ct.fftSize / 2 + 1)
        val apBand = DoubleArray(Aperiodicity.DEFAULT_BANDS.size)

        val a = Analysed(
            wav.name, entries, first * FRAME_PERIOD, fileMedian, DoubleArray(frames),
            Array(frames) { FloatArray(melBins) }, Array(frames) { FloatArray(apBand.size) },
        )
        for (i in 0 until frames) {
            val fi = first + i
            val f0 = f0r.f0[fi]
            a.f0[i] = f0
            ct.estimate(x, fi * FRAME_PERIOD, if (f0 > 0) f0 else fallbackF0, power)
            // store power spectral density (power per Hz, one-sided) so packs are sample-rate independent
            grid.fromLinear(power, ct.fftSize, fs, 2.0 / fs, a.env[i])
        }
        val repaired = repairVoicing(a, grid, fallbackF0)
        for (i in 0 until frames) {
            apEst.estimate(x, (first + i) * FRAME_PERIOD, a.f0[i], apBand)
            for (b in apBand.indices) {
                a.ap[i][b] = (10 * log10(apBand[b])).toFloat()
            }
        }
        capRepairedAperiodicity(a, repaired)
        smoothAperiodicity(a.ap)
        return a
    }

    private fun toUnit(e: OtoEntry, a: Analysed, segIndex: Int): VoiceUnit? {
        val alias = e.alias.trim()
        val sp = alias.indexOf(' ')
        if (sp != 1) {
            return null // only "x KANA" style VCV/CV aliases are used
        }
        val prev = alias[0]
        val kana = alias.substring(2)
        val base = a.regionStart
        val start = e.offset / 1000.0 - base
        val overlap = start + maxOf(0.0, e.overlap) / 1000.0
        val onset = start + e.preutterance / 1000.0
        val fixedEnd = start + e.consonant / 1000.0
        val fileMs = (a.regionStart + a.f0.size * FRAME_PERIOD) * 1000
        val end = minOf(a.f0.size * FRAME_PERIOD, e.endMs(fileMs) / 1000.0 - base)
        val cs = detectConsonantStart(a, prev, kana, start, overlap, onset)
        return VoiceUnit(alias, prev, kana, segIndex, start, overlap, cs, onset, maxOf(fixedEnd, onset), end)
    }

    companion object {
        const val FRAME_PERIOD = 0.005
        const val F0_FLOOR = 80.0
        const val F0_CEIL = 1200.0

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.size < 2) {
                System.err.println("usage: VoiceCompiler <voicebankDir> <out.vpk> [melBins=80] [maxHz=12000]")
                exitProcess(2)
            }
            val mel = if (args.size > 2) args[2].toInt() else 80
            val maxHz = if (args.size > 3) args[3].toDouble() else 12000.0
            val t0 = System.nanoTime()
            val pack = VoiceCompiler(mel, maxHz).compile(File(args[0]))
            val out = File(args[1])
            val parent = out.absoluteFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw IOException("cannot create $parent")
            }
            out.outputStream().use { pack.write(it) }
            System.out.printf(
                "wrote %s: %d segments, %d units, median f0 %.1f Hz, %.1f MB, %.1f s%n",
                out, pack.segmentCount, pack.unitCount, pack.medianF0, out.length() / 1e6,
                (System.nanoTime() - t0) / 1e9,
            )
        }

        /**
         * YIN rejects irregular phonation (nasal transitions, glottalised onsets). Such frames would be
         * resynthesised as loud noise, so frames that are loud and dominated by low-frequency energy are
         * re-labelled voiced (sonorant: most energy below 3 kHz), with f0 interpolated from their voiced neighbours.
         */
        internal fun repairVoicing(a: Analysed, grid: MelGrid, fallbackF0: Double): BooleanArray {
            val n = a.f0.size
            val fix = BooleanArray(n)
            val voicedLevels = (0 until n).filter { a.f0[it] > 0 }.map { meanDb(a.env[it]) }.sorted()
            if (voicedLevels.isEmpty()) {
                return fix
            }
            val ref = voicedLevels[voicedLevels.size / 2]
            for (i in 0 until n) {
                if (a.f0[i] > 0) {
                    continue
                }
                var low = 0.0
                var total = 0.0
                for (b in 0 until grid.size) {
                    val lo = if (b == 0) 0.0 else (grid.hz(b - 1) + grid.hz(b)) / 2
                    val hi = if (b == grid.size - 1) grid.hz(b) else (grid.hz(b) + grid.hz(b + 1)) / 2
                    val e = 10.0.pow(a.env[i][b] / 10.0) * (hi - lo)
                    total += e
                    if (grid.hz(b) < 3000) {
                        low += e
                    }
                }
                val level = meanDb(a.env[i])
                fix[i] = level > ref - 22 && low / total > 0.6
            }
            for (i in 0 until n) {
                if (!fix[i]) {
                    continue
                }
                var l = i - 1
                while (l >= 0 && a.f0[l] <= 0) {
                    l--
                }
                var r = i + 1
                while (r < n && a.f0[r] <= 0) {
                    r++
                }
                val f = if (l >= 0 && r < n) {
                    a.f0[l] + (a.f0[r] - a.f0[l]) * (i - l) / (r - l).toDouble()
                } else if (l >= 0) {
                    a.f0[l]
                } else if (r < n) {
                    a.f0[r]
                } else {
                    fallbackF0
                }
                a.f0[i] = -f // mark, so interpolation above only uses original voiced frames
            }
            for (i in 0 until n) {
                a.f0[i] = abs(a.f0[i])
            }
            return fix
        }

        /** Repaired frames are irregular, so their measured aperiodicity is near 0 dB; use typical voiced values. */
        private fun capRepairedAperiodicity(a: Analysed, repaired: BooleanArray) {
            val bands = a.ap[0].size
            val median = FloatArray(bands) { b ->
                val v = a.f0.indices.filter { a.f0[it] > 0 && !repaired[it] }.map { a.ap[it][b] }.sorted()
                if (v.isEmpty()) -20f else v[v.size / 2]
            }
            for (i in a.f0.indices) {
                if (repaired[i]) {
                    for (b in 0 until bands) {
                        a.ap[i][b] = minOf(a.ap[i][b], median[b] + 6f)
                    }
                }
            }
        }

        /** 3-frame median over time per band to suppress estimator noise. */
        private fun smoothAperiodicity(ap: Array<FloatArray>) {
            if (ap.size < 3) {
                return
            }
            val bands = ap[0].size
            val copy = Array(ap.size) { ap[it].copyOf() }
            for (i in 1 until ap.size - 1) {
                for (b in 0 until bands) {
                    val x = copy[i - 1][b]
                    val y = copy[i][b]
                    val z = copy[i + 1][b]
                    ap[i][b] = maxOf(minOf(x, y), minOf(maxOf(x, y), z))
                }
            }
        }

        /** Equalises recording level across files (partially, to keep natural vowel differences). */
        private fun normaliseLoudness(results: List<Analysed>) {
            val levels = DoubleArray(results.size) { r ->
                val a = results[r]
                val l = a.f0.indices.filter { a.f0[it] > 0 }.map { meanDb(a.env[it]) }.sorted()
                if (l.isEmpty()) Double.NaN else l[l.size / 2]
            }
            val valid = levels.filter { !it.isNaN() }.sorted()
            if (valid.isEmpty()) {
                return
            }
            val target = valid[valid.size / 2]
            for (r in results.indices) {
                if (levels[r].isNaN()) {
                    continue
                }
                val gain = maxOf(-6.0, minOf(6.0, target - levels[r])).toFloat()
                for (frame in results[r].env) {
                    for (b in frame.indices) {
                        frame[b] += gain
                    }
                }
            }
        }

        private fun meanDb(db: FloatArray): Double {
            var s = 0.0
            for (v in db) {
                s += 10.0.pow(v / 10.0)
            }
            return 10 * log10(s / db.size + 1e-300)
        }

        /**
         * Finds where the consonant begins: scanning backwards from the vowel onset, the first frame that is
         * spectrally close to the preceding context (previous vowel tail, or silence for '-' units).
         */
        internal fun detectConsonantStart(
            a: Analysed, prev: Char, kana: String, start: Double, overlap: Double, onset: Double,
        ): Double {
            if (isPureVowel(kana) && prev != '-') {
                return maxOf(overlap, onset - 0.04)
            }
            val onsetFrame = frame(onset)
            val refFrom: Int
            val refTo: Int
            if (prev == '-') {
                refFrom = frame(start)
                refTo = minOf(onsetFrame - 4, refFrom + 4)
            } else {
                refFrom = maxOf(frame(start), frame(overlap) - 6)
                refTo = frame(overlap)
            }
            if (refTo < refFrom || refFrom < 0) {
                return maxOf(overlap, onset - 0.08)
            }
            val bins = a.env[0].size
            val ref = FloatArray(bins)
            for (i in refFrom..refTo) {
                for (b in 0 until bins) {
                    ref[b] += a.env[minOf(a.env.size - 1, i)][b] / (refTo - refFrom + 1)
                }
            }
            val threshold = if (prev == '-') 10.0 else 6.0
            val lower = maxOf(refTo, frame(overlap))
            var i = onsetFrame - 3
            while (i > lower) {
                if (distance(a.env[minOf(a.env.size - 1, i)], ref) < threshold) {
                    break
                }
                i--
            }
            val cs = (i + 1) * FRAME_PERIOD
            return maxOf(overlap, minOf(onset - 0.015, cs))
        }

        private fun frame(t: Double): Int = (t / FRAME_PERIOD).roundToInt()

        private fun distance(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) {
                val d = (maxOf(a[i], -150f) - maxOf(b[i], -150f)).toDouble()
                s += d * d
            }
            return sqrt(s / a.size)
        }

        private fun isPureVowel(kana: String): Boolean =
            kana == "あ" || kana == "い" || kana == "う" || kana == "え" || kana == "お"

        private fun readCharacterTxt(dir: File): MutableMap<String, String> {
            val meta = LinkedHashMap<String, String>()
            val f = File(dir, "character.txt")
            if (!f.isFile) {
                return meta
            }
            try {
                val text = String(f.readBytes(), Charset.forName("windows-31j"))
                val extra = StringBuilder()
                for (line in text.lines()) {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        meta[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                    } else if (line.isNotBlank()) {
                        extra.append(line.trim()).append('\n')
                    }
                }
                if (extra.isNotEmpty()) {
                    meta["notes"] = extra.toString().trim()
                }
            } catch (e: IOException) {
                System.err.println("warning: cannot read character.txt: ${e.message}")
            }
            meta.remove("image")
            return meta
        }
    }
}
