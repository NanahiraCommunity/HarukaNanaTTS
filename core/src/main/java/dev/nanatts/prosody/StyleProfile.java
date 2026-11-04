package dev.nanatts.prosody;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A speaking style: named numeric knobs read by {@link ProsodyModel}. Immutable; derive variants with
 * {@link #with}. Pitch values are in semitones relative to the voice's recorded pitch.
 *
 * <p>Profiles can be stored as {@code .properties} files containing any subset of the knobs; missing
 * knobs keep the values of the base profile.
 */
public final class StyleProfile {

    /** Every knob with a short description (the order is used for printing). */
    private static final String[][] KNOBS = {
            {"baseShift", "overall pitch offset (st)"},
            {"declination", "pitch drop from phrase start to end (st)"},
            {"accentHeight", "height of a stressed-word accent (st)"},
            {"accentDensity", "fraction of content words that get an accent (0..1)"},
            {"downstep", "each later accent is this factor of the previous (1 = no shrinking)"},
            {"alternation", "every second accent is this much lower (st)"},
            {"finalAccentBoost", "extra height of the last accent in a phrase (st)"},
            {"peakDelay", "where in the accented vowel the peak lands (0..1)"},
            {"dipDepth", "how far unaccented morae sink below the baseline (st)"},
            {"secondaryAccent", "bump on secondary-stress syllables (st)"},
            {"stressStretch", "length factor of accented vowels"},
            {"unstressedSquash", "length factor of unaccented, non-final vowels"},
            {"accentGainDb", "loudness boost on accented morae (dB)"},
            {"bounceEnding", "statements end with a fall plus a small lift (0 = plain fall, 1 = full bounce)"},
            {"statementFall", "pitch fall at the end of a statement (st)"},
            {"questionRise", "pitch rise at the end of a yes/no question (st)"},
            {"continuationRise", "pitch rise before a comma (st)"},
            {"exclamationRange", "range multiplier for exclamations"},
            {"emphasisBoost", "extra accent height for *emphasised* words (st)"},
            {"glideHeight", "rise of a ~ glide (st)"},
            {"snap", "pull of pitch targets towards a pentatonic scale (0..1)"},
            {"vibratoDepth", "vibrato depth on sung/held vowels (st)"},
            {"vibratoRate", "vibrato rate (Hz)"},
            {"rhythm", "pull of syllable timing onto a beat grid (0 = natural speech, 1 = strict)"},
            {"beat", "length of one beat slot (s) at speed 1; a syllable takes one or more slots"},
            {"barBeats", "beat slots per bar; phrases end and start on half-bar boundaries"},
            {"variation", "random variation of accents and endings (0..1)"},
            {"smoothing", "pitch contour smoothing window (s)"},
            {"capLow", "lowest pitch (st), soft limit"},
            {"capHigh", "highest pitch (st), soft limit"},
    };

    /** Calm, newsreader-like intonation (the original model). */
    public static final StyleProfile NEUTRAL = new StyleProfile(values(
            "baseShift", 0, "declination", 3, "accentHeight", 3.5, "accentDensity", 1, "downstep", 0.85,
            "alternation", 0, "finalAccentBoost", 0, "peakDelay", 0.5, "dipDepth", 0, "secondaryAccent", 1.2,
            "stressStretch", 1.35, "unstressedSquash", 1, "accentGainDb", 1.5, "bounceEnding", 0,
            "statementFall", 3.5, "questionRise", 6, "continuationRise", 1.5, "exclamationRange", 1.4,
            "emphasisBoost", 2, "glideHeight", 2, "snap", 0, "vibratoDepth", 0.3, "vibratoRate", 5.5,
            "rhythm", 0, "beat", 0.2, "barBeats", 4, "variation", 0.5, "smoothing", 0.05, "capLow", -6, "capHigh", 8));

    /** Bouncy, bright, slightly sing-song intonation: Nana's default. */
    public static final StyleProfile PLAYFUL = new StyleProfile(values(
            "baseShift", 0, "declination", 2, "accentHeight", 5, "accentDensity", 1, "downstep", 1,
            "alternation", 1.5, "finalAccentBoost", 2, "peakDelay", 0.6, "dipDepth", 2, "secondaryAccent", 1,
            "stressStretch", 1.45, "unstressedSquash", 0.85, "accentGainDb", 2.5, "bounceEnding", 1,
            "statementFall", 3, "questionRise", 7, "continuationRise", 3, "exclamationRange", 1.3,
            "emphasisBoost", 2.5, "glideHeight", 3, "snap", 0.3, "vibratoDepth", 0.35, "vibratoRate", 5.5,
            "rhythm", 0, "beat", 0.2, "barBeats", 4, "variation", 0.5, "smoothing", 0.06, "capLow", -6, "capHigh", 8));

    private final Map<String, Double> values;

    private StyleProfile(Map<String, Double> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    private static Map<String, Double> values(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        for (String[] k : KNOBS) {
            if (!m.containsKey(k[0])) {
                throw new IllegalStateException("preset misses knob " + k[0]);
            }
        }
        return m;
    }

    /** Returns a copy with one knob changed. */
    public StyleProfile with(String knob, double value) {
        check(knob);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(knob + " must be finite");
        }
        Map<String, Double> m = new LinkedHashMap<>(values);
        m.put(knob, value);
        return new StyleProfile(m);
    }

    public double get(String knob) {
        check(knob);
        return values.get(knob);
    }

    /** All knob names. */
    public static Set<String> knobs() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] k : KNOBS) {
            m.put(k[0], k[1]);
        }
        return Collections.unmodifiableSet(m.keySet());
    }

    /** Human-readable description of a knob. */
    public static String describe(String knob) {
        for (String[] k : KNOBS) {
            if (k[0].equals(knob)) {
                return k[1];
            }
        }
        throw new IllegalArgumentException("unknown style knob: " + knob);
    }

    /** Applies the knobs found in a properties stream on top of this profile. */
    public StyleProfile load(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        StyleProfile s = this;
        for (String key : p.stringPropertyNames()) {
            try {
                s = s.with(key.trim(), Double.parseDouble(p.getProperty(key).trim()));
            } catch (NumberFormatException e) {
                throw new IOException("style knob " + key + ": not a number", e);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return s;
    }

    /** Properties-file form of this profile. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String[] k : KNOBS) {
            sb.append("# ").append(k[1]).append('\n').append(k[0]).append('=').append(values.get(k[0])).append('\n');
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StyleProfile && ((StyleProfile) o).values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    private void check(String knob) {
        if (!values.containsKey(knob)) {
            throw new IllegalArgumentException("unknown style knob: " + knob);
        }
    }
}
