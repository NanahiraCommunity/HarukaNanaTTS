package dev.nanatts

import dev.nanatts.bank.VoicePack
import dev.nanatts.io.AudioClip
import dev.nanatts.prosody.ProsodyModel
import dev.nanatts.synth.Renderer
import dev.nanatts.text.Phrase
import dev.nanatts.text.TextFrontend
import dev.nanatts.util.JavaRandom
import dev.nanatts.util.javaHash
import dev.nanatts.util.jround
import dev.nanatts.world.WorldSynthesizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.tanh
import kotlin.math.withSign

/**
 * Text-to-speech entry point. Thread-safe: every call uses its own vocoder state.
 *
 * ```
 * val pack = VoicePack.load(ResourceSource.classpath(), "nana.vpk")
 * val tts = Synthesizer.builder(pack).language(Language.ENGLISH).build()
 * val clip = tts.speak("Hello! How are you today?")
 * ```
 */
class Synthesizer private constructor(b: Builder) {

    private val pack = b.pack
    private val frontend: TextFrontend = b.frontend ?: b.language.frontend()
    val sampleRate: Int = b.sampleRate
    private val fftSize = if (sampleRate > 32000) 2048 else 1024
    private val outputGain = b.outputGain
    private val prosody = ProsodyModel()
    private val renderer = Renderer(pack)

    /** Runs only the text frontend (useful for debugging pronunciations). */
    fun analyze(text: String): List<Phrase> = frontend.analyze(text)

    fun speak(text: String, params: VoiceParams = VoiceParams.defaults()): AudioClip =
        speak(frontend.analyze(text), params)

    fun speak(phrases: List<Phrase>, params: VoiceParams): AudioClip {
        val parts = ArrayList<AudioClip>()
        speak(phrases, params) { parts.add(it) }
        return AudioClip.concat(parts, sampleRate)
    }

    /**
     * Streams audio phrase by phrase (each clip includes the following pause), so playback can start
     * before the whole text is synthesised.
     */
    fun speak(text: String, params: VoiceParams, sink: (AudioClip) -> Unit) {
        speak(frontend.analyze(text), params, sink)
    }

    fun speak(phrases: List<Phrase>, params: VoiceParams, sink: (AudioClip) -> Unit) {
        val vocoder = WorldSynthesizer(sampleRate, pack.framePeriod, fftSize)
        val baseF0 = pack.medianF0 * params.pitch
        // plan everything first: rhythmic pauses depend on where the next phrase's first beat falls
        val variation = JavaRandom(ProsodyModel.SEED)
        val spoken = ArrayList<Phrase>()
        val plans = ArrayList<ProsodyModel.Plan>()
        for (phrase in phrases) {
            if (phrase.morae.isNotEmpty()) {
                spoken.add(phrase)
                plans.add(prosody.plan(phrase, params, variation))
            }
        }
        var seed = 0x5EEDL
        for (p in spoken.indices) {
            val phrase = spoken[p]
            val plan = plans[p]
            seed = seed * 31 + javaHash(phrase.toString())
            val track = renderer.render(phrase, plan, params, baseF0, seed)
            val voice = vocoder.synthesize(track.asFrames(pack.grid, pack.apBands, sampleRate, fftSize), seed)
            var pauseSec = phrase.pauseAfter / params.speed
            if (plan.rhythm > 0 && p + 1 < spoken.size) {
                pauseSec += plan.rhythm * (rhythmicPause(plan, voice.size / sampleRate.toDouble(), plans[p + 1]) - pauseSec)
            }
            val pause = jround(maxOf(0.0, pauseSec) * sampleRate)
            val out = FloatArray(voice.size + pause)
            for (i in voice.indices) {
                out[i] = softClip(voice[i] * outputGain.toFloat())
            }
            sink(AudioClip(out, sampleRate))
        }
    }

    class Builder internal constructor(internal val pack: VoicePack) {
        internal var language = Language.ENGLISH
        internal var frontend: TextFrontend? = null
        internal var sampleRate = 24000
        internal var outputGain = 2.5

        fun language(language: Language): Builder {
            this.language = language
            return this
        }

        /** Custom frontend (overrides [language]). */
        fun frontend(frontend: TextFrontend): Builder {
            this.frontend = frontend
            return this
        }

        /** Output sample rate (default 24000; content above the pack's grid limit is rolled off). */
        fun sampleRate(sampleRate: Int): Builder {
            require(sampleRate in 8000..96000) { "sample rate out of range: $sampleRate" }
            this.sampleRate = sampleRate
            return this
        }

        /** Linear make-up gain applied before the soft limiter (default 2.5, i.e. +8 dB). */
        fun outputGain(gain: Double): Builder {
            this.outputGain = gain
            return this
        }

        fun build(): Synthesizer = Synthesizer(this)
    }

    companion object {
        fun builder(pack: VoicePack): Builder = Builder(pack)

        /**
         * Pause that makes the next phrase's first beat fall on the next half-bar after this phrase plus
         * its rest beats, so the beat continues across phrases.
         */
        private fun rhythmicPause(plan: ProsodyModel.Plan, audioSeconds: Double, next: ProsodyModel.Plan): Double {
            val halfBar = plan.halfBar * plan.beat
            val fromFirstBeat = audioSeconds - Renderer.firstBeat(plan)
            val voiced = plan.span - plan.firstBeat
            val nextBeat = ceil((voiced + plan.restBeats * plan.beat) / halfBar - 1e-6) * halfBar
            var pause = nextBeat - fromFirstBeat - Renderer.firstBeat(next)
            while (pause < 0.03) {
                pause += halfBar // the next phrase's pickup does not fit: start on the following half bar
            }
            return pause
        }

        private fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= 0.8f) {
                return x
            }
            val over = (a - 0.8f) / 0.2f
            val y = 0.8f + 0.2f * tanh(over.toDouble()).toFloat()
            return y.withSign(x)
        }
    }
}
