package dev.nanatts.cli;

import dev.nanatts.Language;
import dev.nanatts.Synthesizer;
import dev.nanatts.VoiceParams;
import dev.nanatts.bank.VoicePack;
import dev.nanatts.io.AudioClip;
import dev.nanatts.io.WavIO;
import dev.nanatts.prosody.StyleProfile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the same test sentences in several style variants for listening comparisons.
 * Usage: {@code Audition <outDir>} (voice pack from {@code -Dnanatts.pack}).
 */
public final class Audition {

    static final String[] ENGLISH = {
            "I really like ななひら. Like a lot! Like a whole lot!!",
            "Take the second exit in the roundabout.",
            "Happy birthday あずにゃん ♪",
            "Good morning! I made pancakes for breakfast today.",
            "Do you want to play a game with me?",
            "Where did you put my favorite hat?",
            "We need apples, bananas, strawberries, and a little bit of sugar.",
            "This is *really* important, okay?",
            "Thank you so much~",
            "Let's sing a happy song together ♪",
            "Wow, I can't believe we actually won!!",
    };
    static final String JAPANESE = "おはよう！きょうも がんばろうね〜";

    /** Variants named by how they sound, so they can be compared without knowing the knobs. */
    static Map<String, StyleProfile> variants() {
        Map<String, StyleProfile> v = new LinkedHashMap<>();
        StyleProfile p = StyleProfile.PLAYFUL;
        v.put("A-calm", StyleProfile.NEUTRAL);
        v.put("B-playful", p);
        v.put("C-fewer-bigger-accents", p.with("accentDensity", 0.5).with("accentHeight", 6.5)
                .with("stressStretch", 1.55));
        v.put("D-singsong", p.with("snap", 1).with("vibratoDepth", 0.45).with("smoothing", 0.08)
                .with("rhythm", 1));
        v.put("E-sparse-singsong", p.with("accentDensity", 0.5).with("accentHeight", 6.5).with("snap", 0.8)
                .with("vibratoDepth", 0.45).with("smoothing", 0.08).with("rhythm", 1));
        v.put("F-extra-bouncy", p.with("accentHeight", 7).with("alternation", 2).with("stressStretch", 1.6)
                .with("unstressedSquash", 0.8).with("dipDepth", 2.5).with("finalAccentBoost", 2.5));
        v.put("G-playful-light-rhythm", p.with("rhythm", 0.5));
        v.put("H-singsong-faster-beat", p.with("snap", 1).with("vibratoDepth", 0.45).with("smoothing", 0.08)
                .with("rhythm", 1).with("beat", 0.16));
        return v;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "samples/tuning");
        VoicePack pack;
        try (InputStream in = new FileInputStream(System.getProperty("nanatts.pack", "nana.vpk"))) {
            pack = VoicePack.read(in);
        }
        Synthesizer en = Synthesizer.builder(pack).language(Language.ENGLISH).build();
        Synthesizer ja = Synthesizer.builder(pack).language(Language.JAPANESE_KANA).build();
        for (Map.Entry<String, StyleProfile> e : variants().entrySet()) {
            File dir = new File(out, e.getKey());
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            VoiceParams vp = VoiceParams.defaults().style(e.getValue());
            List<AudioClip> all = new ArrayList<>();
            int sr = en.sampleRate();
            for (int i = 0; i <= ENGLISH.length; i++) {
                boolean japanese = i == ENGLISH.length;
                AudioClip clip = japanese ? ja.speak(JAPANESE, vp) : en.speak(ENGLISH[i], vp);
                write(clip, new File(dir, String.format("%02d.wav", i + 1)));
                all.add(clip);
                all.add(new AudioClip(new float[sr / 2], sr));
            }
            write(AudioClip.concat(all, sr), new File(dir, "all.wav"));
            try (OutputStream os = new FileOutputStream(new File(dir, "style.properties"))) {
                os.write(e.getValue().toString().getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("rendered " + dir);
        }
        try (PrintWriter w = new PrintWriter(new File(out, "sentences.txt"), "UTF-8")) {
            for (int i = 0; i < ENGLISH.length; i++) {
                w.printf("%02d  %s%n", i + 1, ENGLISH[i]);
            }
            w.printf("%02d  %s%n", ENGLISH.length + 1, JAPANESE);
        }
    }

    private static void write(AudioClip clip, File f) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            WavIO.write(clip, os);
        }
    }
}
