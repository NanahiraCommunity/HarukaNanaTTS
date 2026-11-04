package dev.nanatts.cli;

import dev.nanatts.Language;
import dev.nanatts.Synthesizer;
import dev.nanatts.VoiceParams;
import dev.nanatts.bank.VoicePack;
import dev.nanatts.io.AudioClip;
import dev.nanatts.io.WavIO;
import dev.nanatts.prosody.StyleProfile;
import dev.nanatts.text.Phrase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Command-line demo: speaks text through the speakers or writes a wav file. */
public final class Say {

    private static final String USAGE = String.join("\n",
            "usage: say [options] <text...>",
            "  -o, --out FILE       write a wav file instead of playing",
            "  -f, --file FILE      read the text from FILE ('-' = stdin)",
            "  -l, --lang en|ja     input language (ja = kana only), default en",
            "  -p, --pitch X        pitch factor (default 1.0)",
            "  -s, --speed X        speaking rate factor (default 1.0)",
            "  -r, --range X        intonation range factor (default 1.0)",
            "  -b, --breath X       breathiness 0..1 (default 0)",
            "  -v, --volume X       volume factor (default 1.0)",
            "      --style S        playful (default), neutral, or a .properties file of knobs",
            "      --set KNOB=X     override one style knob (repeatable; see --knobs)",
            "      --knobs          list the style knobs with their playful values",
            "      --rate HZ        output sample rate (default 24000)",
            "      --pack FILE      voice pack (default: -Dnanatts.pack or ./nana.vpk)",
            "      --phonemes       print the kana transcription",
            "  -h, --help");

    public static void main(String[] args) throws Exception {
        String out = null;
        String file = null;
        String pack = System.getProperty("nanatts.pack", "nana.vpk");
        Language lang = Language.ENGLISH;
        VoiceParams vp = VoiceParams.defaults();
        int rate = 24000;
        boolean phonemes = false;
        List<String> words = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-o": case "--out": out = args[++i]; break;
                    case "-f": case "--file": file = args[++i]; break;
                    case "-l": case "--lang": lang = parseLang(args[++i]); break;
                    case "-p": case "--pitch": vp = vp.pitch(Double.parseDouble(args[++i])); break;
                    case "-s": case "--speed": vp = vp.speed(Double.parseDouble(args[++i])); break;
                    case "-r": case "--range": vp = vp.range(Double.parseDouble(args[++i])); break;
                    case "-b": case "--breath": vp = vp.breathiness(Double.parseDouble(args[++i])); break;
                    case "-v": case "--volume": vp = vp.volume(Double.parseDouble(args[++i])); break;
                    case "--style": vp = vp.style(loadStyle(args[++i])); break;
                    case "--set": {
                        String[] kv = args[++i].split("=", 2);
                        if (kv.length != 2) {
                            throw new IllegalArgumentException("--set expects KNOB=VALUE");
                        }
                        vp = vp.style(vp.style().with(kv[0].trim(), Double.parseDouble(kv[1].trim())));
                        break;
                    }
                    case "--knobs":
                        System.out.print(StyleProfile.PLAYFUL);
                        return;
                    case "--rate": rate = Integer.parseInt(args[++i]); break;
                    case "--pack": pack = args[++i]; break;
                    case "--phonemes": phonemes = true; break;
                    case "-h": case "--help":
                        System.out.println(USAGE);
                        return;
                    default:
                        if (a.startsWith("-") && a.length() > 1) {
                            throw new IllegalArgumentException("unknown option " + a);
                        }
                        words.add(a);
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage() == null ? "missing option value" : e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        String text = file != null ? readText(file) : String.join(" ", words);
        if (text.isBlank()) {
            System.err.println(USAGE);
            System.exit(2);
        }

        VoicePack voice;
        try (InputStream in = new FileInputStream(pack)) {
            voice = VoicePack.read(in);
        } catch (IOException e) {
            System.err.println("cannot read voice pack " + pack + ": " + e.getMessage()
                    + "\n(build it with: gradle :bankc:compileVoice)");
            System.exit(1);
            return;
        }
        Synthesizer tts = Synthesizer.builder(voice).language(lang).sampleRate(rate).build();
        if (phonemes) {
            for (Phrase p : tts.analyze(text)) {
                System.out.println(p);
            }
        }
        if (out != null) {
            AudioClip clip = tts.speak(text, vp);
            try (OutputStream os = new FileOutputStream(out)) {
                WavIO.write(clip, os);
            }
            System.out.printf("wrote %s (%.2f s)%n", out, clip.durationSeconds());
        } else {
            play(tts, text, vp);
        }
    }

    static StyleProfile loadStyle(String s) throws IOException {
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "playful": return StyleProfile.PLAYFUL;
            case "neutral": return StyleProfile.NEUTRAL;
            default:
                try (InputStream in = new FileInputStream(s)) {
                    return StyleProfile.PLAYFUL.load(in);
                }
        }
    }

    private static Language parseLang(String s) {
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "en": case "english": return Language.ENGLISH;
            case "ja": case "jp": case "japanese": return Language.JAPANESE_KANA;
            default: throw new IllegalArgumentException("unknown language " + s);
        }
    }

    private static String readText(String file) throws IOException {
        InputStream in = file.equals("-") ? System.in : new FileInputStream(new File(file));
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        } finally {
            if (in != System.in) {
                in.close();
            }
        }
    }

    /** Streams phrase by phrase so playback starts while later phrases are still being synthesised. */
    private static void play(Synthesizer tts, String text, VoiceParams vp) throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(tts.sampleRate(), 16, 1, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, tts.sampleRate());
        line.start();
        tts.speak(text, vp, clip -> {
            short[] pcm = clip.toPcm16();
            byte[] b = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                b[2 * i] = (byte) pcm[i];
                b[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            line.write(b, 0, b.length);
        });
        line.drain();
        line.close();
    }
}
