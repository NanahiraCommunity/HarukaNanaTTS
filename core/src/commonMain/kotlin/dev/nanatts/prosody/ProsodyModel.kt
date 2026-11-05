package dev.nanatts.prosody

import dev.nanatts.VoiceParams
import dev.nanatts.text.Mora
import dev.nanatts.text.Phrase
import dev.nanatts.util.JavaRandom
import dev.nanatts.util.jround
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Rule-based prosody driven by a [StyleProfile]: per-mora consonant/vowel durations and gains, and a
 * pitch contour given as knots (semitones relative to the voice's recorded pitch).
 */
class ProsodyModel {

    /** Timing and pitch plan for one phrase. Durations in seconds, pitch in semitones. */
    class Plan internal constructor(n: Int) {
        /** Time from consonant start to vowel onset. */
        val consonant = DoubleArray(n)

        /** Time from vowel onset to the next mora's consonant start. */
        val vowel = DoubleArray(n)
        val gainDb = DoubleArray(n)

        /** Vibrato depth (semitones) per mora; applied to held vowels only. */
        val vibrato = DoubleArray(n)

        /** Pitch knots `{mora, fraction of the vowel, semitones}`, in time order. */
        val knots: MutableList<DoubleArray> = ArrayList()

        /** Pitch at the very start and end of the phrase. */
        var startPitch = 0.0
        var endPitch = 0.0
        var vibratoRate = 0.0
        var smoothing = 0.0
        var capLow = 0.0
        var capHigh = 0.0

        /** Strength of the beat grid (0 = natural timing) and its slot length in seconds. */
        var rhythm = 0.0
        var beat = 0.0

        /** Beat slots per half bar: phrases start on half-bar boundaries when rhythmic. */
        var halfBar = 2

        /** Time from the first vowel onset to the end of the last vowel. */
        var span = 0.0

        /** Time from the first vowel onset to the first beat (non-zero when the phrase starts with a pickup). */
        var firstBeat = 0.0

        /** Beat slots of rest after the phrase (when rhythmic). */
        var restBeats = 0

        internal fun knot(mora: Int, frac: Double, st: Double) {
            knots.add(doubleArrayOf(mora.toDouble(), frac, st))
        }
    }

    /** Plans a phrase with a fresh variation generator (for tests and single phrases). */
    fun plan(phrase: Phrase, vp: VoiceParams): Plan = plan(phrase, vp, JavaRandom(SEED))

    /** Plans a phrase; pass one generator through all phrases of an utterance. */
    fun plan(phrase: Phrase, vp: VoiceParams, rnd: JavaRandom): Plan {
        val s = vp.style
        val morae = phrase.morae
        val n = morae.size
        val plan = Plan(n)
        val accent = chooseAccents(morae, s)
        val rate = vp.speed
        for (i in 0 until n) {
            val m = morae[i]
            val prev = if (i > 0) morae[i - 1] else null
            var c = consonantDuration(m, prev)
            if (m.geminate) {
                c += if (m.consonant.isEmpty()) 0.03 else 0.07
            }
            var v = vowelDuration(m, i == n - 1)
            if (accent[i]) {
                v *= s["stressStretch"]
            } else if (m.stress == 1 && !m.functionWord) {
                v *= 1.1
            } else if (i < n - 1 && !m.epenthetic && !m.longVowel) {
                v *= s["unstressedSquash"]
            }
            if (m.emphasis) {
                v *= 1.25
            }
            if (m.glide) {
                v = maxOf(v * 2.2, 0.22)
            }
            if (phrase.sung && !m.epenthetic) {
                v *= 1.3
            }
            if (m.devoiced) {
                // a devoiced vowel is mostly heard as a longer consonant (s -> sss, t -> aspirated release)
                c += 0.6 * v
                v *= 0.4
            }
            plan.consonant[i] = c / rate
            plan.vowel[i] = v / rate
            var g = 0.0
            if (accent[i]) {
                g += s["accentGainDb"]
            }
            if (m.emphasis) {
                g += 1.5
            }
            if (m.epenthetic) {
                g -= 1.0
            }
            if (m.devoiced) {
                g -= 9.0
            }
            plan.gainDb[i] = g
        }
        pitch(phrase, plan, accent, s, rnd)
        rhythm(phrase, plan, accent, s, rate)
        plan.vibratoRate = s["vibratoRate"]
        plan.smoothing = s["smoothing"]
        plan.capLow = s["capLow"]
        plan.capHigh = s["capHigh"]
        return plan
    }

    companion object {
        /** Seed of the variation generator: the same text always sounds the same. */
        const val SEED = 42L

        private val PENTATONIC = intArrayOf(0, 2, 4, 7, 9)

        /**
         * Syllable heads: morae that start a beat. Epenthetic vowels (the と of きゃっと), diphthong glides and a
         * closing ん belong to the syllable before them instead of taking a beat of their own; morae before the
         * first head (the と of "twinkle" at a phrase start) are a pickup before the first beat.
         */
        fun beatHeads(morae: List<Mora>): BooleanArray {
            val heads = BooleanArray(morae.size)
            var any = false
            for (i in heads.indices) {
                val m = morae[i]
                heads[i] = !(m.epenthetic || m.offglide || m.isNasal && !m.wordStart)
                any = any or heads[i]
            }
            if (!any && heads.isNotEmpty()) {
                heads[0] = true
            }
            return heads
        }

        /**
         * Pulls syllable onsets onto a beat grid. Each syllable gets a whole number of beat slots close to its
         * natural length; accented syllables are moved onto strong (even) slots by holding the previous
         * syllable longer or shorter; the last syllable is held to fill the half bar. Vowel onsets land on the
         * beat (consonants come just before, as in singing). The difference is absorbed by the vowels, mostly
         * by holding the syllable's own vowel. `rhythm` blends between natural and grid timing.
         */
        private fun rhythm(phrase: Phrase, plan: Plan, accent: BooleanArray, s: StyleProfile, rate: Double) {
            val n = phrase.morae.size
            val strength = if (phrase.sung) 1.0 else maxOf(0.0, minOf(1.0, s["rhythm"]))
            val beat = s["beat"] / rate
            val barBeats = maxOf(2, jround(s["barBeats"]))
            plan.rhythm = strength
            plan.beat = beat
            plan.halfBar = maxOf(1, barBeats / 2)
            plan.restBeats = if (phrase.ending == Phrase.Ending.CONTINUATION) 1 else 2
            val onset = onsets(plan)
            plan.span = onset[n - 1] + plan.vowel[n - 1]
            if (strength <= 0) {
                return
            }
            val head = beatHeads(phrase.morae)
            val h = ArrayList<Int>()
            for (i in 0 until n) {
                if (head[i]) {
                    h.add(i)
                }
            }
            val k = h.size
            val natural = DoubleArray(k)
            val slots = IntArray(k)
            val minSlots = IntArray(k)
            val floor = 0.035
            for (u in 0 until k) {
                val next = if (u + 1 < k) onset[h[u + 1]] else plan.span
                natural[u] = next - onset[h[u]]
                // never fewer slots than the syllable's consonants plus minimal vowels need (あんど, let's)
                val to = if (u + 1 < k) h[u + 1] else n
                var minimum = 0.0
                for (i in h[u] until to) {
                    minimum += minOf(plan.vowel[i], floor) +
                        if (i + 1 < n && (i + 1 < to || u + 1 < k)) plan.consonant[i + 1] else 0.0
                }
                val feasible = ceil(minimum / beat - 1e-9).toInt()
                minSlots[u] = feasible
                slots[u] = maxOf(maxOf(1, feasible), jround(natural[u] / beat))
                val m = phrase.morae[h[u]]
                if (m.longVowel || m.glide) {
                    slots[u] = maxOf(2, slots[u])
                }
            }
            // accented syllables on strong beats
            var pos = 0
            for (u in 0 until k) {
                if (u > 0 && accent[h[u]] && pos % 2 == 1) {
                    if (slots[u - 1] >= 2 && natural[u - 1] / beat < slots[u - 1] && slots[u - 1] - 1 >= minSlots[u - 1]) {
                        slots[u - 1]--
                        pos--
                    } else {
                        slots[u - 1]++
                        pos++
                    }
                }
                pos += slots[u]
            }
            // the last syllable is held to the end of the half bar
            slots[k - 1] = maxOf(2, slots[k - 1])
            var total = 0
            for (sl in slots) {
                total += sl
            }
            val pad = (plan.halfBar - total % plan.halfBar) % plan.halfBar
            slots[k - 1] += pad

            // target onsets, blended with the natural ones, then fit unit by unit (pickup morae keep their timing)
            plan.firstBeat = onset[h[0]]
            var grid = plan.firstBeat
            var actual = plan.firstBeat
            for (u in 0 until k) {
                grid += slots[u] * beat
                val naturalNext = if (u + 1 < k) onset[h[u + 1]] else plan.span
                val target = naturalNext + strength * (grid - naturalNext)
                val from = h[u]
                val to = if (u + 1 < k) h[u + 1] else n // exclusive
                var current = 0.0
                for (i in from until to) {
                    current += plan.vowel[i]
                    if (i + 1 < to) {
                        current += plan.consonant[i + 1]
                    }
                }
                if (u + 1 < k) {
                    current += plan.consonant[to]
                }
                var delta = target - actual - current
                if (delta > 0) {
                    plan.vowel[from] += delta // hold the note
                } else if (delta < 0) {
                    var available = 0.0
                    for (i in from until to) {
                        available += maxOf(0.0, plan.vowel[i] - floor)
                    }
                    val take = minOf(-delta, available)
                    if (available > 0) {
                        for (i in from until to) {
                            plan.vowel[i] -= maxOf(0.0, plan.vowel[i] - floor) / available * take
                        }
                    }
                    delta = -take
                }
                actual += current + delta
            }
            plan.span = actual
        }

        /** Vowel onset times relative to the first vowel onset. */
        internal fun onsets(plan: Plan): DoubleArray {
            val n = plan.vowel.size
            val t = DoubleArray(n)
            for (i in 1 until n) {
                t[i] = t[i - 1] + plan.vowel[i - 1] + plan.consonant[i]
            }
            return t
        }

        /**
         * Picks the accented morae: heads of stressed content words, thinned out by `accentDensity`
         * (emphasised words, the first and the last accent are always kept; longer words win).
         */
        internal fun chooseAccents(morae: List<Mora>, s: StyleProfile): BooleanArray {
            val n = morae.size
            val accent = BooleanArray(n)
            val candidates = ArrayList<IntArray>() // {mora index, word length, forced}
            var wordStart = 0
            for (i in 0 until n) {
                if (morae[i].wordStart) {
                    wordStart = i
                }
                val m = morae[i]
                val head = m.accentHead && m.stress == 1 && !m.functionWord
                val emphasisHead = m.emphasis && (i == 0 || !morae[i - 1].emphasis || m.wordStart) &&
                    !hasHeadInWord(morae, i)
                if (head || emphasisHead) {
                    var end = i
                    while (end + 1 < n && !morae[end + 1].wordStart) {
                        end++
                    }
                    candidates.add(intArrayOf(i, end - wordStart + 1, if (m.emphasis) 1 else 0))
                }
            }
            if (candidates.isEmpty()) {
                return accent
            }
            candidates[0][2] = 1
            candidates[candidates.size - 1][2] = 1
            val keep = jround(s["accentDensity"] * candidates.size)
            val ranked = ArrayList(candidates)
            ranked.sortWith { a, b -> if (a[2] != b[2]) b[2] - a[2] else if (a[1] != b[1]) b[1] - a[1] else a[0] - b[0] }
            for (k in ranked.indices) {
                val c = ranked[k]
                if (c[2] == 1 || k < keep) {
                    accent[c[0]] = true
                }
            }
            return accent
        }

        /** True if the emphasised word containing mora i already has a regular accent head. */
        private fun hasHeadInWord(morae: List<Mora>, i: Int): Boolean {
            var a = i
            while (a > 0 && !morae[a].wordStart) {
                a--
            }
            var j = a
            while (j < morae.size && (j == a || !morae[j].wordStart)) {
                val m = morae[j]
                if (m.accentHead && m.stress == 1) {
                    return true
                }
                j++
            }
            return false
        }

        internal fun consonantDuration(m: Mora, prev: Mora?): Double {
            if (m.isNasal) {
                return 0.03
            }
            val c = m.consonant
            if (c.isEmpty()) {
                return if (prev == null) 0.02 else 0.035
            }
            return when (c) {
                "k", "t", "p" -> 0.08
                "g", "d", "b" -> 0.065
                "s" -> 0.11
                "z" -> 0.085
                "sh" -> 0.115
                "j" -> 0.09
                "ts", "ch" -> 0.10
                "h" -> 0.075
                "f" -> 0.09
                "v" -> 0.07
                "n", "m" -> 0.065
                "r" -> 0.045
                "y", "w" -> 0.055
                // palatalised / labialised rows (ky, ny, kw, ty...)
                else -> consonantDuration(Mora(m.kana, c.substring(0, 1), m.vowel), prev) + 0.015
            }
        }

        internal fun vowelDuration(m: Mora, last: Boolean): Double {
            var v = if (m.isNasal) 0.065 else 0.07
            if (m.epenthetic) {
                v = 0.05
            } else if (m.stress == 2) {
                v *= 1.1
            } else if (m.functionWord) {
                v *= 0.85
            }
            if (m.offglide) {
                v *= 0.7
            }
            if (m.longVowel) {
                v *= 1.8
            }
            if (last) {
                v *= if (m.epenthetic) 1.8 else 1.5
            } else if (m.wordEnd) {
                v *= 1.1
            }
            return v
        }

        private fun pitch(phrase: Phrase, plan: Plan, accent: BooleanArray, s: StyleProfile, rnd: JavaRandom) {
            val morae = phrase.morae
            val n = morae.size
            val variation = s["variation"]
            val exclamation = phrase.ending == Phrase.Ending.EXCLAMATION
            var range = if (exclamation) s["exclamationRange"] else 1.0
            if (phrase.excited) {
                range *= 1.2
            }
            var offset = s["baseShift"]
            if (phrase.ending == Phrase.Ending.WH_QUESTION) {
                offset += 1.5
            }
            if (exclamation || phrase.excited) {
                offset += 1.0
            }
            val snap = if (phrase.sung) 1.0 else s["snap"]
            val declination = s["declination"]
            var accents = 0
            for (a in accent) {
                accents += if (a) 1 else 0
            }

            var accentIndex = 0
            var carry = 0.0
            val target = DoubleArray(n)
            for (i in 0 until n) {
                val m = morae[i]
                val pos = if (n == 1) 0.0 else i / (n - 1).toDouble()
                val baseline = offset + declination * (0.5 - pos)
                val knotsBefore = plan.knots.size
                var t: Double
                val explicit = m.pitch
                if (explicit != null) {
                    t = baseline + explicit
                    plan.knot(i, 0.5, t)
                } else if (accent[i]) {
                    var h = s["accentHeight"] * s["downstep"].pow(accentIndex)
                    if (accentIndex % 2 == 1) {
                        h -= s["alternation"]
                    }
                    if (accentIndex == accents - 1) {
                        h += s["finalAccentBoost"]
                    }
                    if (m.emphasis) {
                        h += s["emphasisBoost"]
                    }
                    h *= 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.5
                    h = maxOf(0.5, h) * range
                    accentIndex++
                    t = snap(baseline + h, snap)
                    // rise through the accented vowel, peak late (delayed peaks sound lively)
                    plan.knot(i, 0.0, baseline + 0.25 * h)
                    plan.knot(i, s["peakDelay"], t)
                    carry = h
                } else if (m.accentHead && m.stress >= 1 && !m.functionWord) {
                    val h = s["secondaryAccent"] * range
                    t = baseline + h
                    plan.knot(i, 0.5, t)
                    carry = h
                } else {
                    carry *= if (m.wordStart) 0.2 else 0.45
                    val dip = s["dipDepth"] * (1 - minOf(1.0, carry / 2.0))
                    t = baseline + carry - dip
                    plan.knot(i, 0.5, t)
                }
                if (phrase.sung) {
                    // hold each mora on a note
                    t = snap(t, 1.0)
                    while (plan.knots.size > knotsBefore) {
                        plan.knots.removeAt(plan.knots.size - 1)
                    }
                    plan.knot(i, 0.1, t)
                    plan.knot(i, 0.9, t)
                    plan.vibrato[i] = s["vibratoDepth"]
                }
                if (m.glide) {
                    val g = s["glideHeight"] * range
                    while (plan.knots.size > knotsBefore) {
                        plan.knots.removeAt(plan.knots.size - 1)
                    }
                    plan.knot(i, 0.0, t)
                    plan.knot(i, 0.45, snap(t + g, snap))
                    plan.knot(i, 1.0, t - 0.4 * g)
                    plan.vibrato[i] = s["vibratoDepth"] * 0.6
                }
                target[i] = t
            }

            plan.startPitch = plan.knots[0][2] - 0.5
            val last = n - 1
            var lastT = target[last]
            val glideEnding = morae[last].glide
            val vary = 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.5
            when (phrase.ending) {
                Phrase.Ending.QUESTION -> {
                    if (!glideEnding) {
                        replaceLastKnots(plan, last, lastT + s["questionRise"] * 0.35)
                    }
                    plan.endPitch = snap(lastT + s["questionRise"] * vary * range, snap)
                }
                Phrase.Ending.CONTINUATION -> plan.endPitch = snap(lastT + s["continuationRise"] * vary, snap)
                else -> {
                    val fall = s["statementFall"] * vary * if (exclamation) range else 1.0
                    if (glideEnding) {
                        plan.endPitch = lastT - 0.5 * fall
                        return
                    }
                    if (n > 1 && !accent[last]) {
                        lastT = minOf(lastT, target[last - 1] - 1)
                        replaceLastKnots(plan, last, lastT)
                    }
                    val low = lastT - fall
                    val bounce = s["bounceEnding"]
                    if (bounce > 0) {
                        // fall early in the last vowel, then a small lift: cheerful instead of final
                        plan.knot(last, if (accent[last]) 0.95 else 0.6, low)
                        plan.endPitch = snap(low + 2.0 * bounce, snap)
                    } else {
                        plan.endPitch = low
                    }
                }
            }
        }

        /** Replaces the knots of the last mora by a single target (keeps an accent rise). */
        private fun replaceLastKnots(plan: Plan, last: Int, st: Double) {
            val k = plan.knots[plan.knots.size - 1]
            if (k[0].toInt() == last) {
                k[2] = st
            }
        }

        /** Pulls a pitch towards the nearest note of the major pentatonic scale on the base pitch. */
        fun snap(st: Double, amount: Double): Double {
            if (amount <= 0) {
                return st
            }
            var best = st
            var bestDist = Double.MAX_VALUE
            val octave = floor(st / 12.0).toInt()
            for (o in octave - 1..octave + 1) {
                for (d in PENTATONIC) {
                    val note = (o * 12 + d).toDouble()
                    val dist = abs(note - st)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = note
                    }
                }
            }
            return st + (best - st) * minOf(1.0, amount)
        }
    }
}
