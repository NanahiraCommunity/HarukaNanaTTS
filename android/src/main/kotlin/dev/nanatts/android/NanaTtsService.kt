package dev.nanatts.android

import android.media.AudioFormat
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import dev.nanatts.android.engine.AudioSink
import dev.nanatts.android.engine.EngineVoice
import dev.nanatts.android.engine.Engines
import dev.nanatts.android.engine.PcmFormat
import dev.nanatts.android.engine.SpeechEngine
import dev.nanatts.android.engine.SynthesisParams

/**
 * Android TTS engine entry point (action android.intent.action.TTS_SERVICE).
 * Translates the framework contract onto [SpeechEngine].
 */
class NanaTtsService : TextToSpeechService() {
    private val engine: SpeechEngine by lazy { Engines.get(this) }

    @Volatile private var stopRequested = false
    @Volatile private var currentVoice: EngineVoice? = null

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    // --- Language API (pre-21 style, still used by TextToSpeech.setLanguage) ---

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        Locales.bestVoice(engine.voices, lang, country, variant)?.second
            ?: TextToSpeech.LANG_NOT_SUPPORTED

    override fun onGetLanguage(): Array<String> =
        currentVoice?.let { Locales.iso3Triple(it.locale) } ?: arrayOf("", "", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val (voice, level) = Locales.bestVoice(engine.voices, lang, country, variant)
            ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (!engine.load(voice)) return TextToSpeech.LANG_MISSING_DATA
        currentVoice = voice
        return level
    }

    /** Legacy (pre-Voice) TextToSpeech.getFeatures(Locale). */
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): Set<String> =
        Locales.bestVoice(engine.voices, lang, country, variant)?.first?.features ?: emptySet()

    // --- Voice API (API 21+) ---

    override fun onGetVoices(): List<Voice> = engine.voices.map { it.toVoice() }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (engine.voices.any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        val voice = engine.voices.firstOrNull { it.name == voiceName } ?: return TextToSpeech.ERROR
        if (!engine.load(voice)) return TextToSpeech.ERROR
        currentVoice = voice
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        Locales.bestVoice(engine.voices, lang, country, variant)?.first?.name

    // --- Synthesis ---

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val voice = resolveVoice(request)
        if (voice == null) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            callback.done()
            return
        }

        val sink = CallbackSink(callback)
        val params = SynthesisParams(request.speechRate, request.pitch, request.params)
        try {
            engine.synthesize(request.charSequenceText?.toString() ?: "", voice, params, sink)
            if (!sink.started) {
                // Nothing was produced (e.g. empty text); the framework still needs start/done.
                callback.start(DEFAULT_FORMAT.sampleRateHz, DEFAULT_FORMAT.encoding, DEFAULT_FORMAT.channelCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
        callback.done()
    }

    private fun resolveVoice(request: SynthesisRequest): EngineVoice? {
        request.voiceName?.let { name -> engine.voices.firstOrNull { it.name == name }?.let { return it } }
        Locales.bestVoice(engine.voices, request.language, request.country, request.variant)?.let { return it.first }
        // Callers such as the car assistant speak in the device locale (e.g. German); rather
        // than fail, read it with the loaded or first voice.
        return currentVoice ?: engine.voices.firstOrNull()
    }

    private inner class CallbackSink(private val callback: SynthesisCallback) : AudioSink {
        var started = false
            private set
        private var bytesPerFrame = 2
        private var framesWritten = 0L

        override val isStopped: Boolean
            get() = stopRequested

        override fun start(format: PcmFormat): Boolean {
            check(!started) { "AudioSink.start called twice" }
            if (stopRequested) return false
            started = true
            bytesPerFrame = bytesPerSample(format.encoding) * format.channelCount
            return callback.start(format.sampleRateHz, format.encoding, format.channelCount) == TextToSpeech.SUCCESS
        }

        override fun write(pcm: ByteArray, offset: Int, length: Int): Boolean {
            check(started) { "AudioSink.write before start" }
            val max = callback.maxBufferSize
            var pos = offset
            val end = offset + length
            while (pos < end) {
                if (stopRequested) return false
                val n = minOf(max, end - pos)
                if (callback.audioAvailable(pcm, pos, n) != TextToSpeech.SUCCESS) return false
                pos += n
            }
            framesWritten += length / bytesPerFrame
            return !stopRequested
        }

        override fun markRange(start: Int, end: Int) {
            check(started) { "AudioSink.markRange before start" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                callback.rangeStart(framesWritten.toInt(), start, end)
            }
        }
    }

    private fun EngineVoice.toVoice() =
        Voice(name, locale, quality, latency, requiresNetwork, features)

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private companion object {
        const val TAG = "NanaTts"
        val DEFAULT_FORMAT = PcmFormat(22050)
    }
}
