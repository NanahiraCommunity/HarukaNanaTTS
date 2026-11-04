package dev.nanatts.wasm;

import dev.nanatts.Language;
import dev.nanatts.Synthesizer;
import dev.nanatts.VoiceParams;
import dev.nanatts.bank.VoicePack;
import dev.nanatts.g2p.CmuDict;
import dev.nanatts.g2p.EnglishFrontend;
import dev.nanatts.io.AudioClip;
import dev.nanatts.prosody.StyleProfile;
import dev.nanatts.text.KanaFrontend;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * WebAssembly entry point (compiled by TeaVM). The voice pack and dictionary are handed over as bytes,
 * because a WebAssembly module has no class path or file system of its own.
 */
public final class NanaTts {

    private static VoicePack pack;
    private static Synthesizer english;
    private static Synthesizer japanese;
    private static CmuDict dictionary;

    private NanaTts() {
    }

    public static void main(String[] args) {
        // nothing to do: the module is driven through the exported functions
    }

    /** Loads the compiled voice pack ({@code nana.vpk}). */
    @JSExport
    public static void loadVoice(Int8Array bytes) {
        try {
            pack = VoicePack.read(new ByteArrayInputStream(bytes.copyToJavaArray()));
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid voice pack: " + e.getMessage(), e);
        }
        english = null;
        japanese = null;
    }

    /** Loads the pronunciation dictionary ({@code cmudict.tsv.gz}). */
    @JSExport
    public static void loadDictionary(Int8Array gzipped) {
        byte[] data = gzipped.copyToJavaArray();
        dictionary = CmuDict.load(name -> new ByteArrayInputStream(data));
        english = null;
    }

    @JSExport
    public static int sampleRate() {
        return 24000;
    }

    /**
     * Synthesises text and returns mono samples in [-1, 1] at {@link #sampleRate()}.
     *
     * @param language "en" (English, may contain kana) or "ja" (kana only)
     * @param style    "playful", "neutral", or properties text ({@code knob=value} lines) applied on top of playful
     */
    @JSExport
    public static Float32Array speak(String text, String language, String style, double pitch, double speed,
                                     double volume) {
        VoiceParams vp = VoiceParams.defaults().style(style(style)).pitch(pitch).speed(speed).volume(volume);
        AudioClip clip = synthesizer(language).speak(text, vp);
        return Float32Array.copyFromJavaArray(clip.samples());
    }

    /** The kana transcription the synthesiser will speak (for debugging pronunciations). */
    @JSExport
    public static String analyze(String text, String language) {
        return synthesizer(language).analyze(text).toString();
    }

    private static Synthesizer synthesizer(String language) {
        if (pack == null) {
            throw new IllegalStateException("call loadVoice() first");
        }
        if ("ja".equals(language)) {
            if (japanese == null) {
                japanese = Synthesizer.builder(pack).language(Language.JAPANESE_KANA).frontend(new KanaFrontend()).build();
            }
            return japanese;
        }
        if (dictionary == null) {
            throw new IllegalStateException("call loadDictionary() first");
        }
        if (english == null) {
            english = Synthesizer.builder(pack).frontend(new EnglishFrontend(dictionary)).build();
        }
        return english;
    }

    private static StyleProfile style(String style) {
        if (style == null || style.isEmpty() || style.equals("playful")) {
            return StyleProfile.PLAYFUL;
        }
        if (style.equals("neutral")) {
            return StyleProfile.NEUTRAL;
        }
        try {
            return StyleProfile.PLAYFUL.load(new ByteArrayInputStream(style.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid style: " + e.getMessage(), e);
        }
    }
}
