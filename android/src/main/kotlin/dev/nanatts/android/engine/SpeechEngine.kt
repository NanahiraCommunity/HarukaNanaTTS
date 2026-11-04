package dev.nanatts.android.engine

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.Voice
import java.util.Locale

/**
 * The seam a real synthesizer plugs into. The Android-facing service
 * ([dev.nanatts.android.NanaTtsService]) handles locale negotiation, voice
 * selection, buffering and cancellation; an engine only has to list its
 * voices and turn text into PCM.
 */
interface SpeechEngine {
    /** All voices this engine can speak with. Must be stable between calls. */
    val voices: List<EngineVoice>

    /** Prepare [voice] for synthesis (load models, etc.). Called off the main thread. */
    fun load(voice: EngineVoice): Boolean = true

    /**
     * Synthesize [text] with [voice] and stream the result into [out].
     * Call [AudioSink.start] exactly once before writing, and return as soon
     * as a write returns false (the request was stopped).
     */
    fun synthesize(text: String, voice: EngineVoice, params: SynthesisParams, out: AudioSink)

    fun release() {}
}

data class EngineVoice(
    /** Unique, stable identifier; exposed to apps as [Voice.getName]. */
    val name: String,
    val locale: Locale,
    val quality: Int = Voice.QUALITY_NORMAL,
    val latency: Int = Voice.LATENCY_NORMAL,
    val requiresNetwork: Boolean = false,
    val features: Set<String> = emptySet(),
)

data class SynthesisParams(
    /** 100 = normal speed, 200 = twice as fast. */
    val speechRate: Int,
    /** 100 = normal pitch. */
    val pitch: Int,
    /** Raw request params (utterance id, volume, engine-specific keys). */
    val extras: Bundle,
)

data class PcmFormat(
    val sampleRateHz: Int,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val channelCount: Int = 1,
)

interface AudioSink {
    /** Declare the output format. Returns false if the request was already stopped. */
    fun start(format: PcmFormat): Boolean

    /** Write PCM in [format]. Any length is fine; the sink chunks it. Returns false when stopped. */
    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): Boolean

    /**
     * Mark that the audio written next speaks text[[start], [end]) of the request,
     * e.g. one word. Lets apps highlight text as it is spoken (API 26+; ignored before).
     */
    fun markRange(start: Int, end: Int)

    val isStopped: Boolean
}
