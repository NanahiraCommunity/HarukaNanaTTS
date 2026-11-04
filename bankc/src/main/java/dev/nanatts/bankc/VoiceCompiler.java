package dev.nanatts.bankc;

import dev.nanatts.bank.MelGrid;
import dev.nanatts.bank.OtoEntry;
import dev.nanatts.bank.OtoIni;
import dev.nanatts.bank.Segment;
import dev.nanatts.bank.Unit;
import dev.nanatts.bank.VoicePack;
import dev.nanatts.io.AudioClip;
import dev.nanatts.io.WavIO;
import dev.nanatts.world.Aperiodicity;
import dev.nanatts.world.CheapTrick;
import dev.nanatts.world.F0Estimator;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Compiles an UTAU voicebank directory (oto.ini + wav files) into a {@link VoicePack}.
 *
 * <p>Usage: {@code VoiceCompiler <voicebankDir> <out.vpk> [melBins] [maxHz]}
 */
public final class VoiceCompiler {

    static final double FRAME_PERIOD = 0.005;
    static final double F0_FLOOR = 80;
    static final double F0_CEIL = 1200;

    private final int melBins;
    private final double maxHz;

    public VoiceCompiler(int melBins, double maxHz) {
        this.melBins = melBins;
        this.maxHz = maxHz;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VoiceCompiler <voicebankDir> <out.vpk> [melBins=80] [maxHz=12000]");
            System.exit(2);
        }
        int mel = args.length > 2 ? Integer.parseInt(args[2]) : 80;
        double maxHz = args.length > 3 ? Double.parseDouble(args[3]) : 12000;
        long t0 = System.nanoTime();
        VoicePack pack = new VoiceCompiler(mel, maxHz).compile(new File(args[0]));
        File out = new File(args[1]);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (OutputStream os = new FileOutputStream(out)) {
            pack.write(os);
        }
        System.out.printf("wrote %s: %d segments, %d units, median f0 %.1f Hz, %.1f MB, %.1f s%n",
                out, pack.segmentCount(), pack.unitCount(), pack.medianF0(), out.length() / 1e6,
                (System.nanoTime() - t0) / 1e9);
    }

    /** Intermediate per-file analysis result. */
    private static final class Analysed {
        String file;
        double regionStart;
        double[] f0;
        float[][] env;
        float[][] ap;
        List<OtoEntry> entries;
        double fileMedianF0;
    }

    public VoicePack compile(File dir) throws Exception {
        List<OtoEntry> oto;
        try (InputStream in = new FileInputStream(new File(dir, "oto.ini"))) {
            oto = OtoIni.parse(in);
        }
        Map<String, List<OtoEntry>> byFile = new LinkedHashMap<>();
        for (OtoEntry e : oto) {
            byFile.computeIfAbsent(e.file(), k -> new ArrayList<>()).add(e);
        }

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Analysed>> futures = new ArrayList<>();
        for (Map.Entry<String, List<OtoEntry>> e : byFile.entrySet()) {
            File wav = new File(dir, e.getKey());
            if (!wav.isFile()) {
                System.err.println("warning: missing " + wav.getName() + ", skipping its aliases");
                continue;
            }
            futures.add(pool.submit(() -> analyse(wav, e.getValue())));
        }
        List<Analysed> results = new ArrayList<>();
        for (Future<Analysed> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        normaliseLoudness(results);

        List<Double> medians = new ArrayList<>();
        for (Analysed a : results) {
            if (a.fileMedianF0 > 0) {
                medians.add(a.fileMedianF0);
            }
        }
        medians.sort(null);
        double medianF0 = medians.isEmpty() ? 0 : medians.get(medians.size() / 2);

        List<Segment> segments = new ArrayList<>();
        List<Unit> units = new ArrayList<>();
        for (Analysed a : results) {
            int segIndex = segments.size();
            segments.add(Segment.encode(a.file, a.regionStart, a.f0, a.env, a.ap));
            for (OtoEntry e : a.entries) {
                Unit u = toUnit(e, a, segIndex);
                if (u != null) {
                    units.add(u);
                }
            }
        }

        Map<String, String> meta = readCharacterTxt(dir);
        meta.put("source", "UTAU voicebank compiled by nana-tts bankc");
        MelGrid grid = new MelGrid(melBins, maxHz);
        return new VoicePack(meta, FRAME_PERIOD, grid, Aperiodicity.DEFAULT_BANDS, medianF0, segments, units);
    }

    private Analysed analyse(File wav, List<OtoEntry> entries) throws IOException {
        AudioClip clip;
        try (InputStream in = new FileInputStream(wav)) {
            clip = WavIO.read(in);
        }
        float[] x = clip.samples();
        int fs = clip.sampleRate();
        double lengthMs = x.length * 1000.0 / fs;

        double from = Double.MAX_VALUE;
        double to = 0;
        for (OtoEntry e : entries) {
            from = Math.min(from, e.offset());
            to = Math.max(to, e.endMs(lengthMs));
        }
        from = Math.max(0, from - 50) / 1000.0;
        to = Math.min(lengthMs, to + 50) / 1000.0;

        F0Estimator.Result f0r = new F0Estimator(F0_FLOOR, F0_CEIL).medianSpread(1.6).estimate(x, fs, FRAME_PERIOD);
        double fileMedian = F0Estimator.median(f0r.f0);
        double fallbackF0 = fileMedian > 0 ? fileMedian : 400;

        int first = (int) Math.floor(from / FRAME_PERIOD);
        int last = Math.min(f0r.f0.length - 1, (int) Math.ceil(to / FRAME_PERIOD));
        int frames = last - first + 1;

        CheapTrick ct = new CheapTrick(fs, F0_FLOOR);
        Aperiodicity apEst = new Aperiodicity(fs, Aperiodicity.DEFAULT_BANDS, -60);
        MelGrid grid = new MelGrid(melBins, maxHz);
        double[] power = new double[ct.fftSize() / 2 + 1];
        double[] apBand = new double[Aperiodicity.DEFAULT_BANDS.length];

        Analysed a = new Analysed();
        a.file = wav.getName();
        a.entries = entries;
        a.regionStart = first * FRAME_PERIOD;
        a.fileMedianF0 = fileMedian;
        a.f0 = new double[frames];
        a.env = new float[frames][melBins];
        a.ap = new float[frames][apBand.length];
        for (int i = 0; i < frames; i++) {
            int fi = first + i;
            double f0 = f0r.f0[fi];
            a.f0[i] = f0;
            ct.estimate(x, fi * FRAME_PERIOD, f0 > 0 ? f0 : fallbackF0, power);
            // store power spectral density (power per Hz, one-sided) so packs are sample-rate independent
            grid.fromLinear(power, ct.fftSize(), fs, 2.0 / fs, a.env[i]);
        }
        boolean[] repaired = repairVoicing(a, grid, fallbackF0);
        for (int i = 0; i < frames; i++) {
            apEst.estimate(x, (first + i) * FRAME_PERIOD, a.f0[i], apBand);
            for (int b = 0; b < apBand.length; b++) {
                a.ap[i][b] = (float) (10 * Math.log10(apBand[b]));
            }
        }
        capRepairedAperiodicity(a, repaired);
        smoothAperiodicity(a.ap);
        return a;
    }

    /**
     * YIN rejects irregular phonation (nasal transitions, glottalised onsets). Such frames would be
     * resynthesised as loud noise, so frames that are loud and dominated by low-frequency energy are
     * re-labelled voiced (sonorant: most energy below 3 kHz), with f0 interpolated from their voiced neighbours.
     */
    static boolean[] repairVoicing(Analysed a, MelGrid grid, double fallbackF0) {
        int n = a.f0.length;
        boolean[] fix = new boolean[n];
        List<Double> voicedLevels = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (a.f0[i] > 0) {
                voicedLevels.add(meanDb(a.env[i]));
            }
        }
        if (voicedLevels.isEmpty()) {
            return fix;
        }
        voicedLevels.sort(null);
        double ref = voicedLevels.get(voicedLevels.size() / 2);
        for (int i = 0; i < n; i++) {
            if (a.f0[i] > 0) {
                continue;
            }
            double low = 0;
            double total = 0;
            for (int b = 0; b < grid.size(); b++) {
                double lo = b == 0 ? 0 : (grid.hz(b - 1) + grid.hz(b)) / 2;
                double hi = b == grid.size() - 1 ? grid.hz(b) : (grid.hz(b) + grid.hz(b + 1)) / 2;
                double e = Math.pow(10, a.env[i][b] / 10.0) * (hi - lo);
                total += e;
                if (grid.hz(b) < 3000) {
                    low += e;
                }
            }
            double level = meanDb(a.env[i]);
            fix[i] = level > ref - 22 && low / total > 0.6;
        }
        for (int i = 0; i < n; i++) {
            if (!fix[i]) {
                continue;
            }
            int l = i - 1;
            while (l >= 0 && a.f0[l] <= 0) {
                l--;
            }
            int r = i + 1;
            while (r < n && a.f0[r] <= 0) {
                r++;
            }
            double f;
            if (l >= 0 && r < n) {
                f = a.f0[l] + (a.f0[r] - a.f0[l]) * (i - l) / (double) (r - l);
            } else if (l >= 0) {
                f = a.f0[l];
            } else if (r < n) {
                f = a.f0[r];
            } else {
                f = fallbackF0;
            }
            a.f0[i] = -f; // mark, so interpolation above only uses original voiced frames
        }
        for (int i = 0; i < n; i++) {
            a.f0[i] = Math.abs(a.f0[i]);
        }
        return fix;
    }

    /** Repaired frames are irregular, so their measured aperiodicity is near 0 dB; use typical voiced values. */
    private static void capRepairedAperiodicity(Analysed a, boolean[] repaired) {
        int bands = a.ap[0].length;
        float[] median = new float[bands];
        for (int b = 0; b < bands; b++) {
            List<Float> v = new ArrayList<>();
            for (int i = 0; i < a.f0.length; i++) {
                if (a.f0[i] > 0 && !repaired[i]) {
                    v.add(a.ap[i][b]);
                }
            }
            v.sort(null);
            median[b] = v.isEmpty() ? -20f : v.get(v.size() / 2);
        }
        for (int i = 0; i < a.f0.length; i++) {
            if (repaired[i]) {
                for (int b = 0; b < bands; b++) {
                    a.ap[i][b] = Math.min(a.ap[i][b], median[b] + 6f);
                }
            }
        }
    }

    /** 3-frame median over time per band to suppress estimator noise. */
    private static void smoothAperiodicity(float[][] ap) {
        if (ap.length < 3) {
            return;
        }
        int bands = ap[0].length;
        float[][] copy = new float[ap.length][];
        for (int i = 0; i < ap.length; i++) {
            copy[i] = ap[i].clone();
        }
        for (int i = 1; i < ap.length - 1; i++) {
            for (int b = 0; b < bands; b++) {
                float x = copy[i - 1][b];
                float y = copy[i][b];
                float z = copy[i + 1][b];
                ap[i][b] = Math.max(Math.min(x, y), Math.min(Math.max(x, y), z));
            }
        }
    }

    /** Equalises recording level across files (partially, to keep natural vowel differences). */
    private static void normaliseLoudness(List<Analysed> results) {
        double[] levels = new double[results.size()];
        for (int r = 0; r < results.size(); r++) {
            Analysed a = results.get(r);
            List<Double> l = new ArrayList<>();
            for (int i = 0; i < a.f0.length; i++) {
                if (a.f0[i] > 0) {
                    l.add(meanDb(a.env[i]));
                }
            }
            l.sort(null);
            levels[r] = l.isEmpty() ? Double.NaN : l.get(l.size() / 2);
        }
        double[] valid = Arrays.stream(levels).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (valid.length == 0) {
            return;
        }
        double target = valid[valid.length / 2];
        for (int r = 0; r < results.size(); r++) {
            if (Double.isNaN(levels[r])) {
                continue;
            }
            float gain = (float) Math.max(-6, Math.min(6, target - levels[r]));
            for (float[] frame : results.get(r).env) {
                for (int b = 0; b < frame.length; b++) {
                    frame[b] += gain;
                }
            }
        }
    }

    private static double meanDb(float[] db) {
        double s = 0;
        for (float v : db) {
            s += Math.pow(10, v / 10.0);
        }
        return 10 * Math.log10(s / db.length + 1e-300);
    }

    private Unit toUnit(OtoEntry e, Analysed a, int segIndex) {
        String alias = e.alias().trim();
        int sp = alias.indexOf(' ');
        if (sp != 1) {
            return null; // only "x KANA" style VCV/CV aliases are used
        }
        char prev = alias.charAt(0);
        String kana = alias.substring(2);
        double base = a.regionStart;
        double start = e.offset() / 1000.0 - base;
        double overlap = start + Math.max(0, e.overlap()) / 1000.0;
        double onset = start + e.preutterance() / 1000.0;
        double fixedEnd = start + e.consonant() / 1000.0;
        double fileMs = (a.regionStart + a.f0.length * FRAME_PERIOD) * 1000;
        double end = Math.min(a.f0.length * FRAME_PERIOD, e.endMs(fileMs) / 1000.0 - base);
        double cs = detectConsonantStart(a, prev, kana, start, overlap, onset);
        return new Unit(alias, prev, kana, segIndex, start, overlap, cs, onset, Math.max(fixedEnd, onset), end);
    }

    /**
     * Finds where the consonant begins: scanning backwards from the vowel onset, the first frame that is
     * spectrally close to the preceding context (previous vowel tail, or silence for '-' units).
     */
    static double detectConsonantStart(Analysed a, char prev, String kana, double start, double overlap,
                                       double onset) {
        if (isPureVowel(kana) && prev != '-') {
            return Math.max(overlap, onset - 0.04);
        }
        int onsetFrame = frame(onset);
        int refFrom;
        int refTo;
        if (prev == '-') {
            refFrom = frame(start);
            refTo = Math.min(onsetFrame - 4, refFrom + 4);
        } else {
            refFrom = Math.max(frame(start), frame(overlap) - 6);
            refTo = frame(overlap);
        }
        if (refTo < refFrom || refFrom < 0) {
            return Math.max(overlap, onset - 0.08);
        }
        int bins = a.env[0].length;
        float[] ref = new float[bins];
        for (int i = refFrom; i <= refTo; i++) {
            for (int b = 0; b < bins; b++) {
                ref[b] += a.env[Math.min(a.env.length - 1, i)][b] / (refTo - refFrom + 1);
            }
        }
        double threshold = prev == '-' ? 10.0 : 6.0;
        int lower = Math.max(refTo, frame(overlap));
        int i = onsetFrame - 3;
        while (i > lower) {
            if (distance(a.env[Math.min(a.env.length - 1, i)], ref) < threshold) {
                break;
            }
            i--;
        }
        double cs = (i + 1) * FRAME_PERIOD;
        return Math.max(overlap, Math.min(onset - 0.015, cs));
    }

    private static int frame(double t) {
        return (int) Math.round(t / FRAME_PERIOD);
    }

    private static double distance(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            double d = Math.max(a[i], -150) - Math.max(b[i], -150);
            s += d * d;
        }
        return Math.sqrt(s / a.length);
    }

    private static boolean isPureVowel(String kana) {
        return kana.equals("あ") || kana.equals("い") || kana.equals("う") || kana.equals("え") || kana.equals("お");
    }

    private static Map<String, String> readCharacterTxt(File dir) {
        Map<String, String> meta = new LinkedHashMap<>();
        File f = new File(dir, "character.txt");
        if (!f.isFile()) {
            return meta;
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), Charset.forName("windows-31j"));
            StringBuilder extra = new StringBuilder();
            for (String line : text.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    meta.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                } else if (!line.isBlank()) {
                    extra.append(line.trim()).append('\n');
                }
            }
            if (extra.length() > 0) {
                meta.put("notes", extra.toString().trim());
            }
        } catch (IOException e) {
            System.err.println("warning: cannot read character.txt: " + e.getMessage());
        }
        meta.remove("image");
        return meta;
    }
}
