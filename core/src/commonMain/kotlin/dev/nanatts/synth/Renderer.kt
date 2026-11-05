package dev.nanatts.synth

import dev.nanatts.VoiceParams
import dev.nanatts.bank.Segment
import dev.nanatts.bank.VoicePack
import dev.nanatts.bank.VoiceUnit
import dev.nanatts.prosody.ProsodyModel
import dev.nanatts.text.Mora
import dev.nanatts.text.Phrase
import dev.nanatts.util.JavaRandom
import dev.nanatts.util.fastPow10
import dev.nanatts.util.jround
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Concatenates VCV units in the vocoder-parameter domain: selects a unit per mora, time-maps its consonant
 * and vowel regions onto the planned timing, cross-fades neighbouring units inside their shared vowel and
 * imposes the planned f0 contour.
 */
class Renderer(private val pack: VoicePack) {

    private val melBins = pack.grid.size
    private val apBandsHz = pack.apBands
    private val bands = apBandsHz.size
    private val fp = pack.framePeriod

    /** A unit placed on the output timeline. */
    private class Placed(
        val unit: VoiceUnit,
        val seg: Segment,
        val mora: Int,
        val fadeIn: Double,
        val outT: DoubleArray,
        val srcT: DoubleArray,
    ) {
        val from = outT[0]
        val to = outT[outT.size - 1]
    }

    /** Picks the unit for mora [i] given its left context. */
    fun select(morae: List<Mora>, i: Int): VoiceUnit {
        val m = morae[i]
        val prev = if (i == 0) '-' else morae[i - 1].vowel
        return first("$prev ${m.kana}")
            ?: first("- ${m.kana}")
            ?: first("$prev ${vowelKana(m.vowel)}")
            ?: first("- ${vowelKana(m.vowel)}")
            ?: throw IllegalStateException("voice pack has no unit for ${m.kana}")
    }

    private fun first(alias: String): VoiceUnit? = pack.units(alias).firstOrNull()

    fun render(phrase: Phrase, plan: ProsodyModel.Plan, vp: VoiceParams, baseF0: Double, seed: Long): ParamTrack {
        val morae = phrase.morae
        val n = morae.size
        val onset = DoubleArray(n)
        var t = LEAD + plan.consonant[0]
        for (i in 0 until n) {
            onset[i] = t
            if (i + 1 < n) {
                t += plan.vowel[i] + plan.consonant[i + 1]
            }
        }
        val tail = TAIL / vp.speed
        val end = onset[n - 1] + plan.vowel[n - 1] + tail

        val placed = Array(n) { place(morae, it, onset[it], plan, if (it == n - 1) tail else 0.0) }

        val frames = ceil(end / fp).toInt() + 1
        val track = ParamTrack(frames, melBins, bands)
        val envA = FloatArray(melBins)
        val envB = FloatArray(melBins)
        val apA = FloatArray(bands)
        val apB = FloatArray(bands)
        val semitones = contour(plan, onset, end, frames, vp, seed)
        val breath = vp.breathiness
        val volumeDb = 20 * log10(maxOf(1e-6, vp.volume))

        val gainDb = DoubleArray(frames)
        var first = 0
        for (f in 0 until frames) {
            val time = f * fp
            val env = track.envDb[f]
            val ap = track.apDb[f]
            env.fill(0f)
            ap.fill(0f)
            var wsum = 0.0
            var voiced = 0.0
            var owner = -1
            var ownerW = -1.0
            while (first < n && placed[first].to < time) {
                first++
            }
            var i = maxOf(0, first - 1)
            while (i < n && placed[i].from <= time) {
                val p = placed[i]
                if (time > p.to) {
                    i++
                    continue
                }
                var w = 1.0
                if (p.fadeIn > 0) {
                    w *= clamp01((time - p.from) / p.fadeIn)
                }
                if (i + 1 < n) {
                    val q = placed[i + 1]
                    if (q.fadeIn > 0 && time >= q.from) {
                        w *= 1 - clamp01((time - q.from) / q.fadeIn)
                    }
                }
                if (w <= 0) {
                    i++
                    continue
                }
                val src = interp(p.outT, p.srcT, time) / fp
                val s0 = floor(src).toInt()
                val frac = (src - s0).toFloat()
                p.seg.envDb(s0, envA)
                p.seg.envDb(s0 + 1, envB)
                p.seg.apDb(s0, apA)
                p.seg.apDb(s0 + 1, apB)
                for (b in 0 until melBins) {
                    env[b] += (w * (envA[b] + (envB[b] - envA[b]) * frac)).toFloat()
                }
                for (b in 0 until bands) {
                    ap[b] += (w * (apA[b] + (apB[b] - apA[b]) * frac)).toFloat()
                }
                val v = p.seg.f0(jround(src)) > 0
                voiced += if (v) w else 0.0
                wsum += w
                if (w > ownerW) {
                    ownerW = w
                    owner = p.mora
                }
                i++
            }
            if (wsum <= 0) {
                env.fill(-200f)
                ap.fill(0f)
                track.f0[f] = 0.0
                gainDb[f] = Double.NaN
                continue
            }
            // which mora's vowel are we in? (decides devoicing and gain)
            var mora = owner
            while (mora + 1 < n && time >= onset[mora + 1]) {
                mora++
            }
            while (mora > 0 && time < onset[mora] - plan.consonant[mora]) {
                mora--
            }
            val m = morae[mora]
            val inVowel = time >= onset[mora]
            var gain = plan.gainDb[mora]
            if (!inVowel && m.devoiced) {
                gain = 0.0 // consonant of a devoiced mora keeps full level
            }
            // a lone unit fading in (phrase start) must actually fade: the missing weight is silence
            gainDb[f] = gain + 20 * log10(maxOf(1e-3, minOf(1.0, wsum)))
            for (b in 0 until melBins) {
                env[b] = (env[b] / wsum).toFloat()
            }
            val devoice = m.devoiced && inVowel
            for (b in 0 until bands) {
                var a = ap[b] / wsum
                if (breath > 0) {
                    a = a * (1 - breath)
                }
                ap[b] = (if (devoice) 0.0 else a).toFloat()
            }
            val isVoiced = voiced / wsum >= 0.5 && !devoice
            track.f0[f] = if (isVoiced) baseF0 * 2.0.pow(semitones[f] / 12.0) else 0.0
        }
        cleanVoicing(track, baseF0, semitones)
        softenWeakVoicing(track, apBandsHz)
        applyGain(track, gainDb, end, tail, volumeDb)
        return track
    }

    /**
     * Voicing comes from the source frames, which flip occasionally (aspiration after a release, irregular
     * phonation, cross-fades). A short noise burst inside a loud vowel is heard as a crackle, so short loud
     * unvoiced gaps are bridged (aperiodicity interpolated from the neighbours) and 1-2 frame voiced islands
     * are removed.
     */
    private fun cleanVoicing(track: ParamTrack, baseF0: Double, semitones: DoubleArray) {
        val frames = track.frames
        val level = levels(track)
        val maxGap = jround(0.025 / fp)
        var f = 0
        while (f < frames) {
            if (track.f0[f] > 0) {
                f++
                continue
            }
            var g = f
            while (g < frames && track.f0[g] <= 0) {
                g++
            }
            // unvoiced run [f, g)
            if (f > 0 && g < frames && g - f <= maxGap) {
                val edge = minOf(level[f - 1], level[g])
                var loud = true
                for (j in f until g) {
                    loud = loud and (level[j] > edge - 12)
                }
                if (loud) {
                    val apL = track.apDb[f - 1]
                    val apR = track.apDb[g]
                    for (j in f until g) {
                        val t = (j - f + 1) / (g - f + 1).toDouble()
                        track.f0[j] = baseF0 * 2.0.pow(semitones[j] / 12.0)
                        for (b in 0 until bands) {
                            track.apDb[j][b] = (apL[b] + (apR[b] - apL[b]) * t).toFloat()
                        }
                    }
                }
            }
            f = g
        }
        f = 0
        while (f < frames) {
            if (track.f0[f] <= 0) {
                f++
                continue
            }
            var g = f
            while (g < frames && track.f0[g] > 0) {
                g++
            }
            if (g - f <= 2 && f > 0 && g < frames) {
                for (j in f until g) {
                    track.f0[j] = 0.0
                    track.apDb[j].fill(0f)
                }
            }
            f = g
        }
    }

    /** Mean envelope level (dB) of each frame. */
    private fun levels(track: ParamTrack): DoubleArray = DoubleArray(track.frames) { f ->
        var s = 0.0
        for (v in track.envDb[f]) {
            s += fastPow10(v / 10.0)
        }
        10 * log10(s / melBins + 1e-30)
    }

    /**
     * Voiced frames far below the surrounding vowel level (decays into closures, weak onsets) have a flat
     * spectrum; rendered as pulses they sound like a train of ticks. Real speech has only a low voice bar
     * there, so the band aperiodicity above 1 kHz is raised the quieter the frame is.
     */
    private fun softenWeakVoicing(track: ParamTrack, bandHz: DoubleArray) {
        val frames = track.frames
        val level = levels(track)
        val half = jround(0.1 / fp)
        for (f in 0 until frames) {
            if (track.f0[f] <= 0) {
                continue
            }
            var ref = -1e9
            for (j in maxOf(0, f - half)..minOf(frames - 1, f + half)) {
                ref = maxOf(ref, level[j])
            }
            val t = clamp01((ref - level[f] - 10) / 12.0)
            if (t <= 0) {
                continue
            }
            val ap = track.apDb[f]
            for (b in 0 until bands) {
                if (bandHz[b] > 1000) {
                    ap[b] = (ap[b] * (1 - t)).toFloat()
                }
            }
        }
    }

    /** Applies per-mora gains smoothed over time (steps in level are heard as clicks), plus the final fade. */
    private fun applyGain(track: ParamTrack, gainDb: DoubleArray, end: Double, tail: Double, volumeDb: Double) {
        val frames = gainDb.size
        val half = maxOf(1, jround(GAIN_SMOOTHING / fp))
        for (f in 0 until frames) {
            if (gainDb[f].isNaN()) {
                continue
            }
            var sum = 0.0
            var count = 0
            for (j in maxOf(0, f - half)..minOf(frames - 1, f + half)) {
                if (!gainDb[j].isNaN()) {
                    sum += gainDb[j]
                    count++
                }
            }
            val fadeOut = clamp01((end - f * fp) / tail)
            val g = sum / count + volumeDb + 20 * log10(maxOf(1e-3, fadeOut))
            val env = track.envDb[f]
            for (b in 0 until melBins) {
                env[b] += g.toFloat()
            }
        }
    }

    private fun place(morae: List<Mora>, i: Int, onsetOut: Double, plan: ProsodyModel.Plan, extra: Double): Placed {
        val u = select(morae, i)
        val seg = pack.segment(u.segment)
        val c = plan.consonant[i]
        val v = plan.vowel[i] + extra
        val xf = if (i == 0) 0.03 else minOf(MAX_XFADE, 0.6 * plan.vowel[i - 1])

        var cs = u.consonantStart
        var on = u.onset
        if (u.prev != '-' && isPureVowel(u.kana)) {
            // sung vowel-to-vowel transitions often contain a breathy re-attack; skip it and let the
            // parameter cross-fade form the glide instead
            val from = floor(cs / fp).toInt()
            val to = ceil((on + 0.03) / fp).toInt()
            var lastUnvoiced = -1
            for (f in from..to) {
                if (seg.f0(f) <= 0) {
                    lastUnvoiced = f
                }
            }
            if (lastUnvoiced >= 0) {
                on = (lastUnvoiced + 2) * fp
                cs = on
            }
        }
        // consonant region: the decay out of the previous vowel and the release into the next vowel keep
        // their natural speed; only the middle (closure / steady frication) is compressed or stretched
        val srcCons = maxOf(0.0, on - cs)
        val keepS = minOf(srcCons, KEEP_BEFORE_ONSET)
        val headS = minOf(srcCons - keepS, KEEP_AFTER_START)
        val fit = minOf(1.0, 0.85 * c / maxOf(1e-9, headS + keepS))
        val keepO = keepS * fit
        val headO = headS * fit
        val usableEnd = u.end - 0.03

        val outT = DoubleArray(8)
        val srcT = DoubleArray(8)
        var k = 0
        outT[k] = onsetOut - c - xf
        srcT[k++] = cs - xf
        outT[k] = onsetOut - c
        srcT[k++] = cs
        if (headS > 0.005) {
            outT[k] = onsetOut - c + headO
            srcT[k++] = cs + headS
        }
        if (keepS > 0.005) {
            outT[k] = onsetOut - keepO
            srcT[k++] = on - keepS
        }
        outT[k] = onsetOut
        srcT[k++] = on
        if (on + v <= usableEnd) {
            outT[k] = onsetOut + v
            srcT[k++] = on + v
        } else {
            val steady = minOf(0.04, v)
            outT[k] = onsetOut + steady
            srcT[k++] = on + steady
            outT[k] = onsetOut + v
            srcT[k++] = maxOf(on + steady, usableEnd)
        }
        return Placed(u, seg, i, xf, outT.copyOf(k), srcT.copyOf(k))
    }

    /**
     * Frame-wise pitch in semitones: linear between the plan's knots, smoothed, plus slow random drift,
     * vibrato on held vowels and a soft limit to the style's pitch range.
     */
    private fun contour(
        plan: ProsodyModel.Plan, onset: DoubleArray, end: Double, frames: Int, vp: VoiceParams, seed: Long,
    ): DoubleArray {
        val n = onset.size
        val k = plan.knots.size
        val pts = arrayOfNulls<DoubleArray>(k + 2)
        pts[0] = doubleArrayOf(0.0, plan.startPitch)
        for (i in 0 until k) {
            val kn = plan.knots[i]
            val m = kn[0].toInt()
            pts[i + 1] = doubleArrayOf(onset[m] + kn[1] * plan.vowel[m], kn[2])
        }
        pts[k + 1] = doubleArrayOf(maxOf(end, pts[k]!![0] + 1e-3), plan.endPitch)
        pts.sortWith({ a, b -> a!![0].compareTo(b!![0]) }, 1, k + 1)
        val kt = DoubleArray(k + 2) { pts[it]!![0] }
        val kv = DoubleArray(k + 2) { pts[it]!![1] }
        val raw = DoubleArray(frames) { interp(kt, kv, it * fp) }
        val half = maxOf(1, jround(plan.smoothing / 2 / fp))
        val out = DoubleArray(frames)
        for (f in 0 until frames) {
            var s = 0.0
            var c = 0
            for (j in f - half..f + half) {
                s += raw[maxOf(0, minOf(frames - 1, j))]
                c++
            }
            out[f] = s / c * vp.range
        }
        if (vp.liveliness > 0) {
            val rnd = JavaRandom(seed)
            var drift = 0.0
            var vel = 0.0
            for (f in 0 until frames) {
                vel = 0.9 * vel + 0.02 * rnd.nextGaussian()
                drift = 0.97 * drift + vel
                out[f] += maxOf(-0.6, minOf(0.6, drift)) * vp.liveliness
            }
        }
        // vibrato fades in on vowels held long enough to notice it
        var m = 0
        for (f in 0 until frames) {
            val t = f * fp
            while (m + 1 < n && t >= onset[m + 1]) {
                m++
            }
            val depth = plan.vibrato[m]
            val into = t - onset[m]
            if (depth > 0 && into >= 0 && into <= plan.vowel[m] && plan.vowel[m] > 0.12) {
                val ramp = clamp01((into - 0.06) / 0.1)
                out[f] += depth * ramp * sin(2 * PI * plan.vibratoRate * into)
            }
        }
        for (f in 0 until frames) {
            out[f] = softLimit(out[f], plan.capLow, plan.capHigh)
        }
        return out
    }

    companion object {
        private const val LEAD = 0.04
        private const val TAIL = 0.07
        private const val MAX_XFADE = 0.025

        /** Release / consonant-to-vowel transition kept at natural speed. */
        private const val KEEP_BEFORE_ONSET = 0.04

        /** Vowel-to-consonant decay kept at natural speed (compressing it causes audible clicks). */
        private const val KEEP_AFTER_START = 0.03
        private const val GAIN_SMOOTHING = 0.02

        /** Time of the phrase's first beat (first syllable's vowel onset after any pickup) in its rendered audio. */
        fun firstBeat(plan: ProsodyModel.Plan): Double = LEAD + plan.consonant[0] + plan.firstBeat

        private fun isPureVowel(kana: String): Boolean = kana.length == 1 && "あいうえお".indexOf(kana[0]) >= 0

        private fun vowelKana(v: Char): String = when (v) {
            'a' -> "あ"
            'i' -> "い"
            'u' -> "う"
            'e' -> "え"
            'o' -> "お"
            else -> "ん"
        }

        /** Linear inside the range, approaching the limits smoothly over the last 2 semitones. */
        internal fun softLimit(st: Double, lo: Double, hi: Double): Double {
            val knee = minOf(2.0, (hi - lo) / 4)
            if (st > hi - knee) {
                return hi - knee + knee * tanh((st - (hi - knee)) / knee)
            }
            if (st < lo + knee) {
                return lo + knee - knee * tanh(((lo + knee) - st) / knee)
            }
            return st
        }

        internal fun interp(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
            if (x <= xs[0]) {
                return ys[0]
            }
            val last = xs.size - 1
            if (x >= xs[last]) {
                return ys[last]
            }
            var i = 0
            while (i < last - 1 && xs[i + 1] <= x) {
                i++
            }
            val span = xs[i + 1] - xs[i]
            if (span <= 0) {
                return ys[i + 1]
            }
            return ys[i] + (ys[i + 1] - ys[i]) * (x - xs[i]) / span
        }

        private fun clamp01(v: Double): Double = maxOf(0.0, minOf(1.0, v))
    }
}
