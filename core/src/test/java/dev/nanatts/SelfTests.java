package dev.nanatts;

import dev.nanatts.bank.MelGrid;
import dev.nanatts.bank.OtoEntry;
import dev.nanatts.bank.OtoIni;
import dev.nanatts.bank.VoicePack;
import dev.nanatts.dsp.FFT;
import dev.nanatts.g2p.ArpabetToKana;
import dev.nanatts.g2p.CmuDict;
import dev.nanatts.g2p.EnglishFrontend;
import dev.nanatts.g2p.EnglishG2P;
import dev.nanatts.g2p.EnglishNormalizer;
import dev.nanatts.io.AudioClip;
import dev.nanatts.io.ResourceSource;
import dev.nanatts.io.WavIO;
import dev.nanatts.text.KanaFrontend;
import dev.nanatts.text.KanaTable;
import dev.nanatts.text.Mora;
import dev.nanatts.text.Phrase;
import dev.nanatts.prosody.ProsodyModel;
import dev.nanatts.prosody.StyleProfile;
import dev.nanatts.synth.ParamTrack;
import dev.nanatts.synth.Renderer;
import dev.nanatts.world.Aperiodicity;
import dev.nanatts.world.CheapTrick;
import dev.nanatts.world.F0Estimator;
import dev.nanatts.world.WorldSynthesizer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Dependency-free test runner (the build must work offline, so no JUnit). Run with {@code gradle check}.
 */
public final class SelfTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private static final File DATA = new File(System.getProperty("nanatts.dataDir", "data"));
    private static final File PACK = new File(System.getProperty("nanatts.pack", "bankc/build/voice/nana.vpk"));
    private static final File OUT = new File(System.getProperty("nanatts.outDir", "build/selftest"));

    private static CmuDict dict;
    private static Set<String> otoAliases;

    interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        dict = CmuDict.load(ResourceSource.classpath());
        run("fft round trip", SelfTests::fftRoundTrip);
        run("wav write/read", SelfTests::wavRoundTrip);
        run("world analysis/synthesis round trip", SelfTests::worldRoundTrip);
        run("oto.ini parsing", SelfTests::otoParsing);
        run("kana table covered by voicebank", SelfTests::kanaCoverage);
        run("kana frontend", SelfTests::kanaFrontend);
        run("cmudict lookup", SelfTests::cmuDict);
        run("g2p fallbacks", SelfTests::g2pFallbacks);
        run("text normalisation", SelfTests::normalisation);
        run("arpabet to kana", SelfTests::arpabetToKana);
        run("all dictionary words map to bank kana", SelfTests::dictionaryCoverage);
        run("voice pack round trip", SelfTests::packRoundTrip);
        run("english synthesis", SelfTests::englishSynthesis);
        run("no pops: parameter continuity", SelfTests::parameterContinuity);
        run("japanese kana synthesis", SelfTests::japaneseSynthesis);
        run("style profiles", SelfTests::styleProfiles);
        run("expressive text cues", SelfTests::textCues);
        run("kana inside english text", SelfTests::kanaInEnglish);
        run("playful style moves more than neutral", SelfTests::playfulVsNeutral);
        run("pitch stays inside the style's cap", SelfTests::pitchCap);
        run("questions rise, statements fall", SelfTests::endings);
        run("rhythm: syllables and phrases on the beat grid", SelfTests::rhythmGrid);
        run("android-safe bytecode", SelfTests::androidSafety);
        System.out.printf("%n%d passed, %d failed%n", passed, failures.size());
        for (String f : failures) {
            System.out.println("  FAIL " + f);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** Optional substring filter on test names ({@code gradle :core:selfTest -Ponly=kana}). */
    private static final String ONLY = System.getProperty("nanatts.only", "");

    private static void run(String name, Test t) {
        if (!ONLY.isEmpty() && !name.contains(ONLY)) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            t.run();
            passed++;
            System.out.printf("ok   %-45s %6.0f ms%n", name, (System.nanoTime() - t0) / 1e6);
        } catch (Throwable e) {
            failures.add(name + ": " + e);
            System.out.printf("FAIL %-45s %s%n", name, e);
            if (!(e instanceof AssertionError)) {
                e.printStackTrace(System.out);
            }
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ---------------------------------------------------------------- DSP / IO

    private static void fftRoundTrip() {
        FFT fft = new FFT(256);
        Random r = new Random(1);
        double[] re = new double[256];
        double[] im = new double[256];
        double[] orig = new double[256];
        for (int i = 0; i < 256; i++) {
            orig[i] = re[i] = r.nextGaussian();
        }
        fft.forward(re, im);
        // Parseval
        double et = 0;
        double ef = 0;
        for (int i = 0; i < 256; i++) {
            et += orig[i] * orig[i];
            ef += re[i] * re[i] + im[i] * im[i];
        }
        check(Math.abs(ef / 256 - et) < 1e-9 * et, "Parseval");
        fft.inverse(re, im);
        for (int i = 0; i < 256; i++) {
            check(Math.abs(re[i] - orig[i]) < 1e-12 && Math.abs(im[i]) < 1e-12, "inverse mismatch at " + i);
        }
    }

    private static void wavRoundTrip() throws IOException {
        float[] x = new float[1000];
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) Math.sin(i * 0.05) * 0.5f;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        WavIO.write(new AudioClip(x, 22050), bos);
        AudioClip back = WavIO.read(new ByteArrayInputStream(bos.toByteArray()));
        eq(22050, back.sampleRate());
        eq(1000, back.samples().length);
        for (int i = 0; i < x.length; i++) {
            check(Math.abs(back.samples()[i] - x[i]) < 1e-4, "sample " + i);
        }
    }

    /** Analyse a voicebank recording, resynthesise it at 24 kHz and compare level and envelope. */
    private static void worldRoundTrip() throws IOException {
        AudioClip clip;
        try (InputStream in = new FileInputStream(new File(DATA, "_ああいあう.wav"))) {
            clip = WavIO.read(in);
        }
        float[] x = clip.samples();
        int fs = clip.sampleRate();
        double fp = 0.005;
        double[] f0 = new F0Estimator(80, 1200).medianSpread(1.6).estimate(x, fs, fp).f0;
        double median = F0Estimator.median(f0);
        check(median > 250 && median < 500, "median f0 " + median);
        CheapTrick ct = new CheapTrick(fs, 80);
        Aperiodicity apEst = new Aperiodicity(fs, Aperiodicity.DEFAULT_BANDS, -60);
        MelGrid grid = new MelGrid(80, 12000);
        ParamTrack track = new ParamTrack(f0.length, grid.size(), Aperiodicity.DEFAULT_BANDS.length);
        double[] power = new double[ct.fftSize() / 2 + 1];
        double[] ap = new double[Aperiodicity.DEFAULT_BANDS.length];
        for (int i = 0; i < f0.length; i++) {
            track.f0()[i] = f0[i];
            ct.estimate(x, i * fp, f0[i] > 0 ? f0[i] : median, power);
            grid.fromLinear(power, ct.fftSize(), fs, 2.0 / fs, track.envDb(i));
            apEst.estimate(x, i * fp, f0[i], ap);
            for (int b = 0; b < ap.length; b++) {
                track.apDb(i)[b] = (float) (10 * Math.log10(ap[b]));
            }
        }
        int outFs = 24000;
        float[] y = new WorldSynthesizer(outFs, fp, 1024)
                .synthesize(track.asFrames(grid, Aperiodicity.DEFAULT_BANDS, outFs, 1024), 1);
        double levelDb = 20 * Math.log10(rms(y) / rms(x));
        check(Math.abs(levelDb) < 3, "level differs by " + levelDb + " dB");
        CheapTrick ct2 = new CheapTrick(outFs, 80);
        double[] p2 = new double[ct2.fftSize() / 2 + 1];
        float[] e2 = new float[grid.size()];
        double dist = 0;
        int n = 0;
        for (int i = 0; i < f0.length; i++) {
            if (f0[i] <= 0) {
                continue;
            }
            ct2.estimate(y, i * fp, f0[i], p2);
            grid.fromLinear(p2, ct2.fftSize(), outFs, 2.0 / outFs, e2);
            double s = 0;
            for (int b = 0; b < grid.size(); b++) {
                double d = e2[b] - track.envDb(i)[b];
                s += d * d;
            }
            dist += Math.sqrt(s / grid.size());
            n++;
        }
        check(dist / n < 4, "log-spectral distance " + dist / n + " dB");
    }

    private static double rms(float[] v) {
        double s = 0;
        for (float f : v) {
            s += f * f;
        }
        return Math.sqrt(s / v.length);
    }

    // ---------------------------------------------------------------- voicebank & kana

    private static Set<String> otoAliases() throws IOException {
        if (otoAliases == null) {
            otoAliases = new HashSet<>();
            try (InputStream in = new FileInputStream(new File(DATA, "oto.ini"))) {
                for (OtoEntry e : OtoIni.parse(in)) {
                    otoAliases.add(e.alias());
                }
            }
        }
        return otoAliases;
    }

    private static void otoParsing() throws IOException {
        List<OtoEntry> entries;
        try (InputStream in = new FileInputStream(new File(DATA, "oto.ini"))) {
            entries = OtoIni.parse(in);
        }
        eq(1270, entries.size());
        OtoEntry first = entries.get(0);
        eq("_ああいあう.wav", first.file());
        eq("- あ", first.alias());
        check(Math.abs(first.offset() - 701.023) < 1e-9, "offset");
        check(otoAliases().contains("a か") && otoAliases().contains("n ん") && otoAliases().contains("o ヴぁ"),
                "expected aliases missing");
    }

    private static void kanaCoverage() throws IOException {
        Set<String> aliases = otoAliases();
        String rows = "k g s z sh j t d ts ch n h f b p m y r w v ky gy ny hy by py my ry kw gw";
        List<String> missing = new ArrayList<>();
        List<String> cons = new ArrayList<>(Arrays.asList(rows.split(" ")));
        cons.add("");
        for (String c : cons) {
            for (char v : "aiueo".toCharArray()) {
                if (!KanaTable.exists(c, v)) {
                    continue;
                }
                String kana = KanaTable.mora(c, v).kana;
                for (String prev : new String[]{"-", "a", "i", "u", "e", "o", "n"}) {
                    if (!aliases.contains(prev + " " + kana)) {
                        missing.add(prev + " " + kana);
                    }
                }
            }
        }
        check(missing.isEmpty(), "aliases missing from voicebank: " + missing);
    }

    private static void kanaFrontend() {
        List<Phrase> p = new KanaFrontend().analyze("コンニチハ、ティーです！");
        eq(2, p.size());
        eq("[こ, ん, に, ち, は]", p.get(0).morae.toString());
        eq(Phrase.Ending.CONTINUATION, p.get(0).ending);
        eq("てぃ", p.get(1).morae.get(0).kana);
        check(p.get(1).morae.get(0).longVowel, "ー lengthens the previous mora");
        eq(Phrase.Ending.EXCLAMATION, p.get(1).ending);
        List<Phrase> g = new KanaFrontend().analyze("きって");
        check(g.get(0).morae.get(1).geminate, "っ marks the next mora");
    }

    // ---------------------------------------------------------------- English frontend

    private static void cmuDict() {
        check(dict.size() > 120000, "dictionary size " + dict.size());
        eq("[HH, AH0, L, OW1]", Arrays.toString(dict.lookup("hello")));
        eq("[HH, AH0, L, OW1]", Arrays.toString(dict.lookup("Hello")));
        eq("[D, OW1, N, T]", Arrays.toString(dict.lookup("don't")));
        check(dict.lookup("a") != null && dict.lookup("zygote") != null, "first/last entries");
        check(dict.lookup("qwxzv") == null, "unknown word");
    }

    private static void g2pFallbacks() {
        EnglishG2P g = new EnglishG2P(dict);
        // spelled acronym
        eq("[EY1, B, IY1, S, IY1]", Arrays.toString(g.phones("#ABC")));
        // dictionary word in caps is read as a word
        eq(Arrays.toString(dict.lookup("hello")), Arrays.toString(g.phones("#HELLO")));
        // morphology: unknown derived forms use the stem
        String[] p = g.phones("blorpingly");
        check(p.length > 0, "letter-to-sound fallback produced nothing");
        String[] compound = g.phones("catdogs");
        eq("[K, AE1, T, D, AO2, G, Z]", Arrays.toString(compound));
        for (String w : new String[]{"zorbitron", "kawaii", "nanahira", "xyzzy", "brrr"}) {
            check(g.phones(w).length > 0, "no phones for " + w);
        }
    }

    private static void normalisation() {
        EnglishNormalizer n = new EnglishNormalizer();
        eq("[it costs three dollars and fifty cents [STATEMENT]]", n.normalize("It costs $3.50.").toString());
        eq("[the twenty first of march nineteen eighty four [STATEMENT]]",
                n.normalize("The 21st of March 1984").toString());
        eq("[doctor smith lives on baker street [STATEMENT]]", n.normalize("Dr. Smith lives on Baker St.").toString());
        eq(Phrase.Ending.WH_QUESTION, n.normalize("What is it?").get(0).ending);
        eq(Phrase.Ending.QUESTION, n.normalize("Is it?").get(0).ending);
        eq("[hello [CONTINUATION], world [EXCLAMATION]]", n.normalize("Hello, world!").toString());
        eq("[fifty percent of one thousand two hundred fifty point five [STATEMENT]]",
                n.normalize("50% of 1,250.5").toString());
        eq("[at three oh five you and me [STATEMENT]]", n.normalize("at 3:05 you & me").toString());
        eq("[minus five degrees in a cafe [STATEMENT]]", n.normalize("-5 degrees in a café").toString());
        eq("[well known [STATEMENT]]", n.normalize("well-known").toString());
    }

    private static String kana(String... phones) {
        StringBuilder sb = new StringBuilder();
        for (Mora m : ArpabetToKana.convert(phones)) {
            sb.append(m.kana);
        }
        return sb.toString();
    }

    private static void arpabetToKana() {
        eq("はろう", kana(dict.lookup("hello")));
        eq("きゃと", kana(dict.lookup("cat")));
        eq("すとりと", kana(dict.lookup("street")));
        eq("きゅと", kana(dict.lookup("cute")));
        List<Mora> cat = ArpabetToKana.convert(dict.lookup("cat"));
        check(cat.get(1).geminate && !cat.get(1).devoiced && cat.get(1).epenthetic, "cat: held, voiced と ending");
        check(cat.get(0).accentHead && cat.get(0).stress == 1, "cat: stressed head");
        List<Mora> car = ArpabetToKana.convert(dict.lookup("car"));
        eq(1, car.size());
        check(car.get(0).longVowel, "non-rhotic r lengthens the vowel");
        eq("うぃん", kana(dict.lookup("win")));
        eq("くぃく", kana(dict.lookup("quick")));
    }

    /** Every mora produced for a large sample of dictionary words must exist in the voicebank. */
    private static void dictionaryCoverage() throws IOException {
        Set<String> aliases = otoAliases();
        Set<String> bad = new HashSet<>();
        EnglishFrontend fe = new EnglishFrontend(dict);
        String[] words = ("the quick brown fox jumps over the lazy dog while seven thoughtful zebras vanish "
                + "through rhythm and texture of judge measure vision usual beautiful music young yesterday "
                + "queue anxious twelfth strengths world girl squirrel heard february library thousand "
                + "photograph language pleasure chocolate computer synthesizer television february").split(" ");
        for (String w : words) {
            for (Phrase p : fe.analyze(w)) {
                for (Mora m : p.morae) {
                    if (!aliases.contains("- " + m.kana)) {
                        bad.add(w + ":" + m.kana);
                    }
                }
            }
        }
        // unusual phone sequences and letter-to-sound output
        EnglishG2P g = fe.g2p();
        String[] sample = {"a", "about", "zoo", "rhythms", "xylophone", "ngo", "tsunami", "pneumonia"};
        for (String w : sample) {
            for (Mora m : ArpabetToKana.convert(g.phones(w))) {
                if (!aliases.contains("- " + m.kana)) {
                    bad.add(w + ":" + m.kana);
                }
            }
        }
        check(bad.isEmpty(), "kana not in voicebank: " + bad);
    }

    // ---------------------------------------------------------------- synthesis (needs the compiled pack)

    private static VoicePack pack;

    private static VoicePack pack() throws IOException {
        if (pack == null) {
            check(PACK.isFile(), "voice pack missing (run gradle :bankc:compileVoice): " + PACK);
            try (InputStream in = new FileInputStream(PACK)) {
                pack = VoicePack.read(in);
            }
        }
        return pack;
    }

    private static void packRoundTrip() throws IOException {
        VoicePack p = pack();
        eq(1270, p.unitCount());
        check(p.medianF0() > 200 && p.medianF0() < 600, "median f0 " + p.medianF0());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        p.write(bos);
        VoicePack q = VoicePack.read(new ByteArrayInputStream(bos.toByteArray()));
        eq(p.unitCount(), q.unitCount());
        eq(p.units("a か").get(0), q.units("a か").get(0));
        float[] a = new float[p.grid().size()];
        float[] b = new float[q.grid().size()];
        p.segment(10).envDb(20, a);
        q.segment(10).envDb(20, b);
        check(Arrays.equals(a, b), "decoded envelopes differ");
        eq(p.metadata(), q.metadata());
    }

    private static AudioClip checkedSpeech(Synthesizer tts, String text, String file) throws IOException {
        AudioClip c = tts.speak(text);
        float[] x = c.samples();
        double sum = 0;
        float peak = 0;
        for (float v : x) {
            check(!Float.isNaN(v) && !Float.isInfinite(v), "non-finite sample");
            sum += v * v;
            peak = Math.max(peak, Math.abs(v));
        }
        double rms = Math.sqrt(sum / x.length);
        check(rms > 0.01, "too quiet: rms " + rms);
        check(peak <= 1.0f, "clipping: " + peak);
        if (OUT.isDirectory() || OUT.mkdirs()) {
            try (OutputStream os = new FileOutputStream(new File(OUT, file))) {
                WavIO.write(c, os);
            }
        }
        return c;
    }

    private static void englishSynthesis() throws IOException {
        Synthesizer tts = Synthesizer.builder(pack()).language(Language.ENGLISH).build();
        String text = "Hello world! This is a test of the speech synthesizer. Can you understand me?";
        AudioClip c = checkedSpeech(tts, text, "english.wav");
        check(c.durationSeconds() > 3 && c.durationSeconds() < 10, "duration " + c.durationSeconds());
        // streaming yields one clip per phrase and the same audio
        List<AudioClip> parts = new ArrayList<>();
        tts.speak(text, VoiceParams.defaults(), parts::add);
        eq(3, parts.size());
        AudioClip joined = AudioClip.concat(parts, tts.sampleRate());
        check(Arrays.equals(joined.samples(), c.samples()), "streamed audio differs from batch");
        // faster speech is shorter, pitch changes keep the duration
        AudioClip fast = tts.speak(text, VoiceParams.defaults().speed(1.5));
        check(fast.durationSeconds() < c.durationSeconds() * 0.8, "speed has no effect");
        AudioClip high = tts.speak(text, VoiceParams.defaults().pitch(1.2));
        check(Math.abs(high.durationSeconds() - c.durationSeconds()) < 0.05, "pitch changed duration");
        // deterministic
        check(Arrays.equals(tts.speak(text).samples(), c.samples()), "output is not deterministic");
        // no pops: no sudden loud onsets in the audio (phrase starts used to jump ~60 dB in 1 ms)
        int jumps = suddenOnsets(c);
        check(jumps <= 2, jumps + " sudden loud onsets (pops)");
    }

    /**
     * Pops come from discontinuities in the rendered parameters: loud level steps between frames, or
     * voicing flipping on and off inside loud sounds (noise bursts in vowels). Both are checked on the
     * parameter track, which, unlike click counting on audio, does not depend on the pitch.
     */
    private static void parameterContinuity() throws IOException {
        VoicePack p = pack();
        String text = "Hello world! This is a test of the speech synthesizer. Can you understand me? "
                + "I really like chocolate cake, and *so* do you~ Let's sing ♪ Wow!!";
        for (StyleProfile style : new StyleProfile[]{StyleProfile.PLAYFUL, StyleProfile.NEUTRAL,
                StyleProfile.PLAYFUL.with("rhythm", 1).with("snap", 0.8)}) {
            VoiceParams vp = VoiceParams.defaults().style(style);
            Random rnd = new Random(ProsodyModel.SEED);
            for (Phrase ph : new EnglishFrontend(dict).analyze(text)) {
                ParamTrack t = new Renderer(p).render(ph, new ProsodyModel().plan(ph, vp, rnd), vp, p.medianF0(), 1);
                double[] level = new double[t.frames()];
                double max = -1e9;
                for (int f = 0; f < t.frames(); f++) {
                    double s = 0;
                    for (float v : t.envDb(f)) {
                        s += Math.pow(10, v / 10.0);
                    }
                    level[f] = 10 * Math.log10(s / t.envDb(f).length + 1e-30);
                    max = Math.max(max, level[f]);
                }
                for (int f = 1; f < t.frames(); f++) {
                    // consonant-vowel transitions in the recordings reach ~8 dB per frame; pops were 10-60 dB
                    boolean loud = level[f] > max - 12 && level[f - 1] > max - 12;
                    check(!loud || Math.abs(level[f] - level[f - 1]) < 10,
                            "level step of " + (level[f] - level[f - 1]) + " dB in " + ph + " at frame " + f);
                    boolean flip = (t.f0()[f] > 0) != (t.f0()[f - 1] > 0);
                    check(!(loud && flip && level[f] > max - 10), "voicing flips inside a loud sound in " + ph
                            + " at frame " + f);
                }
            }
        }
    }

    /** Places where the 1 ms peak level jumps by more than 20 dB to a loud level. */
    private static int suddenOnsets(AudioClip c) {
        float[] x = c.samples();
        int w = c.sampleRate() / 1000;
        int n = x.length / w;
        double[] pk = new double[n];
        for (int i = 0; i < n; i++) {
            float m = 0;
            for (int j = i * w; j < (i + 1) * w; j++) {
                m = Math.max(m, Math.abs(x[j]));
            }
            pk[i] = 20 * Math.log10(m + 1e-9);
        }
        int count = 0;
        for (int i = 4; i < n; i++) {
            double prev = Math.max(Math.max(pk[i - 1], pk[i - 2]), Math.max(pk[i - 3], pk[i - 4]));
            if (pk[i] > -25 && pk[i] - prev > 20) {
                count++;
            }
        }
        return count;
    }

    private static void japaneseSynthesis() throws IOException {
        Synthesizer tts = Synthesizer.builder(pack()).language(Language.JAPANESE_KANA).build();
        AudioClip c = checkedSpeech(tts, "こんにちは、はるかななです。", "japanese.wav");
        check(c.durationSeconds() > 1 && c.durationSeconds() < 4, "duration " + c.durationSeconds());
    }

    // ---------------------------------------------------------------- styles & expressive cues

    private static void styleProfiles() throws IOException {
        StyleProfile p = StyleProfile.PLAYFUL;
        eq(0.3, p.get("snap"));
        StyleProfile q = p.with("snap", 0.9);
        eq(0.9, q.get("snap"));
        eq(0.3, p.get("snap")); // immutable
        try {
            p.with("nope", 1);
            throw new AssertionError("unknown knob accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        StyleProfile loaded = StyleProfile.NEUTRAL.load(new ByteArrayInputStream(
                "# comment\naccentHeight = 6.5\nsnap=0.5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        eq(6.5, loaded.get("accentHeight"));
        eq(0.5, loaded.get("snap"));
        eq(StyleProfile.NEUTRAL.get("declination"), loaded.get("declination"));
        // toString is a loadable properties file
        StyleProfile round = StyleProfile.NEUTRAL.load(new ByteArrayInputStream(
                q.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        eq(q, round);
        eq(StyleProfile.PLAYFUL, VoiceParams.defaults().style());
        // pentatonic snapping on the base pitch: 0 2 4 7 9
        check(Math.abs(ProsodyModel.snap(6.6, 1) - 7) < 1e-9, "snap to 7");
        check(Math.abs(ProsodyModel.snap(6.6, 0.5) - 6.8) < 1e-9, "half snap");
        check(Math.abs(ProsodyModel.snap(-2.0, 1) + 3) < 1e-9, "snap below base to -3 (a 9 of the octave below)");
        eq(5.5, ProsodyModel.snap(5.5, 0));
    }

    private static void textCues() {
        EnglishNormalizer n = new EnglishNormalizer();
        List<EnglishNormalizer.Chunk> c = n.normalize("This is *really* cute~ ♪");
        eq(1, c.size());
        eq("[this, is, really, cute]", c.get(0).words.toString());
        eq("[2]", c.get(0).emphasized.toString());
        eq("[3]", c.get(0).glides.toString());
        check(c.get(0).sung, "♪ marks the phrase as sung");
        List<EnglishNormalizer.Chunk> w = n.normalize("Wow!! Hi!~ *Two words* here");
        eq(3, w.size());
        check(w.get(0).excited && w.get(0).ending == Phrase.Ending.EXCLAMATION, "!! is excited");
        check(!w.get(1).excited && w.get(1).glides.contains(0), "~ after ! glides the closed phrase");
        eq("[0, 1]", w.get(2).emphasized.toString());
        List<Phrase> p = new EnglishFrontend(dict).analyze("so *cute*~");
        Mora last = p.get(0).morae.get(p.get(0).morae.size() - 1);
        check(last.epenthetic && !last.glide, "glide is not put on the epenthetic と");
        boolean glided = false;
        for (Mora m : p.get(0).morae) {
            glided |= m.glide;
            check(m.kana.indexOf('*') < 0 && m.kana.indexOf('~') < 0, "cue spoken: " + m.kana);
        }
        check(glided, "no mora glides");
        List<Phrase> ja = new KanaFrontend().analyze("*すごい*ね〜。やった！！たのしいー");
        check(ja.get(0).morae.get(0).emphasis && last(ja.get(0)).glide, "kana emphasis and 〜 glide");
        check(ja.get(1).excited && ja.get(1).ending == Phrase.Ending.EXCLAMATION, "！！ excited");
        check(last(ja.get(2)).glide && last(ja.get(2)).longVowel, "phrase-final ー glides");
    }

    private static String kanaOf(Phrase p) {
        StringBuilder sb = new StringBuilder();
        for (Mora m : p.morae) {
            if (m.wordStart && sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(m.kana).append(m.longVowel ? "ー" : "");
        }
        return sb.toString();
    }

    private static void kanaInEnglish() throws IOException {
        EnglishFrontend fe = new EnglishFrontend(dict);
        List<Phrase> p = fe.analyze("I love すし and ラーメン!");
        eq(1, p.size());
        check(kanaOf(p.get(0)).endsWith("すし あんど らーめん"), kanaOf(p.get(0)));
        // dakuten survive normalisation, っ marks the next mora
        List<Mora> gakkou = fe.analyze("がっこう").get(0).morae;
        eq("が", gakkou.get(0).kana);
        check(gakkou.get(1).geminate, "っ");
        // half-width katakana, Japanese punctuation, kana glued to latin letters, cues
        List<Phrase> q = fe.analyze("ﾗｰﾒﾝ、Nanaちゃん。*すごい*~");
        eq(3, q.size());
        eq("らーめん", kanaOf(q.get(0)));
        eq(Phrase.Ending.CONTINUATION, q.get(0).ending);
        eq("なな ちゃ", kanaOf(q.get(1)).substring(0, 5));
        check(q.get(2).morae.get(0).emphasis && last(q.get(2)).glide, "cues on kana words");
        // a kana word is accented like a content word
        check(q.get(0).morae.get(0).accentHead && q.get(0).morae.get(0).stress == 1, "kana word accent");
        // and it is actually spoken
        Synthesizer tts = Synthesizer.builder(pack()).build();
        double withKana = tts.speak("I love すし and ラーメン!").durationSeconds();
        double without = tts.speak("I love and!").durationSeconds();
        check(withKana > without + 0.4, "kana words are not spoken");
        eq("らーめん", kanaOf(new KanaFrontend().analyze("ﾗｰﾒﾝ").get(0))); // half-width in the kana frontend too
    }

    private static Mora last(Phrase p) {
        return p.morae.get(p.morae.size() - 1);
    }

    /** Pitch in semitones (relative to the voice's base) of the voiced frames of each phrase of a text. */
    private static List<double[]> pitchTracks(String text, VoiceParams vp) throws IOException {
        VoicePack p = pack();
        List<double[]> out = new ArrayList<>();
        Random rnd = new Random(ProsodyModel.SEED);
        for (Phrase ph : new EnglishFrontend(dict).analyze(text)) {
            ProsodyModel.Plan plan = new ProsodyModel().plan(ph, vp, rnd);
            double[] f0 = new Renderer(p).render(ph, plan, vp, p.medianF0(), 1).f0();
            out.add(Arrays.stream(f0).filter(x -> x > 0)
                    .map(x -> 12 * Math.log(x / p.medianF0()) / Math.log(2)).toArray());
        }
        return out;
    }

    private static double sd(double[] v) {
        double m = Arrays.stream(v).average().orElse(0);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / Math.max(1, v.length));
    }

    private static void playfulVsNeutral() throws IOException {
        String text = "I really like chocolate cake. We went to the beautiful park yesterday afternoon.";
        double neutral = 0;
        double playful = 0;
        for (double[] t : pitchTracks(text, VoiceParams.defaults().style(StyleProfile.NEUTRAL))) {
            neutral += sd(t);
        }
        for (double[] t : pitchTracks(text, VoiceParams.defaults())) {
            playful += sd(t);
        }
        check(playful > neutral * 1.3, "playful spread " + playful + " vs neutral " + neutral);
    }

    private static void pitchCap() throws IOException {
        StyleProfile extreme = StyleProfile.PLAYFUL.with("accentHeight", 25).with("questionRise", 30)
                .with("vibratoDepth", 3).with("dipDepth", 20);
        VoiceParams vp = VoiceParams.defaults().style(extreme).range(2);
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double[] t : pitchTracks("Is this *amazing*? Wow!! Sing along~ ♪", vp)) {
            for (double x : t) {
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
        }
        check(hi <= extreme.get("capHigh") + 1e-6 && lo >= extreme.get("capLow") - 1e-6,
                "pitch " + lo + ".." + hi + " escapes the cap");
        check(hi > 7, "cap not approached: " + hi);
    }

    private static void endings() throws IOException {
        for (StyleProfile s : new StyleProfile[]{StyleProfile.PLAYFUL, StyleProfile.NEUTRAL}) {
            double[] q = pitchTracks("Do you want to come with me?", VoiceParams.defaults().style(s)).get(0);
            double[] st = pitchTracks("I want to come with you.", VoiceParams.defaults().style(s)).get(0);
            check(tailMean(q) > Arrays.stream(q).average().orElse(0) + 2, "question does not rise");
            check(tailMean(st) < Arrays.stream(st).average().orElse(0) - 1, "statement does not fall");
        }
    }

    private static void rhythmGrid() throws IOException {
        String text = "Twinkle twinkle little star, how I wonder what you are. Street lights! "
                + "We need apples and a little bit of sugar.";
        EnglishFrontend fe = new EnglishFrontend(dict);
        List<Phrase> phrases = fe.analyze(text);
        VoiceParams strict = VoiceParams.defaults().style(StyleProfile.PLAYFUL.with("rhythm", 1));
        Random rnd = new Random(ProsodyModel.SEED);
        List<ProsodyModel.Plan> plans = new ArrayList<>();
        for (Phrase ph : phrases) {
            ProsodyModel.Plan plan = new ProsodyModel().plan(ph, strict, rnd);
            plans.add(plan);
            boolean[] heads = ProsodyModel.beatHeads(ph.morae);
            double t = 0;
            for (int i = 0; i < ph.morae.size(); i++) {
                if (i > 0) {
                    t += plan.vowel[i - 1] + plan.consonant[i];
                }
                if (heads[i]) {
                    double slot = (t - plan.firstBeat) / plan.beat;
                    check(Math.abs(slot - Math.round(slot)) < 1e-6, "syllable off the grid in " + ph + ": " + slot);
                }
            }
            check(!heads[0] == (plan.firstBeat > 0), "pickup handling in " + ph);
        }
        // phrases start on half-bar boundaries in the actual audio
        List<AudioClip> clips = new ArrayList<>();
        Synthesizer tts = Synthesizer.builder(pack()).build();
        tts.speak(phrases, strict, clips::add);
        double start = 0;
        double first = Renderer.firstBeat(plans.get(0));
        for (int i = 0; i < clips.size(); i++) {
            double halfBars = (start + Renderer.firstBeat(plans.get(i)) - first) / (plans.get(i).halfBar * plans.get(i).beat);
            check(Math.abs(halfBars - Math.round(halfBars)) < 0.01, "phrase " + i + " starts off the beat: " + halfBars);
            start += clips.get(i).durationSeconds();
        }
        // rhythm 0 leaves natural timing alone; ♪ is always rhythmic
        Phrase ph = phrases.get(0);
        ProsodyModel.Plan natural = new ProsodyModel().plan(ph, VoiceParams.defaults());
        ProsodyModel.Plan zero = new ProsodyModel().plan(ph, VoiceParams.defaults().style(StyleProfile.PLAYFUL.with("rhythm", 0)));
        check(Arrays.equals(natural.vowel, zero.vowel) && natural.rhythm == 0, "rhythm 0 changes timing");
        Phrase sung = fe.analyze("let us sing ♪").get(0);
        eq(1.0, new ProsodyModel().plan(sung, VoiceParams.defaults()).rhythm);
    }

    /** Mean of the last 50 ms of voicing. */
    private static double tailMean(double[] v) {
        return Arrays.stream(v, Math.max(0, v.length - 10), v.length).average().orElse(0);
    }

    // ---------------------------------------------------------------- Android compatibility

    private static final String[] FORBIDDEN_PACKAGES = {"java/awt/", "javax/sound/", "javax/swing/", "java/nio/file/",
            "java/lang/invoke/StringConcatFactory"};
    /** Methods missing on older Android API levels (owner.name). */
    private static final Set<String> FORBIDDEN_METHODS = new HashSet<>(Arrays.asList(
            "java/util/List.of", "java/util/List.copyOf", "java/util/Set.of", "java/util/Set.copyOf",
            "java/util/Map.of", "java/util/Map.ofEntries", "java/util/Map.entry", "java/util/Map.copyOf",
            "java/lang/String.isBlank", "java/lang/String.strip", "java/lang/String.repeat",
            "java/lang/String.lines", "java/util/stream/Stream.toList", "java/io/InputStream.readAllBytes",
            "java/io/InputStream.transferTo", "java/util/Optional.isEmpty"));

    private static void androidSafety() throws IOException {
        String dirs = System.getProperty("nanatts.classesDir", "core/build/classes/java/main");
        List<String> problems = new ArrayList<>();
        int classes = 0;
        for (String d : dirs.split(File.pathSeparator)) {
            List<File> files = new ArrayList<>();
            collect(new File(d), files);
            for (File f : files) {
                classes++;
                scanClass(f, problems);
            }
        }
        check(classes > 20, "no classes found in " + dirs);
        check(problems.isEmpty(), "Android-incompatible references: " + problems);
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".class")) {
                out.add(f);
            }
        }
    }

    /** Minimal class-file constant pool parser: checks the bytecode version and referenced APIs. */
    private static void scanClass(File f, List<String> problems) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            check(in.readInt() == 0xCAFEBABE, "bad class file " + f);
            in.readUnsignedShort();
            int major = in.readUnsignedShort();
            if (major != 61) {
                problems.add(f.getName() + " has class version " + major + " (want 61 = Java 17)");
            }
            int count = in.readUnsignedShort();
            String[] utf = new String[count];
            int[][] refs = new int[count][];
            int[] classNameIdx = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: utf[i] = in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: classNameIdx[i] = in.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 9: case 10: case 11: refs[i] = new int[]{tag, in.readUnsignedShort(), in.readUnsignedShort()}; break;
                    case 12: refs[i] = new int[]{tag, in.readUnsignedShort(), in.readUnsignedShort()}; break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    case 17: case 18: in.readUnsignedShort(); in.readUnsignedShort(); break;
                    default: throw new IOException("unknown constant tag " + tag + " in " + f);
                }
            }
            for (int i = 1; i < count; i++) {
                if (classNameIdx[i] != 0) {
                    String name = utf[classNameIdx[i]];
                    for (String p : FORBIDDEN_PACKAGES) {
                        if (name.startsWith(p)) {
                            problems.add(f.getName() + " -> " + name);
                        }
                    }
                }
                if (refs[i] != null && refs[i][0] != 12) {
                    String owner = utf[classNameIdx[refs[i][1]]];
                    String member = utf[refs[i][2] == 0 ? 0 : refs[refs[i][2]][1]];
                    if (FORBIDDEN_METHODS.contains(owner + "." + member)) {
                        problems.add(f.getName() + " -> " + owner + "." + member);
                    }
                }
            }
        }
    }
}
