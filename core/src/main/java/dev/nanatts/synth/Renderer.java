package dev.nanatts.synth;

import dev.nanatts.VoiceParams;
import dev.nanatts.bank.Segment;
import dev.nanatts.bank.Unit;
import dev.nanatts.bank.VoicePack;
import dev.nanatts.prosody.ProsodyModel;
import dev.nanatts.text.Mora;
import dev.nanatts.text.Phrase;
import java.util.List;
import java.util.Random;

/**
 * Concatenates VCV units in the vocoder-parameter domain: selects a unit per mora, time-maps its consonant
 * and vowel regions onto the planned timing, cross-fades neighbouring units inside their shared vowel and
 * imposes the planned f0 contour.
 */
public final class Renderer {

    private static final double LEAD = 0.04;
    private static final double TAIL = 0.07;
    private static final double MAX_XFADE = 0.025;
    /** Release / consonant-to-vowel transition kept at natural speed. */
    private static final double KEEP_BEFORE_ONSET = 0.04;
    /** Vowel-to-consonant decay kept at natural speed (compressing it causes audible clicks). */
    private static final double KEEP_AFTER_START = 0.03;
    private static final double GAIN_SMOOTHING = 0.02;

    private final VoicePack pack;
    private final int melBins;
    private final int bands;
    private final double fp;

    public Renderer(VoicePack pack) {
        this.pack = pack;
        this.melBins = pack.grid().size();
        this.bands = pack.apBands().length;
        this.fp = pack.framePeriod();
    }

    /** A unit placed on the output timeline. */
    private static final class Placed {
        Unit unit;
        Segment seg;
        double[] outT;
        double[] srcT;
        double from;
        double to;
        double fadeIn;
        int mora;
    }

    /** Time of the phrase's first beat (first syllable's vowel onset after any pickup) in its rendered audio. */
    public static double firstBeat(ProsodyModel.Plan plan) {
        return LEAD + plan.consonant[0] + plan.firstBeat;
    }

    /** Picks the unit for mora {@code i} given its left context. */
    public Unit select(List<Mora> morae, int i) {
        Mora m = morae.get(i);
        char prev = i == 0 ? '-' : morae.get(i - 1).vowel;
        Unit u = first(prev + " " + m.kana);
        if (u == null) {
            u = first("- " + m.kana);
        }
        if (u == null) {
            u = first(prev + " " + vowelKana(m.vowel));
        }
        if (u == null) {
            u = first("- " + vowelKana(m.vowel));
        }
        if (u == null) {
            throw new IllegalStateException("voice pack has no unit for " + m.kana);
        }
        return u;
    }

    private Unit first(String alias) {
        List<Unit> l = pack.units(alias);
        return l.isEmpty() ? null : l.get(0);
    }

    private static boolean isPureVowel(String kana) {
        return kana.length() == 1 && "あいうえお".indexOf(kana.charAt(0)) >= 0;
    }

    private static String vowelKana(char v) {
        switch (v) {
            case 'a': return "あ";
            case 'i': return "い";
            case 'u': return "う";
            case 'e': return "え";
            case 'o': return "お";
            default: return "ん";
        }
    }

    public ParamTrack render(Phrase phrase, ProsodyModel.Plan plan, VoiceParams vp, double baseF0, long seed) {
        List<Mora> morae = phrase.morae;
        int n = morae.size();
        double[] onset = new double[n];
        double t = LEAD + plan.consonant[0];
        for (int i = 0; i < n; i++) {
            onset[i] = t;
            if (i + 1 < n) {
                t += plan.vowel[i] + plan.consonant[i + 1];
            }
        }
        double tail = TAIL / vp.speed();
        double end = onset[n - 1] + plan.vowel[n - 1] + tail;

        Placed[] placed = new Placed[n];
        for (int i = 0; i < n; i++) {
            placed[i] = place(morae, i, onset[i], plan, i == n - 1 ? tail : 0);
        }

        int frames = (int) Math.ceil(end / fp) + 1;
        ParamTrack track = new ParamTrack(frames, melBins, bands);
        float[] envA = new float[melBins];
        float[] envB = new float[melBins];
        float[] apA = new float[bands];
        float[] apB = new float[bands];
        double[] semitones = contour(plan, onset, end, frames, vp, seed);
        double breath = vp.breathiness();
        double volumeDb = 20 * Math.log10(Math.max(1e-6, vp.volume()));

        double[] gainDb = new double[frames];
        int first = 0;
        for (int f = 0; f < frames; f++) {
            double time = f * fp;
            float[] env = track.envDb[f];
            float[] ap = track.apDb[f];
            java.util.Arrays.fill(env, 0);
            java.util.Arrays.fill(ap, 0);
            double wsum = 0;
            double voiced = 0;
            int owner = -1;
            double ownerW = -1;
            while (first < n && placed[first].to < time) {
                first++;
            }
            for (int i = Math.max(0, first - 1); i < n && placed[i].from <= time; i++) {
                Placed p = placed[i];
                if (time > p.to) {
                    continue;
                }
                double w = 1;
                if (p.fadeIn > 0) {
                    w *= clamp01((time - p.from) / p.fadeIn);
                }
                if (i + 1 < n) {
                    Placed q = placed[i + 1];
                    if (q.fadeIn > 0 && time >= q.from) {
                        w *= 1 - clamp01((time - q.from) / q.fadeIn);
                    }
                }
                if (w <= 0) {
                    continue;
                }
                double src = interp(p.outT, p.srcT, time) / fp;
                int s0 = (int) Math.floor(src);
                float frac = (float) (src - s0);
                p.seg.envDb(s0, envA);
                p.seg.envDb(s0 + 1, envB);
                p.seg.apDb(s0, apA);
                p.seg.apDb(s0 + 1, apB);
                for (int b = 0; b < melBins; b++) {
                    env[b] += (float) (w * (envA[b] + (envB[b] - envA[b]) * frac));
                }
                for (int b = 0; b < bands; b++) {
                    ap[b] += (float) (w * (apA[b] + (apB[b] - apA[b]) * frac));
                }
                boolean v = p.seg.f0((int) Math.round(src)) > 0;
                voiced += v ? w : 0;
                wsum += w;
                if (w > ownerW) {
                    ownerW = w;
                    owner = p.mora;
                }
            }
            if (wsum <= 0) {
                java.util.Arrays.fill(env, -200f);
                java.util.Arrays.fill(ap, 0f);
                track.f0[f] = 0;
                gainDb[f] = Double.NaN;
                continue;
            }
            // which mora's vowel are we in? (decides devoicing and gain)
            int mora = owner;
            while (mora + 1 < n && time >= onset[mora + 1]) {
                mora++;
            }
            while (mora > 0 && time < onset[mora] - plan.consonant[mora]) {
                mora--;
            }
            Mora m = morae.get(mora);
            boolean inVowel = time >= onset[mora];
            double gain = plan.gainDb[mora];
            if (!inVowel && m.devoiced) {
                gain = 0; // consonant of a devoiced mora keeps full level
            }
            // a lone unit fading in (phrase start) must actually fade: the missing weight is silence
            gainDb[f] = gain + 20 * Math.log10(Math.max(1e-3, Math.min(1, wsum)));
            for (int b = 0; b < melBins; b++) {
                env[b] = (float) (env[b] / wsum);
            }
            boolean devoice = m.devoiced && inVowel;
            for (int b = 0; b < bands; b++) {
                double a = ap[b] / wsum;
                if (breath > 0) {
                    a = a * (1 - breath);
                }
                ap[b] = (float) (devoice ? 0 : a);
            }
            boolean isVoiced = voiced / wsum >= 0.5 && !devoice;
            track.f0[f] = isVoiced ? baseF0 * Math.pow(2, semitones[f] / 12.0) : 0;
        }
        cleanVoicing(track, baseF0, semitones);
        softenWeakVoicing(track, apBandsHz());
        applyGain(track, gainDb, end, tail, volumeDb);
        return track;
    }

    /**
     * Voicing comes from the source frames, which flip occasionally (aspiration after a release, irregular
     * phonation, cross-fades). A short noise burst inside a loud vowel is heard as a crackle, so short loud
     * unvoiced gaps are bridged (aperiodicity interpolated from the neighbours) and 1-2 frame voiced islands
     * are removed.
     */
    private void cleanVoicing(ParamTrack track, double baseF0, double[] semitones) {
        int frames = track.frames();
        double[] level = new double[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            for (float v : track.envDb[f]) {
                s += Math.pow(10, v / 10.0);
            }
            level[f] = 10 * Math.log10(s / melBins + 1e-30);
        }
        int maxGap = (int) Math.round(0.025 / fp);
        int f = 0;
        while (f < frames) {
            if (track.f0[f] > 0) {
                f++;
                continue;
            }
            int g = f;
            while (g < frames && track.f0[g] <= 0) {
                g++;
            }
            // unvoiced run [f, g)
            if (f > 0 && g < frames && g - f <= maxGap) {
                double edge = Math.min(level[f - 1], level[g]);
                boolean loud = true;
                for (int j = f; j < g; j++) {
                    loud &= level[j] > edge - 12;
                }
                if (loud) {
                    float[] apL = track.apDb[f - 1];
                    float[] apR = track.apDb[g];
                    for (int j = f; j < g; j++) {
                        double t = (j - f + 1) / (double) (g - f + 1);
                        track.f0[j] = baseF0 * Math.pow(2, semitones[j] / 12.0);
                        for (int b = 0; b < bands; b++) {
                            track.apDb[j][b] = (float) (apL[b] + (apR[b] - apL[b]) * t);
                        }
                    }
                }
            }
            f = g;
        }
        f = 0;
        while (f < frames) {
            if (track.f0[f] <= 0) {
                f++;
                continue;
            }
            int g = f;
            while (g < frames && track.f0[g] > 0) {
                g++;
            }
            if (g - f <= 2 && f > 0 && g < frames) {
                for (int j = f; j < g; j++) {
                    track.f0[j] = 0;
                    java.util.Arrays.fill(track.apDb[j], 0f);
                }
            }
            f = g;
        }
    }

    private double[] apBandsHz() {
        return pack.apBands();
    }

    /**
     * Voiced frames far below the surrounding vowel level (decays into closures, weak onsets) have a flat
     * spectrum; rendered as pulses they sound like a train of ticks. Real speech has only a low voice bar
     * there, so the band aperiodicity above 1 kHz is raised the quieter the frame is.
     */
    private void softenWeakVoicing(ParamTrack track, double[] bandHz) {
        int frames = track.frames();
        double[] level = new double[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            for (float v : track.envDb[f]) {
                s += Math.pow(10, v / 10.0);
            }
            level[f] = 10 * Math.log10(s / melBins + 1e-30);
        }
        int half = (int) Math.round(0.1 / fp);
        for (int f = 0; f < frames; f++) {
            if (track.f0[f] <= 0) {
                continue;
            }
            double ref = -1e9;
            for (int j = Math.max(0, f - half); j <= Math.min(frames - 1, f + half); j++) {
                ref = Math.max(ref, level[j]);
            }
            double t = clamp01((ref - level[f] - 10) / 12.0);
            if (t <= 0) {
                continue;
            }
            float[] ap = track.apDb[f];
            for (int b = 0; b < bands; b++) {
                if (bandHz[b] > 1000) {
                    ap[b] = (float) (ap[b] * (1 - t));
                }
            }
        }
    }

    /** Applies per-mora gains smoothed over time (steps in level are heard as clicks), plus the final fade. */
    private void applyGain(ParamTrack track, double[] gainDb, double end, double tail, double volumeDb) {
        int frames = gainDb.length;
        int half = Math.max(1, (int) Math.round(GAIN_SMOOTHING / fp));
        for (int f = 0; f < frames; f++) {
            if (Double.isNaN(gainDb[f])) {
                continue;
            }
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, f - half); j <= Math.min(frames - 1, f + half); j++) {
                if (!Double.isNaN(gainDb[j])) {
                    sum += gainDb[j];
                    count++;
                }
            }
            double fadeOut = clamp01((end - f * fp) / tail);
            double g = sum / count + volumeDb + 20 * Math.log10(Math.max(1e-3, fadeOut));
            float[] env = track.envDb[f];
            for (int b = 0; b < melBins; b++) {
                env[b] += (float) g;
            }
        }
    }

    private Placed place(List<Mora> morae, int i, double onsetOut, ProsodyModel.Plan plan, double extra) {
        Unit u = select(morae, i);
        Placed p = new Placed();
        p.unit = u;
        p.seg = pack.segment(u.segment());
        p.mora = i;
        double c = plan.consonant[i];
        double v = plan.vowel[i] + extra;
        double xf = i == 0 ? 0.03 : Math.min(MAX_XFADE, 0.6 * plan.vowel[i - 1]);
        p.fadeIn = xf;

        double cs = u.consonantStart();
        double on = u.onset();
        if (u.prev() != '-' && isPureVowel(u.kana())) {
            // sung vowel-to-vowel transitions often contain a breathy re-attack; skip it and let the
            // parameter cross-fade form the glide instead
            int from = (int) Math.floor(cs / fp);
            int to = (int) Math.ceil((on + 0.03) / fp);
            int lastUnvoiced = -1;
            for (int f = from; f <= to; f++) {
                if (p.seg.f0(f) <= 0) {
                    lastUnvoiced = f;
                }
            }
            if (lastUnvoiced >= 0) {
                on = (lastUnvoiced + 2) * fp;
                cs = on;
            }
        }
        // consonant region: the decay out of the previous vowel and the release into the next vowel keep
        // their natural speed; only the middle (closure / steady frication) is compressed or stretched
        double srcCons = Math.max(0, on - cs);
        double keepS = Math.min(srcCons, KEEP_BEFORE_ONSET);
        double headS = Math.min(srcCons - keepS, KEEP_AFTER_START);
        double fit = Math.min(1, 0.85 * c / Math.max(1e-9, headS + keepS));
        double keepO = keepS * fit;
        double headO = headS * fit;
        double usableEnd = u.end() - 0.03;

        double[] outT = new double[8];
        double[] srcT = new double[8];
        int k = 0;
        outT[k] = onsetOut - c - xf;
        srcT[k++] = cs - xf;
        outT[k] = onsetOut - c;
        srcT[k++] = cs;
        if (headS > 0.005) {
            outT[k] = onsetOut - c + headO;
            srcT[k++] = cs + headS;
        }
        if (keepS > 0.005) {
            outT[k] = onsetOut - keepO;
            srcT[k++] = on - keepS;
        }
        outT[k] = onsetOut;
        srcT[k++] = on;
        if (on + v <= usableEnd) {
            outT[k] = onsetOut + v;
            srcT[k++] = on + v;
        } else {
            double steady = Math.min(0.04, v);
            outT[k] = onsetOut + steady;
            srcT[k++] = on + steady;
            outT[k] = onsetOut + v;
            srcT[k++] = Math.max(on + steady, usableEnd);
        }
        p.outT = java.util.Arrays.copyOf(outT, k);
        p.srcT = java.util.Arrays.copyOf(srcT, k);
        p.from = p.outT[0];
        p.to = p.outT[k - 1];
        return p;
    }

    /**
     * Frame-wise pitch in semitones: linear between the plan's knots, smoothed, plus slow random drift,
     * vibrato on held vowels and a soft limit to the style's pitch range.
     */
    private double[] contour(ProsodyModel.Plan plan, double[] onset, double end, int frames, VoiceParams vp,
                             long seed) {
        int n = onset.length;
        int k = plan.knots.size();
        double[][] pts = new double[k + 2][];
        pts[0] = new double[]{0, plan.startPitch};
        for (int i = 0; i < k; i++) {
            double[] kn = plan.knots.get(i);
            int m = (int) kn[0];
            pts[i + 1] = new double[]{onset[m] + kn[1] * plan.vowel[m], kn[2]};
        }
        pts[k + 1] = new double[]{Math.max(end, pts[k][0] + 1e-3), plan.endPitch};
        java.util.Arrays.sort(pts, 1, k + 1, (a, b) -> Double.compare(a[0], b[0]));
        double[] kt = new double[k + 2];
        double[] kv = new double[k + 2];
        for (int i = 0; i < k + 2; i++) {
            kt[i] = pts[i][0];
            kv[i] = pts[i][1];
        }
        double[] raw = new double[frames];
        for (int f = 0; f < frames; f++) {
            raw[f] = interp(kt, kv, f * fp);
        }
        int half = Math.max(1, (int) Math.round(plan.smoothing / 2 / fp));
        double[] out = new double[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            int c = 0;
            for (int j = f - half; j <= f + half; j++) {
                s += raw[Math.max(0, Math.min(frames - 1, j))];
                c++;
            }
            out[f] = s / c * vp.range();
        }
        if (vp.liveliness() > 0) {
            Random rnd = new Random(seed);
            double drift = 0;
            double vel = 0;
            for (int f = 0; f < frames; f++) {
                vel = 0.9 * vel + 0.02 * rnd.nextGaussian();
                drift = 0.97 * drift + vel;
                out[f] += Math.max(-0.6, Math.min(0.6, drift)) * vp.liveliness();
            }
        }
        // vibrato fades in on vowels held long enough to notice it
        int m = 0;
        for (int f = 0; f < frames; f++) {
            double t = f * fp;
            while (m + 1 < n && t >= onset[m + 1]) {
                m++;
            }
            double depth = plan.vibrato[m];
            double into = t - onset[m];
            if (depth > 0 && into >= 0 && into <= plan.vowel[m] && plan.vowel[m] > 0.12) {
                double ramp = clamp01((into - 0.06) / 0.1);
                out[f] += depth * ramp * Math.sin(2 * Math.PI * plan.vibratoRate * into);
            }
        }
        for (int f = 0; f < frames; f++) {
            out[f] = softLimit(out[f], plan.capLow, plan.capHigh);
        }
        return out;
    }

    /** Linear inside the range, approaching the limits smoothly over the last 2 semitones. */
    static double softLimit(double st, double lo, double hi) {
        double knee = Math.min(2, (hi - lo) / 4);
        if (st > hi - knee) {
            return hi - knee + knee * Math.tanh((st - (hi - knee)) / knee);
        }
        if (st < lo + knee) {
            return lo + knee - knee * Math.tanh(((lo + knee) - st) / knee);
        }
        return st;
    }

    static double interp(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        int last = xs.length - 1;
        if (x >= xs[last]) {
            return ys[last];
        }
        int i = 0;
        while (i < last - 1 && xs[i + 1] <= x) {
            i++;
        }
        double span = xs[i + 1] - xs[i];
        if (span <= 0) {
            return ys[i + 1];
        }
        return ys[i] + (ys[i + 1] - ys[i]) * (x - xs[i]) / span;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
