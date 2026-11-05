package dev.nanatts.cli

import dev.nanatts.Language
import dev.nanatts.Synthesizer
import dev.nanatts.VoiceParams
import dev.nanatts.bank.VoicePack
import dev.nanatts.io.AudioClip
import dev.nanatts.io.WavIO
import dev.nanatts.prosody.StyleProfile
import java.io.File
import java.io.IOException

/**
 * Renders the same test sentences in several style variants for listening comparisons.
 * Usage: `Audition <outDir>` (voice pack from `-Dnanatts.pack`).
 */
object Audition {

    private val ENGLISH = arrayOf(
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
    )
    private const val JAPANESE = "おはよう！きょうも がんばろうね〜"

    /** Variants named by how they sound, so they can be compared without knowing the knobs. */
    private fun variants(): Map<String, StyleProfile> {
        val p = StyleProfile.PLAYFUL
        return linkedMapOf(
            "A-calm" to StyleProfile.NEUTRAL,
            "B-playful" to p,
            "C-fewer-bigger-accents" to p.with("accentDensity", 0.5).with("accentHeight", 6.5).with("stressStretch", 1.55),
            "D-singsong" to p.with("snap", 1.0).with("vibratoDepth", 0.45).with("smoothing", 0.08).with("rhythm", 1.0),
            "E-sparse-singsong" to p.with("accentDensity", 0.5).with("accentHeight", 6.5).with("snap", 0.8)
                .with("vibratoDepth", 0.45).with("smoothing", 0.08).with("rhythm", 1.0),
            "F-extra-bouncy" to p.with("accentHeight", 7.0).with("alternation", 2.0).with("stressStretch", 1.6)
                .with("unstressedSquash", 0.8).with("dipDepth", 2.5).with("finalAccentBoost", 2.5),
            "G-playful-light-rhythm" to p.with("rhythm", 0.5),
            "H-singsong-faster-beat" to p.with("snap", 1.0).with("vibratoDepth", 0.45).with("smoothing", 0.08)
                .with("rhythm", 1.0).with("beat", 0.16),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(if (args.isNotEmpty()) args[0] else "samples/tuning")
        val pack = VoicePack.read(File(System.getProperty("nanatts.pack", "nana.vpk")).readBytes())
        val en = Synthesizer.builder(pack).language(Language.ENGLISH).build()
        val ja = Synthesizer.builder(pack).language(Language.JAPANESE_KANA).build()
        for ((name, style) in variants()) {
            val dir = File(out, name)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("cannot create $dir")
            }
            val vp = VoiceParams.defaults().style(style)
            val all = ArrayList<AudioClip>()
            val sr = en.sampleRate
            for (i in 0..ENGLISH.size) {
                val japanese = i == ENGLISH.size
                val clip = if (japanese) ja.speak(JAPANESE, vp) else en.speak(ENGLISH[i], vp)
                File(dir, "%02d.wav".format(i + 1)).writeBytes(WavIO.encode(clip))
                all.add(clip)
                all.add(AudioClip(FloatArray(sr / 2), sr))
            }
            File(dir, "all.wav").writeBytes(WavIO.encode(AudioClip.concat(all, sr)))
            File(dir, "style.properties").writeText(style.toString())
            println("rendered $dir")
        }
        File(out, "sentences.txt").printWriter().use { w ->
            for (i in ENGLISH.indices) {
                w.printf("%02d  %s%n", i + 1, ENGLISH[i])
            }
            w.printf("%02d  %s%n", ENGLISH.size + 1, JAPANESE)
        }
    }
}
