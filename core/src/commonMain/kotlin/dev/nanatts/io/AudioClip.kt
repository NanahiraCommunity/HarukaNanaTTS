package dev.nanatts.io

import dev.nanatts.util.jround

/** Mono PCM audio as floats in [-1, 1]. */
class AudioClip(val samples: FloatArray, val sampleRate: Int) {

    val durationSeconds: Double
        get() = samples.size / sampleRate.toDouble()

    /** 16-bit signed PCM, clipped. Suitable for Android `AudioTrack`. */
    fun toPcm16(): ShortArray = ShortArray(samples.size) {
        jround(samples[it].coerceIn(-1f, 1f) * 32767f).toShort()
    }

    companion object {
        /** Concatenates clips of the same sample rate. */
        fun concat(clips: List<AudioClip>, sampleRate: Int): AudioClip {
            var total = 0
            for (c in clips) {
                require(c.sampleRate == sampleRate) { "sample rate mismatch" }
                total += c.samples.size
            }
            val out = FloatArray(total)
            var pos = 0
            for (c in clips) {
                c.samples.copyInto(out, pos)
                pos += c.samples.size
            }
            return AudioClip(out, sampleRate)
        }
    }
}
