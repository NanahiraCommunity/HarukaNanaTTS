package dev.nanatts;

import dev.nanatts.bank.VoicePack;
import dev.nanatts.io.AudioClip;
import dev.nanatts.prosody.ProsodyModel;
import dev.nanatts.synth.ParamTrack;
import dev.nanatts.synth.Renderer;
import dev.nanatts.text.Phrase;
import dev.nanatts.text.TextFrontend;
import dev.nanatts.world.WorldSynthesizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Text-to-speech entry point. Thread-safe: every call uses its own vocoder state.
 *
 * <pre>{@code
 * VoicePack pack = VoicePack.load(ResourceSource.classpath(), "nana.vpk");
 * Synthesizer tts = Synthesizer.builder(pack).language(Language.ENGLISH).build();
 * AudioClip clip = tts.speak("Hello! How are you today?");
 * }</pre>
 */
public final class Synthesizer {

    private final VoicePack pack;
    private final TextFrontend frontend;
    private final int sampleRate;
    private final int fftSize;
    private final double outputGain;
    private final ProsodyModel prosody = new ProsodyModel();
    private final Renderer renderer;

    private Synthesizer(Builder b) {
        this.pack = b.pack;
        this.frontend = b.frontend != null ? b.frontend : b.language.frontend();
        this.sampleRate = b.sampleRate;
        this.fftSize = sampleRate > 32000 ? 2048 : 1024;
        this.outputGain = b.outputGain;
        this.renderer = new Renderer(pack);
    }

    public static Builder builder(VoicePack pack) {
        return new Builder(pack);
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** Runs only the text frontend (useful for debugging pronunciations). */
    public List<Phrase> analyze(String text) {
        return frontend.analyze(text);
    }

    public AudioClip speak(String text) {
        return speak(text, VoiceParams.defaults());
    }

    public AudioClip speak(String text, VoiceParams params) {
        return speak(frontend.analyze(text), params);
    }

    public AudioClip speak(List<Phrase> phrases, VoiceParams params) {
        List<AudioClip> parts = new ArrayList<>();
        speak(phrases, params, parts::add);
        return AudioClip.concat(parts, sampleRate);
    }

    /**
     * Streams audio phrase by phrase (each clip includes the following pause), so playback can start
     * before the whole text is synthesised.
     */
    public void speak(String text, VoiceParams params, Consumer<AudioClip> sink) {
        speak(frontend.analyze(text), params, sink);
    }

    public void speak(List<Phrase> phrases, VoiceParams params, Consumer<AudioClip> sink) {
        WorldSynthesizer vocoder = new WorldSynthesizer(sampleRate, pack.framePeriod(), fftSize);
        double baseF0 = pack.medianF0() * params.pitch();
        // plan everything first: rhythmic pauses depend on where the next phrase's first beat falls
        java.util.Random variation = new java.util.Random(ProsodyModel.SEED);
        List<Phrase> spoken = new ArrayList<>();
        List<ProsodyModel.Plan> plans = new ArrayList<>();
        for (Phrase phrase : phrases) {
            if (!phrase.morae.isEmpty()) {
                spoken.add(phrase);
                plans.add(prosody.plan(phrase, params, variation));
            }
        }
        long seed = 0x5EED;
        for (int p = 0; p < spoken.size(); p++) {
            Phrase phrase = spoken.get(p);
            ProsodyModel.Plan plan = plans.get(p);
            seed = seed * 31 + phrase.toString().hashCode();
            ParamTrack track = renderer.render(phrase, plan, params, baseF0, seed);
            float[] voice = vocoder.synthesize(
                    track.asFrames(pack.grid(), pack.apBands(), sampleRate, fftSize), seed);
            double pauseSec = phrase.pauseAfter / params.speed();
            if (plan.rhythm > 0 && p + 1 < spoken.size()) {
                pauseSec += plan.rhythm * (rhythmicPause(plan, voice.length / (double) sampleRate,
                        plans.get(p + 1)) - pauseSec);
            }
            int pause = (int) Math.round(Math.max(0, pauseSec) * sampleRate);
            float[] out = new float[voice.length + pause];
            for (int i = 0; i < voice.length; i++) {
                out[i] = softClip(voice[i] * (float) outputGain);
            }
            sink.accept(new AudioClip(out, sampleRate));
        }
    }

    /**
     * Pause that makes the next phrase's first beat fall on the next half-bar after this phrase plus
     * its rest beats, so the beat continues across phrases.
     */
    private static double rhythmicPause(ProsodyModel.Plan plan, double audioSeconds, ProsodyModel.Plan next) {
        double halfBar = plan.halfBar * plan.beat;
        double fromFirstBeat = audioSeconds - Renderer.firstBeat(plan);
        double voiced = plan.span - plan.firstBeat;
        double nextBeat = Math.ceil((voiced + plan.restBeats * plan.beat) / halfBar - 1e-6) * halfBar;
        double pause = nextBeat - fromFirstBeat - Renderer.firstBeat(next);
        while (pause < 0.03) {
            pause += halfBar; // the next phrase's pickup does not fit: start on the following half bar
        }
        return pause;
    }

    private static float softClip(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) {
            return x;
        }
        float over = (a - 0.8f) / 0.2f;
        float y = 0.8f + 0.2f * (float) Math.tanh(over);
        return Math.copySign(y, x);
    }

    public static final class Builder {
        private final VoicePack pack;
        private Language language = Language.ENGLISH;
        private TextFrontend frontend;
        private int sampleRate = 24000;
        private double outputGain = 2.5;

        private Builder(VoicePack pack) {
            this.pack = pack;
        }

        public Builder language(Language language) {
            this.language = language;
            return this;
        }

        /** Custom frontend (overrides {@link #language}). */
        public Builder frontend(TextFrontend frontend) {
            this.frontend = frontend;
            return this;
        }

        /** Output sample rate (default 24000; content above the pack's grid limit is rolled off). */
        public Builder sampleRate(int sampleRate) {
            if (sampleRate < 8000 || sampleRate > 96000) {
                throw new IllegalArgumentException("sample rate out of range: " + sampleRate);
            }
            this.sampleRate = sampleRate;
            return this;
        }

        /** Linear make-up gain applied before the soft limiter (default 2.5, i.e. +8 dB). */
        public Builder outputGain(double gain) {
            this.outputGain = gain;
            return this;
        }

        public Synthesizer build() {
            return new Synthesizer(this);
        }
    }
}
