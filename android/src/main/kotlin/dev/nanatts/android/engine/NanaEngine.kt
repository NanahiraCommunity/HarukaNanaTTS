package dev.nanatts.android.engine

import android.speech.tts.Voice
import android.util.Log
import dev.nanatts.Language
import dev.nanatts.Synthesizer
import dev.nanatts.VoiceParams
import dev.nanatts.bank.VoicePack
import dev.nanatts.io.AudioClip
import dev.nanatts.io.ResourceSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 春歌ナナ voice via the haruka-nana library (dev.nanatts). The voice pack is
 * loaded from [resources] on first use and shared by both voices.
 */
class NanaEngine(private val resources: ResourceSource) : SpeechEngine {
    private val languages = mapOf(
        VOICE_EN to Language.ENGLISH,
        VOICE_JA to Language.JAPANESE_KANA,
    )

    override val voices = listOf(
        // English is spoken with a Japanese accent; inline kana words work too.
        EngineVoice(VOICE_EN, Locale.US),
        // Kana only for now; kanji are skipped by the library.
        EngineVoice(VOICE_JA, Locale.JAPAN),
    )

    private val pack: VoicePack by lazy { VoicePack.load(resources, PACK_ASSET) }
    private val synthesizers = ConcurrentHashMap<Language, Synthesizer>()

    private fun synthesizer(voice: EngineVoice): Synthesizer {
        val language = languages.getValue(voice.name)
        return synthesizers.getOrPut(language) {
            Synthesizer.builder(pack).language(language).sampleRate(SAMPLE_RATE).build()
        }
    }

    override fun load(voice: EngineVoice): Boolean =
        runCatching { synthesizer(voice) }
            .onFailure { Log.e(TAG, "Failed to load ${voice.name}", it) }
            .isSuccess

    override fun synthesize(text: String, voice: EngineVoice, params: SynthesisParams, out: AudioSink) {
        val tts = synthesizer(voice)
        val voiceParams = VoiceParams.defaults()
            .speed(params.speechRate.coerceAtLeast(10) / 100.0)
            .pitch(params.pitch.coerceAtLeast(10) / 100.0)
        if (!out.start(PcmFormat(tts.sampleRate()))) return

        // The library streams one clip per phrase; abort between phrases once stopped.
        try {
            tts.speak(text, voiceParams) { clip ->
                if (!out.write(clip.toPcm16Le())) throw Stopped
            }
        } catch (_: Stopped) {
        }
    }

    private object Stopped : RuntimeException() {
        private fun readResolve(): Any = Stopped
        override fun fillInStackTrace(): Throwable = this
    }

    private fun AudioClip.toPcm16Le(): ByteArray {
        val pcm = toPcm16()
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        return bytes
    }

    companion object {
        const val PACK_ASSET = "nana.vpk"
        const val VOICE_EN = "nana-en-US"
        const val VOICE_JA = "nana-ja-JP"
        private const val SAMPLE_RATE = 24000
        private const val TAG = "NanaEngine"
    }
}
