package dev.nanatts.prosody;

import dev.nanatts.VoiceParams;
import dev.nanatts.text.Mora;
import dev.nanatts.text.Phrase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Rule-based prosody driven by a {@link StyleProfile}: per-mora consonant/vowel durations and gains, and a
 * pitch contour given as knots (semitones relative to the voice's recorded pitch).
 */
public final class ProsodyModel {

    /** Seed of the variation generator: the same text always sounds the same. */
    public static final long SEED = 42;

    private static final int[] PENTATONIC = {0, 2, 4, 7, 9};

    /** Timing and pitch plan for one phrase. Durations in seconds, pitch in semitones. */
    public static final class Plan {
        /** Time from consonant start to vowel onset. */
        public final double[] consonant;
        /** Time from vowel onset to the next mora's consonant start. */
        public final double[] vowel;
        public final double[] gainDb;
        /** Vibrato depth (semitones) per mora; applied to held vowels only. */
        public final double[] vibrato;
        /** Pitch knots {@code {mora, fraction of the vowel, semitones}}, in time order. */
        public final List<double[]> knots = new ArrayList<>();
        /** Pitch at the very start and end of the phrase. */
        public double startPitch;
        public double endPitch;
        public double vibratoRate;
        public double smoothing;
        public double capLow;
        public double capHigh;
        /** Strength of the beat grid (0 = natural timing) and its slot length in seconds. */
        public double rhythm;
        public double beat;
        /** Beat slots per half bar: phrases start on half-bar boundaries when rhythmic. */
        public int halfBar = 2;
        /** Time from the first vowel onset to the end of the last vowel. */
        public double span;
        /** Time from the first vowel onset to the first beat (non-zero when the phrase starts with a pickup). */
        public double firstBeat;
        /** Beat slots of rest after the phrase (when rhythmic). */
        public int restBeats;

        Plan(int n) {
            consonant = new double[n];
            vowel = new double[n];
            gainDb = new double[n];
            vibrato = new double[n];
        }

        void knot(int mora, double frac, double st) {
            knots.add(new double[]{mora, frac, st});
        }
    }

    /** Plans a phrase with a fresh variation generator (for tests and single phrases). */
    public Plan plan(Phrase phrase, VoiceParams vp) {
        return plan(phrase, vp, new Random(SEED));
    }

    /** Plans a phrase; pass one generator through all phrases of an utterance. */
    public Plan plan(Phrase phrase, VoiceParams vp, Random rnd) {
        StyleProfile s = vp.style();
        List<Mora> morae = phrase.morae;
        int n = morae.size();
        Plan plan = new Plan(n);
        boolean[] accent = chooseAccents(morae, s);
        double rate = vp.speed();
        for (int i = 0; i < n; i++) {
            Mora m = morae.get(i);
            Mora prev = i > 0 ? morae.get(i - 1) : null;
            double c = consonantDuration(m, prev);
            if (m.geminate) {
                c += m.consonant.isEmpty() ? 0.03 : 0.07;
            }
            double v = vowelDuration(m, i == n - 1);
            if (accent[i]) {
                v *= s.get("stressStretch");
            } else if (m.stress == 1 && !m.functionWord) {
                v *= 1.1;
            } else if (i < n - 1 && !m.epenthetic && !m.longVowel) {
                v *= s.get("unstressedSquash");
            }
            if (m.emphasis) {
                v *= 1.25;
            }
            if (m.glide) {
                v = Math.max(v * 2.2, 0.22);
            }
            if (phrase.sung && !m.epenthetic) {
                v *= 1.3;
            }
            if (m.devoiced) {
                // a devoiced vowel is mostly heard as a longer consonant (s -> sss, t -> aspirated release)
                c += 0.6 * v;
                v *= 0.4;
            }
            plan.consonant[i] = c / rate;
            plan.vowel[i] = v / rate;
            double g = 0;
            if (accent[i]) {
                g += s.get("accentGainDb");
            }
            if (m.emphasis) {
                g += 1.5;
            }
            if (m.epenthetic) {
                g -= 1;
            }
            if (m.devoiced) {
                g -= 9;
            }
            plan.gainDb[i] = g;
        }
        pitch(phrase, plan, accent, s, rnd);
        rhythm(phrase, plan, accent, s, rate);
        plan.vibratoRate = s.get("vibratoRate");
        plan.smoothing = s.get("smoothing");
        plan.capLow = s.get("capLow");
        plan.capHigh = s.get("capHigh");
        return plan;
    }

    /**
     * Syllable heads: morae that start a beat. Epenthetic vowels (the と of きゃっと), diphthong glides and a
     * closing ん belong to the syllable before them instead of taking a beat of their own; morae before the
     * first head (the と of "twinkle" at a phrase start) are a pickup before the first beat.
     */
    public static boolean[] beatHeads(List<Mora> morae) {
        boolean[] heads = new boolean[morae.size()];
        boolean any = false;
        for (int i = 0; i < heads.length; i++) {
            Mora m = morae.get(i);
            heads[i] = !(m.epenthetic || m.offglide || m.isNasal() && !m.wordStart);
            any |= heads[i];
        }
        if (!any && heads.length > 0) {
            heads[0] = true;
        }
        return heads;
    }

    /**
     * Pulls syllable onsets onto a beat grid. Each syllable gets a whole number of beat slots close to its
     * natural length; accented syllables are moved onto strong (even) slots by holding the previous
     * syllable longer or shorter; the last syllable is held to fill the half bar. Vowel onsets land on the
     * beat (consonants come just before, as in singing). The difference is absorbed by the vowels, mostly
     * by holding the syllable's own vowel. {@code rhythm} blends between natural and grid timing.
     */
    private static void rhythm(Phrase phrase, Plan plan, boolean[] accent, StyleProfile s, double rate) {
        int n = phrase.morae.size();
        double strength = phrase.sung ? 1 : Math.max(0, Math.min(1, s.get("rhythm")));
        double beat = s.get("beat") / rate;
        int barBeats = Math.max(2, (int) Math.round(s.get("barBeats")));
        plan.rhythm = strength;
        plan.beat = beat;
        plan.halfBar = Math.max(1, barBeats / 2);
        plan.restBeats = phrase.ending == Phrase.Ending.CONTINUATION ? 1 : 2;
        double[] onset = onsets(plan);
        plan.span = onset[n - 1] + plan.vowel[n - 1];
        if (strength <= 0) {
            return;
        }
        boolean[] head = beatHeads(phrase.morae);
        List<Integer> h = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (head[i]) {
                h.add(i);
            }
        }
        int k = h.size();
        double[] natural = new double[k];
        int[] slots = new int[k];
        int[] minSlots = new int[k];
        double floor = 0.035;
        for (int u = 0; u < k; u++) {
            double next = u + 1 < k ? onset[h.get(u + 1)] : plan.span;
            natural[u] = next - onset[h.get(u)];
            // never fewer slots than the syllable's consonants plus minimal vowels need (あんど, let's)
            int to = u + 1 < k ? h.get(u + 1) : n;
            double minimum = 0;
            for (int i = h.get(u); i < to; i++) {
                minimum += Math.min(plan.vowel[i], floor) + (i + 1 < n && (i + 1 < to || u + 1 < k) ? plan.consonant[i + 1] : 0);
            }
            int feasible = (int) Math.ceil(minimum / beat - 1e-9);
            minSlots[u] = feasible;
            slots[u] = Math.max(Math.max(1, feasible), (int) Math.round(natural[u] / beat));
            Mora m = phrase.morae.get(h.get(u));
            if (m.longVowel || m.glide) {
                slots[u] = Math.max(2, slots[u]);
            }
        }
        // accented syllables on strong beats
        int pos = 0;
        for (int u = 0; u < k; u++) {
            if (u > 0 && accent[h.get(u)] && pos % 2 == 1) {
                if (slots[u - 1] >= 2 && natural[u - 1] / beat < slots[u - 1] && slots[u - 1] - 1 >= minSlots[u - 1]) {
                    slots[u - 1]--;
                    pos--;
                } else {
                    slots[u - 1]++;
                    pos++;
                }
            }
            pos += slots[u];
        }
        // the last syllable is held to the end of the half bar
        slots[k - 1] = Math.max(2, slots[k - 1]);
        int total = 0;
        for (int sl : slots) {
            total += sl;
        }
        int pad = (plan.halfBar - total % plan.halfBar) % plan.halfBar;
        slots[k - 1] += pad;

        // target onsets, blended with the natural ones, then fit unit by unit (pickup morae keep their timing)
        plan.firstBeat = onset[h.get(0)];
        double grid = plan.firstBeat;
        double actual = plan.firstBeat;
        for (int u = 0; u < k; u++) {
            grid += slots[u] * beat;
            double naturalNext = u + 1 < k ? onset[h.get(u + 1)] : plan.span;
            double target = naturalNext + strength * (grid - naturalNext);
            int from = h.get(u);
            int to = u + 1 < k ? h.get(u + 1) : n; // exclusive
            double current = 0;
            for (int i = from; i < to; i++) {
                current += plan.vowel[i];
                if (i + 1 < to) {
                    current += plan.consonant[i + 1];
                }
            }
            if (u + 1 < k) {
                current += plan.consonant[to];
            }
            double delta = target - actual - current;
            if (delta > 0) {
                plan.vowel[from] += delta; // hold the note
            } else if (delta < 0) {
                double available = 0;
                for (int i = from; i < to; i++) {
                    available += Math.max(0, plan.vowel[i] - floor);
                }
                double take = Math.min(-delta, available);
                if (available > 0) {
                    for (int i = from; i < to; i++) {
                        plan.vowel[i] -= Math.max(0, plan.vowel[i] - floor) / available * take;
                    }
                }
                delta = -take;
            }
            actual += current + delta;
        }
        plan.span = actual;
    }

    /** Vowel onset times relative to the first vowel onset. */
    static double[] onsets(Plan plan) {
        int n = plan.vowel.length;
        double[] t = new double[n];
        for (int i = 1; i < n; i++) {
            t[i] = t[i - 1] + plan.vowel[i - 1] + plan.consonant[i];
        }
        return t;
    }

    /**
     * Picks the accented morae: heads of stressed content words, thinned out by {@code accentDensity}
     * (emphasised words, the first and the last accent are always kept; longer words win).
     */
    static boolean[] chooseAccents(List<Mora> morae, StyleProfile s) {
        int n = morae.size();
        boolean[] accent = new boolean[n];
        List<int[]> candidates = new ArrayList<>(); // {mora index, word length, forced}
        int wordStart = 0;
        for (int i = 0; i < n; i++) {
            if (morae.get(i).wordStart) {
                wordStart = i;
            }
            Mora m = morae.get(i);
            boolean head = m.accentHead && m.stress == 1 && !m.functionWord;
            boolean emphasisHead = m.emphasis && (i == 0 || !morae.get(i - 1).emphasis || m.wordStart)
                    && !hasHeadInWord(morae, i);
            if (head || emphasisHead) {
                int end = i;
                while (end + 1 < n && !morae.get(end + 1).wordStart) {
                    end++;
                }
                candidates.add(new int[]{i, end - wordStart + 1, m.emphasis ? 1 : 0});
            }
        }
        if (candidates.isEmpty()) {
            return accent;
        }
        candidates.get(0)[2] = 1;
        candidates.get(candidates.size() - 1)[2] = 1;
        int keep = (int) Math.round(s.get("accentDensity") * candidates.size());
        List<int[]> ranked = new ArrayList<>(candidates);
        Collections.sort(ranked, (a, b) -> a[2] != b[2] ? b[2] - a[2] : a[1] != b[1] ? b[1] - a[1] : a[0] - b[0]);
        for (int k = 0; k < ranked.size(); k++) {
            int[] c = ranked.get(k);
            if (c[2] == 1 || k < keep) {
                accent[c[0]] = true;
            }
        }
        return accent;
    }

    /** True if the emphasised word containing mora i already has a regular accent head. */
    private static boolean hasHeadInWord(List<Mora> morae, int i) {
        int a = i;
        while (a > 0 && !morae.get(a).wordStart) {
            a--;
        }
        for (int j = a; j < morae.size() && (j == a || !morae.get(j).wordStart); j++) {
            Mora m = morae.get(j);
            if (m.accentHead && m.stress == 1) {
                return true;
            }
        }
        return false;
    }

    static double consonantDuration(Mora m, Mora prev) {
        if (m.isNasal()) {
            return 0.03;
        }
        String c = m.consonant;
        if (c.isEmpty()) {
            return prev == null ? 0.02 : 0.035;
        }
        double d;
        switch (c) {
            case "k": case "t": case "p": d = 0.08; break;
            case "g": case "d": case "b": d = 0.065; break;
            case "s": d = 0.11; break;
            case "z": d = 0.085; break;
            case "sh": d = 0.115; break;
            case "j": d = 0.09; break;
            case "ts": case "ch": d = 0.10; break;
            case "h": d = 0.075; break;
            case "f": d = 0.09; break;
            case "v": d = 0.07; break;
            case "n": case "m": d = 0.065; break;
            case "r": d = 0.045; break;
            case "y": case "w": d = 0.055; break;
            default:
                // palatalised / labialised rows (ky, ny, kw, ty...)
                d = consonantDuration(new Mora(m.kana, c.substring(0, 1), m.vowel), prev) + 0.015;
        }
        return d;
    }

    static double vowelDuration(Mora m, boolean last) {
        double v = m.isNasal() ? 0.065 : 0.07;
        if (m.epenthetic) {
            v = 0.05;
        } else if (m.stress == 2) {
            v *= 1.1;
        } else if (m.functionWord) {
            v *= 0.85;
        }
        if (m.offglide) {
            v *= 0.7;
        }
        if (m.longVowel) {
            v *= 1.8;
        }
        if (last) {
            v *= m.epenthetic ? 1.8 : 1.5;
        } else if (m.wordEnd) {
            v *= 1.1;
        }
        return v;
    }

    private static void pitch(Phrase phrase, Plan plan, boolean[] accent, StyleProfile s, Random rnd) {
        List<Mora> morae = phrase.morae;
        int n = morae.size();
        double variation = s.get("variation");
        boolean exclamation = phrase.ending == Phrase.Ending.EXCLAMATION;
        double range = exclamation ? s.get("exclamationRange") : 1.0;
        if (phrase.excited) {
            range *= 1.2;
        }
        double offset = s.get("baseShift");
        if (phrase.ending == Phrase.Ending.WH_QUESTION) {
            offset += 1.5;
        }
        if (exclamation || phrase.excited) {
            offset += 1;
        }
        double snap = phrase.sung ? 1.0 : s.get("snap");
        double declination = s.get("declination");
        int accents = 0;
        for (boolean a : accent) {
            accents += a ? 1 : 0;
        }

        int accentIndex = 0;
        double carry = 0;
        double[] target = new double[n];
        for (int i = 0; i < n; i++) {
            Mora m = morae.get(i);
            double pos = n == 1 ? 0 : i / (double) (n - 1);
            double baseline = offset + declination * (0.5 - pos);
            int knotsBefore = plan.knots.size();
            double t;
            if (m.pitch != null) {
                t = baseline + m.pitch;
                plan.knot(i, 0.5, t);
            } else if (accent[i]) {
                double h = s.get("accentHeight") * Math.pow(s.get("downstep"), accentIndex);
                if (accentIndex % 2 == 1) {
                    h -= s.get("alternation");
                }
                if (accentIndex == accents - 1) {
                    h += s.get("finalAccentBoost");
                }
                if (m.emphasis) {
                    h += s.get("emphasisBoost");
                }
                h *= 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.5;
                h = Math.max(0.5, h) * range;
                accentIndex++;
                t = snap(baseline + h, snap);
                // rise through the accented vowel, peak late (delayed peaks sound lively)
                plan.knot(i, 0.0, baseline + 0.25 * h);
                plan.knot(i, s.get("peakDelay"), t);
                carry = h;
            } else if (m.accentHead && m.stress >= 1 && !m.functionWord) {
                double h = s.get("secondaryAccent") * range;
                t = baseline + h;
                plan.knot(i, 0.5, t);
                carry = h;
            } else {
                carry *= m.wordStart ? 0.2 : 0.45;
                double dip = s.get("dipDepth") * (1 - Math.min(1, carry / 2.0));
                t = baseline + carry - dip;
                plan.knot(i, 0.5, t);
            }
            if (phrase.sung) {
                // hold each mora on a note
                t = snap(t, 1.0);
                while (plan.knots.size() > knotsBefore) {
                    plan.knots.remove(plan.knots.size() - 1);
                }
                plan.knot(i, 0.1, t);
                plan.knot(i, 0.9, t);
                plan.vibrato[i] = s.get("vibratoDepth");
            }
            if (m.glide) {
                double g = s.get("glideHeight") * range;
                while (plan.knots.size() > knotsBefore) {
                    plan.knots.remove(plan.knots.size() - 1);
                }
                plan.knot(i, 0.0, t);
                plan.knot(i, 0.45, snap(t + g, snap));
                plan.knot(i, 1.0, t - 0.4 * g);
                plan.vibrato[i] = s.get("vibratoDepth") * 0.6;
            }
            target[i] = t;
        }

        plan.startPitch = plan.knots.get(0)[2] - 0.5;
        int last = n - 1;
        double lastT = target[last];
        boolean glideEnding = morae.get(last).glide;
        double vary = 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.5;
        switch (phrase.ending) {
            case QUESTION:
                if (!glideEnding) {
                    replaceLastKnots(plan, last, lastT + s.get("questionRise") * 0.35);
                }
                plan.endPitch = snap(lastT + s.get("questionRise") * vary * range, snap);
                break;
            case CONTINUATION:
                plan.endPitch = snap(lastT + s.get("continuationRise") * vary, snap);
                break;
            default: {
                double fall = s.get("statementFall") * vary * (exclamation ? range : 1);
                if (glideEnding) {
                    plan.endPitch = lastT - 0.5 * fall;
                    break;
                }
                if (n > 1 && !accent[last]) {
                    lastT = Math.min(lastT, target[last - 1] - 1);
                    replaceLastKnots(plan, last, lastT);
                }
                double low = lastT - fall;
                double bounce = s.get("bounceEnding");
                if (bounce > 0) {
                    // fall early in the last vowel, then a small lift: cheerful instead of final
                    plan.knot(last, accent[last] ? 0.95 : 0.6, low);
                    plan.endPitch = snap(low + 2.0 * bounce, snap);
                } else {
                    plan.endPitch = low;
                }
                break;
            }
        }
    }

    /** Replaces the knots of the last mora by a single target (keeps an accent rise). */
    private static void replaceLastKnots(Plan plan, int last, double st) {
        double[] k = plan.knots.get(plan.knots.size() - 1);
        if ((int) k[0] == last) {
            k[2] = st;
        }
    }

    /** Pulls a pitch towards the nearest note of the major pentatonic scale on the base pitch. */
    public static double snap(double st, double amount) {
        if (amount <= 0) {
            return st;
        }
        double best = st;
        double bestDist = Double.MAX_VALUE;
        int octave = (int) Math.floor(st / 12.0);
        for (int o = octave - 1; o <= octave + 1; o++) {
            for (int d : PENTATONIC) {
                double note = o * 12 + d;
                double dist = Math.abs(note - st);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = note;
                }
            }
        }
        return st + (best - st) * Math.min(1, amount);
    }
}
