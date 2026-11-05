package dev.nanatts

import dev.nanatts.prosody.StyleProfile

/** Immutable voice controls. All factors are relative to the voice's natural values (1.0 = default). */
class VoiceParams private constructor(
    /** Base pitch multiplier (1.0 = the recorded pitch of the voicebank). */
    val pitch: Double,
    /** Intonation range multiplier (0 = monotone). */
    val range: Double,
    /** Speaking rate multiplier (2.0 = twice as fast). */
    val speed: Double,
    /** Linear output gain. */
    val volume: Double,
    /** 0..1: blends noise into voiced sounds. */
    val breathiness: Double,
    /** Amount of random pitch drift (0 = perfectly smooth contours). */
    val liveliness: Double,
    /** Speaking style (intonation, rhythm, emphasis); default [StyleProfile.PLAYFUL]. */
    val style: StyleProfile,
) {

    fun style(s: StyleProfile): VoiceParams = VoiceParams(pitch, range, speed, volume, breathiness, liveliness, s)

    fun pitch(v: Double): VoiceParams =
        VoiceParams(positive(v, "pitch"), range, speed, volume, breathiness, liveliness, style)

    fun range(v: Double): VoiceParams =
        VoiceParams(pitch, maxOf(0.0, v), speed, volume, breathiness, liveliness, style)

    fun speed(v: Double): VoiceParams =
        VoiceParams(pitch, range, positive(v, "speed"), volume, breathiness, liveliness, style)

    fun volume(v: Double): VoiceParams =
        VoiceParams(pitch, range, speed, maxOf(0.0, v), breathiness, liveliness, style)

    fun breathiness(v: Double): VoiceParams =
        VoiceParams(pitch, range, speed, volume, maxOf(0.0, minOf(1.0, v)), liveliness, style)

    fun liveliness(v: Double): VoiceParams =
        VoiceParams(pitch, range, speed, volume, breathiness, maxOf(0.0, v), style)

    companion object {
        fun defaults(): VoiceParams = VoiceParams(1.0, 1.0, 1.0, 1.0, 0.0, 1.0, StyleProfile.PLAYFUL)

        private fun positive(v: Double, name: String): Double {
            require(v > 0) { "$name must be > 0" }
            return v
        }
    }
}
