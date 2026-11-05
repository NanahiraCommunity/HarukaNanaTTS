package dev.nanatts.prosody

/**
 * A speaking style: named numeric knobs read by [ProsodyModel]. Immutable; derive variants with
 * [with]. Pitch values are in semitones relative to the voice's recorded pitch.
 *
 * Profiles can be stored as `.properties` files containing any subset of the knobs; missing
 * knobs keep the values of the base profile.
 */
class StyleProfile private constructor(private val values: Map<String, Double>) {

    /** Returns a copy with one knob changed. */
    fun with(knob: String, value: Double): StyleProfile {
        check(knob)
        require(value.isFinite()) { "$knob must be finite" }
        val m = LinkedHashMap(values)
        m[knob] = value
        return StyleProfile(m)
    }

    operator fun get(knob: String): Double {
        check(knob)
        return values.getValue(knob)
    }

    /**
     * Applies the knobs found in properties-file text (`knob = value` lines, `#` comments) on top of this
     * profile. Throws [IllegalArgumentException] for unknown knobs and values that are not numbers.
     */
    fun load(properties: String): StyleProfile {
        var s = this
        for ((key, value) in parseProperties(properties)) {
            val v = value.trim().toDoubleOrNull() ?: throw IllegalArgumentException("style knob $key: not a number")
            s = s.with(key.trim(), v)
        }
        return s
    }

    /** Properties-file form of this profile. */
    override fun toString(): String {
        val sb = StringBuilder()
        for ((knob, description) in KNOBS) {
            sb.append("# ").append(description).append('\n').append(knob).append('=').append(values[knob]).append('\n')
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is StyleProfile && other.values == values

    override fun hashCode(): Int = values.hashCode()

    private fun check(knob: String) {
        require(knob in values) { "unknown style knob: $knob" }
    }

    companion object {
        /** Every knob with a short description (the order is used for printing). */
        private val KNOBS = listOf(
            "baseShift" to "overall pitch offset (st)",
            "declination" to "pitch drop from phrase start to end (st)",
            "accentHeight" to "height of a stressed-word accent (st)",
            "accentDensity" to "fraction of content words that get an accent (0..1)",
            "downstep" to "each later accent is this factor of the previous (1 = no shrinking)",
            "alternation" to "every second accent is this much lower (st)",
            "finalAccentBoost" to "extra height of the last accent in a phrase (st)",
            "peakDelay" to "where in the accented vowel the peak lands (0..1)",
            "dipDepth" to "how far unaccented morae sink below the baseline (st)",
            "secondaryAccent" to "bump on secondary-stress syllables (st)",
            "stressStretch" to "length factor of accented vowels",
            "unstressedSquash" to "length factor of unaccented, non-final vowels",
            "accentGainDb" to "loudness boost on accented morae (dB)",
            "bounceEnding" to "statements end with a fall plus a small lift (0 = plain fall, 1 = full bounce)",
            "statementFall" to "pitch fall at the end of a statement (st)",
            "questionRise" to "pitch rise at the end of a yes/no question (st)",
            "continuationRise" to "pitch rise before a comma (st)",
            "exclamationRange" to "range multiplier for exclamations",
            "emphasisBoost" to "extra accent height for *emphasised* words (st)",
            "glideHeight" to "rise of a ~ glide (st)",
            "snap" to "pull of pitch targets towards a pentatonic scale (0..1)",
            "vibratoDepth" to "vibrato depth on sung/held vowels (st)",
            "vibratoRate" to "vibrato rate (Hz)",
            "rhythm" to "pull of syllable timing onto a beat grid (0 = natural speech, 1 = strict)",
            "beat" to "length of one beat slot (s) at speed 1; a syllable takes one or more slots",
            "barBeats" to "beat slots per bar; phrases end and start on half-bar boundaries",
            "variation" to "random variation of accents and endings (0..1)",
            "smoothing" to "pitch contour smoothing window (s)",
            "capLow" to "lowest pitch (st), soft limit",
            "capHigh" to "highest pitch (st), soft limit",
        )

        /** Calm, newsreader-like intonation (the original model). */
        val NEUTRAL = StyleProfile(
            values(
                "baseShift" to 0, "declination" to 3, "accentHeight" to 3.5, "accentDensity" to 1, "downstep" to 0.85,
                "alternation" to 0, "finalAccentBoost" to 0, "peakDelay" to 0.5, "dipDepth" to 0,
                "secondaryAccent" to 1.2, "stressStretch" to 1.35, "unstressedSquash" to 1, "accentGainDb" to 1.5,
                "bounceEnding" to 0, "statementFall" to 3.5, "questionRise" to 6, "continuationRise" to 1.5,
                "exclamationRange" to 1.4, "emphasisBoost" to 2, "glideHeight" to 2, "snap" to 0,
                "vibratoDepth" to 0.3, "vibratoRate" to 5.5, "rhythm" to 0, "beat" to 0.2, "barBeats" to 4,
                "variation" to 0.5, "smoothing" to 0.05, "capLow" to -6, "capHigh" to 8,
            ),
        )

        /** Bouncy, bright, slightly sing-song intonation: Nana's default. */
        val PLAYFUL = StyleProfile(
            values(
                "baseShift" to 0, "declination" to 2, "accentHeight" to 5, "accentDensity" to 1, "downstep" to 1,
                "alternation" to 1.5, "finalAccentBoost" to 2, "peakDelay" to 0.6, "dipDepth" to 2,
                "secondaryAccent" to 1, "stressStretch" to 1.45, "unstressedSquash" to 0.85, "accentGainDb" to 2.5,
                "bounceEnding" to 1, "statementFall" to 3, "questionRise" to 7, "continuationRise" to 3,
                "exclamationRange" to 1.3, "emphasisBoost" to 2.5, "glideHeight" to 3, "snap" to 0.3,
                "vibratoDepth" to 0.35, "vibratoRate" to 5.5, "rhythm" to 0, "beat" to 0.2, "barBeats" to 4,
                "variation" to 0.5, "smoothing" to 0.06, "capLow" to -6, "capHigh" to 8,
            ),
        )

        private fun values(vararg kv: Pair<String, Number>): Map<String, Double> {
            val m = LinkedHashMap<String, Double>()
            for ((k, v) in kv) {
                m[k] = v.toDouble()
            }
            for ((knob, _) in KNOBS) {
                check(knob in m) { "preset misses knob $knob" }
            }
            return m
        }

        /** All knob names. */
        fun knobs(): Set<String> = KNOBS.mapTo(LinkedHashSet()) { it.first }

        /** Human-readable description of a knob. */
        fun describe(knob: String): String =
            KNOBS.firstOrNull { it.first == knob }?.second ?: throw IllegalArgumentException("unknown style knob: $knob")

        /** The subset of the `.properties` format that style files use: key/value lines, comments, continuations. */
        private fun parseProperties(text: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val lines = text.split("\r\n", "\n", "\r")
            var i = 0
            while (i < lines.size) {
                var line = lines[i++].trimStart()
                if (line.isEmpty() || line[0] == '#' || line[0] == '!') {
                    continue
                }
                while (line.endsWith("\\") && i < lines.size) {
                    line = line.dropLast(1) + lines[i++].trimStart()
                }
                var k = 0
                while (k < line.length && line[k] != '=' && line[k] != ':' && !line[k].isWhitespace()) {
                    k++
                }
                var v = k
                while (v < line.length && line[v].isWhitespace()) {
                    v++
                }
                if (v < line.length && (line[v] == '=' || line[v] == ':')) {
                    v++
                }
                out.add(line.substring(0, k) to line.substring(v).trimStart())
            }
            return out
        }
    }
}
