package dev.nanatts.cli

import dev.nanatts.Language
import dev.nanatts.Synthesizer
import dev.nanatts.VoiceParams
import dev.nanatts.bank.VoicePack
import dev.nanatts.io.WavIO
import dev.nanatts.prosody.StyleProfile
import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.system.exitProcess

/** Command-line demo: speaks text through the speakers or writes a wav file. */
object Say {

    private val USAGE = """
        usage: say [options] <text...>
          -o, --out FILE       write a wav file instead of playing
          -f, --file FILE      read the text from FILE ('-' = stdin)
          -l, --lang en|ja     input language (ja = kana only), default en
          -p, --pitch X        pitch factor (default 1.0)
          -s, --speed X        speaking rate factor (default 1.0)
          -r, --range X        intonation range factor (default 1.0)
          -b, --breath X       breathiness 0..1 (default 0)
          -v, --volume X       volume factor (default 1.0)
              --style S        playful (default), neutral, or a .properties file of knobs
              --set KNOB=X     override one style knob (repeatable; see --knobs)
              --knobs          list the style knobs with their playful values
              --rate HZ        output sample rate (default 24000)
              --pack FILE      voice pack (default: -Dnanatts.pack or ./nana.vpk)
              --phonemes       print the kana transcription
          -h, --help
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        var out: String? = null
        var file: String? = null
        var pack = System.getProperty("nanatts.pack", "nana.vpk")
        var lang = Language.ENGLISH
        var vp = VoiceParams.defaults()
        var rate = 24000
        var phonemes = false
        val words = ArrayList<String>()
        try {
            var i = 0
            fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("missing option value")
            while (i < args.size) {
                when (val a = args[i]) {
                    "-o", "--out" -> out = value()
                    "-f", "--file" -> file = value()
                    "-l", "--lang" -> lang = parseLang(value())
                    "-p", "--pitch" -> vp = vp.pitch(value().toDouble())
                    "-s", "--speed" -> vp = vp.speed(value().toDouble())
                    "-r", "--range" -> vp = vp.range(value().toDouble())
                    "-b", "--breath" -> vp = vp.breathiness(value().toDouble())
                    "-v", "--volume" -> vp = vp.volume(value().toDouble())
                    "--style" -> vp = vp.style(loadStyle(value()))
                    "--set" -> {
                        val kv = value().split("=", limit = 2)
                        require(kv.size == 2) { "--set expects KNOB=VALUE" }
                        vp = vp.style(vp.style.with(kv[0].trim(), kv[1].trim().toDouble()))
                    }
                    "--knobs" -> {
                        print(StyleProfile.PLAYFUL)
                        return
                    }
                    "--rate" -> rate = value().toInt()
                    "--pack" -> pack = value()
                    "--phonemes" -> phonemes = true
                    "-h", "--help" -> {
                        println(USAGE)
                        return
                    }
                    else -> {
                        require(!(a.startsWith("-") && a.length > 1)) { "unknown option $a" }
                        words.add(a)
                    }
                }
                i++
            }
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(2)
        } catch (e: IOException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(2)
        }
        val text = file?.let { readText(it) } ?: words.joinToString(" ")
        if (text.isBlank()) {
            System.err.println(USAGE)
            exitProcess(2)
        }

        val voice = try {
            VoicePack.read(File(pack).readBytes())
        } catch (e: Exception) {
            System.err.println("cannot read voice pack $pack: ${e.message}\n(build it with: gradle :bankc:compileVoice)")
            exitProcess(1)
        }
        val tts = Synthesizer.builder(voice).language(lang).sampleRate(rate).build()
        if (phonemes) {
            for (p in tts.analyze(text)) {
                println(p)
            }
        }
        if (out != null) {
            val clip = tts.speak(text, vp)
            File(out).writeBytes(WavIO.encode(clip))
            System.out.printf("wrote %s (%.2f s)%n", out, clip.durationSeconds)
        } else {
            play(tts, text, vp)
        }
    }

    internal fun loadStyle(s: String): StyleProfile = when (s.lowercase()) {
        "playful" -> StyleProfile.PLAYFUL
        "neutral" -> StyleProfile.NEUTRAL
        else -> StyleProfile.PLAYFUL.load(File(s).readText())
    }

    private fun parseLang(s: String): Language = when (s.lowercase()) {
        "en", "english" -> Language.ENGLISH
        "ja", "jp", "japanese" -> Language.JAPANESE_KANA
        else -> throw IllegalArgumentException("unknown language $s")
    }

    private fun readText(file: String): String =
        if (file == "-") System.`in`.readBytes().decodeToString() else File(file).readText()

    /** Streams phrase by phrase so playback starts while later phrases are still being synthesised. */
    private fun play(tts: Synthesizer, text: String, vp: VoiceParams) {
        val fmt = AudioFormat(tts.sampleRate.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(fmt)
        line.open(fmt, tts.sampleRate)
        line.start()
        tts.speak(text, vp) { clip ->
            val pcm = clip.toPcm16()
            val b = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                b[2 * i] = pcm[i].toByte()
                b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
            }
            line.write(b, 0, b.size)
        }
        line.drain()
        line.close()
    }
}
